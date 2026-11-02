package cn.gulupai.server.dice.application;

import cn.gulupai.server.common.api.ApiCode;
import cn.gulupai.server.common.api.ApiException;
import cn.gulupai.server.common.audit.AuditService;
import cn.gulupai.server.common.idempotency.IdempotencyRequest;
import cn.gulupai.server.common.idempotency.IdempotencyService;
import cn.gulupai.server.common.security.AuthenticatedUser;
import cn.gulupai.server.common.security.CurrentUserPolicy;
import cn.gulupai.server.dice.api.DiceRequests;
import cn.gulupai.server.dice.api.DiceViews;
import cn.gulupai.server.dice.persistence.DiceRepository;
import cn.gulupai.server.dice.persistence.DiceRepository.ParticipantRow;
import cn.gulupai.server.dice.persistence.DiceRepository.RollRow;
import cn.gulupai.server.dice.persistence.DiceRepository.RoundRow;
import cn.gulupai.server.dice.persistence.DiceRepository.SessionRow;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class DiceService {

    private static final Duration INVITE_TTL = Duration.ofMinutes(15);
    private static final Duration ROLL_TTL = Duration.ofMinutes(30);
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final DiceRepository repo;
    private final CurrentUserPolicy policy;
    private final IdempotencyService idempotency;
    private final AuditService audit;
    private final RandomSource random;
    private final ObjectMapper mapper;
    private final Clock clock;

    public DiceService(DiceRepository repo, CurrentUserPolicy policy,
                       IdempotencyService idempotency, AuditService audit,
                       RandomSource random, ObjectMapper mapper, Clock clock) {
        this.repo = repo;
        this.policy = policy;
        this.idempotency = idempotency;
        this.audit = audit;
        this.random = random;
        this.mapper = mapper;
        this.clock = clock;
    }

    public DiceViews.Session create(AuthenticatedUser principal, String taskId,
                                    DiceRequests.CreateSessionRequest request,
                                    String key, String path) {
        policy.requireActiveBusinessUser(principal);
        DiceRepository.MarkTaskRow task = requireTask(taskId);
        if (!repo.leader(task.projectId(), principal.userId())) throw new ApiException(ApiCode.FORBIDDEN);
        return idempotency.execute(request(principal, "POST", path, key, request),
                DiceViews.Session.class,
                () -> policy.requireActiveBusinessUser(principal),
                () -> createOnce(principal, task, request));
    }

    private DiceViews.Session createOnce(AuthenticatedUser principal, DiceRepository.MarkTaskRow task,
                                        DiceRequests.CreateSessionRequest request) {
        DiceRepository.MarkTaskRow current = requireTask(task.id());
        if (!"ORDINARY".equals(current.mode()) || !"TODO".equals(current.status())
                || current.ownerId() != null || repo.submissionCount(current.id()) > 0) {
            throw ApiException.validation("只有无负责人、无提交的普通TODO任务可以投骰");
        }
        if (current.revision() != request.expectedTaskRevision()) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        if (repo.projectArchived(current.projectId())) throw new ApiException(ApiCode.PROJECT_READ_ONLY);
        if (repo.hasSession(current.id())) throw new ApiException(ApiCode.RESOURCE_CHANGED, "任务已有骰子会话");
        List<String> participantIds = new ArrayList<>(new LinkedHashSet<>(request.participantIds()));
        if (participantIds.size() < 2 || participantIds.size() > 20) {
            throw ApiException.validation("参与名单必须为2～20名去重成员");
        }
        for (String userId : participantIds) {
            if (!repo.member(current.projectId(), userId)) {
                throw ApiException.validation("参与人必须是当前项目有效成员");
            }
        }
        validateRule(request);
        Instant now = clock.instant();
        Instant invite = request.inviteEndsAt() == null ? now.plus(INVITE_TTL) : request.inviteEndsAt();
        Instant roll = request.rollEndsAt() == null ? now.plus(ROLL_TTL) : request.rollEndsAt();
        if (!invite.isAfter(now) || !roll.isAfter(invite)) {
            throw ApiException.validation("邀请截止必须晚于现在，投骰截止必须晚于邀请截止");
        }
        String id = UUID.randomUUID().toString();
        repo.insertSession(id, current.projectId(), current.id(), principal.userId(), request.rule(),
                request.targetValue(), current.revision(), 1, invite, roll, now);
        for (String userId : participantIds) {
            repo.insertParticipant(UUID.randomUUID().toString(), id, userId, now);
        }
        audit.record(principal.userId(), "DICE_CREATE", "DICE_SESSION", id, null, 1, null);
        return view(repo.session(id).orElseThrow(), principal);
    }

    private void validateRule(DiceRequests.CreateSessionRequest request) {
        if ("TARGET".equals(request.rule())) {
            if (request.targetValue() == null || request.targetValue() < 1 || request.targetValue() > 6) {
                throw ApiException.validation("TARGET 必须提供1～6的targetValue");
            }
        } else if (request.targetValue() != null) {
            throw ApiException.validation("MAX/MIN 的 targetValue 必须为null");
        }
        if (request.maxRounds() == null || request.maxRounds() != 3) {
            throw ApiException.validation("maxRounds 固定为3");
        }
    }

    @Transactional
    public DiceViews.Session session(AuthenticatedUser principal, String sessionId) {
        policy.requireActiveBusinessUser(principal);
        SessionRow session = repo.lockSession(sessionId).orElseThrow(ApiException::notFound);
        if (!repo.member(session.projectId(), principal.userId())) throw new ApiException(ApiCode.FORBIDDEN);
        expire(session);
        invalidateIfTaskChanged(session);
        return view(repo.session(sessionId).orElseThrow(), principal);
    }

    public DiceViews.Session participate(AuthenticatedUser principal, String sessionId,
                                        DiceRequests.ParticipationRequest request,
                                        String key, String path) {
        policy.requireActiveBusinessUser(principal);
        return idempotency.execute(request(principal, "POST", path, key, request),
                DiceViews.Session.class,
                () -> policy.requireActiveBusinessUser(principal),
                () -> participateOnce(principal, sessionId, request));
    }

    private DiceViews.Session participateOnce(AuthenticatedUser principal, String sessionId,
                                             DiceRequests.ParticipationRequest request) {
        SessionRow session = repo.lockSession(sessionId).orElseThrow(ApiException::notFound);
        if (!repo.member(session.projectId(), principal.userId())) throw new ApiException(ApiCode.FORBIDDEN);
        ParticipantRow participant = repo.participant(sessionId, principal.userId())
                .orElseThrow(() -> new ApiException(ApiCode.FORBIDDEN));
        if (!"INVITING".equals(session.status())) throw new ApiException(ApiCode.RESOURCE_CHANGED);
        if (session.revision() != request.expectedRevision()) throw new ApiException(ApiCode.RESOURCE_CHANGED);
        if (!session.inviteEndsAt().isAfter(clock.instant())) {
            repo.transition(sessionId, session.revision(), "EXPIRED", "EXPIRED", "邀请已过期", clock.instant());
            throw new ApiException(ApiCode.RESOURCE_CHANGED, "邀请已过期");
        }
        String decision = "ACCEPT".equals(request.decision()) ? "ACCEPTED" : "DECLINED";
        if (repo.decide(sessionId, principal.userId(), decision, clock.instant()) != 1) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        if ("DECLINE".equals(request.decision())) {
            repo.transition(sessionId, session.revision(), "DECLINED", "DECLINED", null, clock.instant());
            return view(repo.session(sessionId).orElseThrow(), principal);
        }
        if (repo.acceptedCount(sessionId) == repo.participantCount(sessionId)) {
            DiceRepository.MarkTaskRow task = requireTask(session.taskId());
            if (task.ownerId() != null || task.revision() != session.taskRevision()
                    || !task.projectOwner().equals(requireProjectOwner(session.projectId()))) {
                repo.transition(sessionId, session.revision(), "INVALIDATED", "INVALIDATED",
                        "任务或成员来源已变化", clock.instant());
                return view(repo.session(sessionId).orElseThrow(), principal);
            }
            List<String> accepted = repo.participants(sessionId).stream()
                    .filter(p -> "ACCEPTED".equals(p.decision())).map(ParticipantRow::userId).toList();
            repo.setActiveInRound(sessionId, accepted);
            if (repo.freeze(sessionId, session.revision(), clock.instant()) != 1) {
                throw new ApiException(ApiCode.RESOURCE_CHANGED);
            }
            repo.insertRound(UUID.randomUUID().toString(), sessionId, 1, write(accepted), false,
                    clock.instant());
        }
        return view(repo.session(sessionId).orElseThrow(), principal);
    }

    public DiceViews.Session roll(AuthenticatedUser principal, String sessionId,
                                  DiceRequests.RollRequest request, String key, String path) {
        policy.requireActiveBusinessUser(principal);
        return idempotency.execute(request(principal, "POST", path, key, request),
                DiceViews.Session.class,
                () -> policy.requireActiveBusinessUser(principal),
                () -> rollOnce(principal, sessionId, request));
    }

    private DiceViews.Session rollOnce(AuthenticatedUser principal, String sessionId,
                                       DiceRequests.RollRequest request) {
        SessionRow session = repo.lockSession(sessionId).orElseThrow(ApiException::notFound);
        if (!repo.member(session.projectId(), principal.userId())) throw new ApiException(ApiCode.FORBIDDEN);
        ParticipantRow participant = repo.participant(sessionId, principal.userId())
                .orElseThrow(() -> new ApiException(ApiCode.FORBIDDEN));
        if (!"ACCEPTED".equals(participant.decision()) || !participant.activeInRound()) {
            throw new ApiException(ApiCode.FORBIDDEN);
        }
        if (!"ROLLING".equals(session.status()) && !"TIE_ROUND".equals(session.status())) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        if (session.roundNo() != request.roundNo()) throw new ApiException(ApiCode.RESOURCE_CHANGED);
        if (!session.rollEndsAt().isAfter(clock.instant())) {
            repo.transition(sessionId, session.revision(), "EXPIRED", "EXPIRED", "投骰已截止", clock.instant());
            throw new ApiException(ApiCode.RESOURCE_CHANGED, "投骰已截止");
        }
        RollRow existing = repo.roll(sessionId, request.roundNo(), principal.userId()).orElse(null);
        if (existing != null) {
            return view(repo.session(sessionId).orElseThrow(), principal);
        }
        if (session.revision() != request.expectedRevision()) throw new ApiException(ApiCode.RESOURCE_CHANGED);
        int value = random.nextInt(6) + 1;
        repo.insertRoll(UUID.randomUUID().toString(), sessionId, request.roundNo(),
                principal.userId(), value, clock.instant());
        if (repo.rollCount(sessionId, request.roundNo()) == repo.participants(sessionId).stream()
                .filter(ParticipantRow::activeInRound).count()) {
            revealRound(session, request.roundNo());
        }
        return view(repo.session(sessionId).orElseThrow(), principal);
    }

    private void revealRound(SessionRow session, int roundNo) {
        List<ParticipantRow> candidates = repo.participants(session.id()).stream()
                .filter(ParticipantRow::activeInRound).toList();
        List<RollRow> rolls = repo.rolls(session.id(), roundNo);
        Map<String, Integer> values = new java.util.HashMap<>();
        rolls.forEach(roll -> values.put(roll.userId(), roll.value()));
        List<String> winners = new ArrayList<>();
        if ("TARGET".equals(session.rule())) {
            for (ParticipantRow participant : candidates) {
                if (values.getOrDefault(participant.userId(), -1).equals(session.targetValue())) {
                    winners.add(participant.userId());
                }
            }
            if (winners.isEmpty()) {
                winners = candidates.stream().map(ParticipantRow::userId).toList();
            }
        } else {
            int extreme = "MAX".equals(session.rule())
                    ? candidates.stream().mapToInt(p -> values.getOrDefault(p.userId(), 0)).max().orElse(0)
                    : candidates.stream().mapToInt(p -> values.getOrDefault(p.userId(), 7)).min().orElse(7);
            for (ParticipantRow participant : candidates) {
                if (values.getOrDefault(participant.userId(), -1) == extreme) {
                    winners.add(participant.userId());
                }
            }
        }
        String winner = winners.size() == 1 ? winners.getFirst() : null;
        repo.revealRound(session.id(), roundNo, winner);
        if (winner != null) {
            repo.advance(session.id(), session.revision(), roundNo, "AWAITING_CLAIM",
                    winner, winner, clock.instant());
            return;
        }
        if (roundNo >= 3) {
            repo.advance(session.id(), session.revision(), roundNo, "UNRESOLVED", null, null,
                    clock.instant());
            return;
        }
        repo.setActiveInRound(session.id(), winners);
        repo.advance(session.id(), session.revision(), roundNo + 1, "TIE_ROUND", null, null,
                clock.instant());
        repo.insertRound(UUID.randomUUID().toString(), session.id(), roundNo + 1,
                write(winners), false, clock.instant());
    }

    public DiceViews.Session terminate(AuthenticatedUser principal, String sessionId,
                                       DiceRequests.TerminationRequest request,
                                       String key, String path) {
        policy.requireActiveBusinessUser(principal);
        return idempotency.execute(request(principal, "POST", path, key, request),
                DiceViews.Session.class,
                () -> policy.requireActiveBusinessUser(principal),
                () -> {
                    SessionRow session = repo.lockSession(sessionId).orElseThrow(ApiException::notFound);
                    if (!repo.leader(session.projectId(), principal.userId())) {
                        throw new ApiException(ApiCode.FORBIDDEN);
                    }
                    if (session.revision() != request.expectedRevision()) {
                        throw new ApiException(ApiCode.RESOURCE_CHANGED);
                    }
                    if (repo.transition(sessionId, request.expectedRevision(), "TERMINATED", null,
                            request.reason(), clock.instant()) != 1) {
                        throw new ApiException(ApiCode.RESOURCE_CHANGED);
                    }
                    return view(repo.session(sessionId).orElseThrow(), principal);
                });
    }

    public DiceViews.Outcome outcome(AuthenticatedUser principal, String sessionId,
                                     DiceRequests.OutcomeDecisionRequest request,
                                     String key, String path) {
        policy.requireActiveBusinessUser(principal);
        return idempotency.execute(request(principal, "POST", path, key, request),
                DiceViews.Outcome.class,
                () -> policy.requireActiveBusinessUser(principal),
                () -> {
                    SessionRow session = repo.lockSession(sessionId).orElseThrow(ApiException::notFound);
                    if (!"AWAITING_CLAIM".equals(session.status())
                            || !principal.userId().equals(session.proposedOwnerId())) {
                        throw new ApiException(ApiCode.FORBIDDEN);
                    }
                    if (session.revision() != request.expectedRevision()) {
                        throw new ApiException(ApiCode.RESOURCE_CHANGED);
                    }
                    String status = "ACCEPT".equals(request.decision()) ? "AWAITING_CLAIM" : "DECLINED";
                    String claim = "ACCEPT".equals(request.decision()) ? "AWAITING" : "DECLINED";
                    if (repo.setOutcome(sessionId, request.expectedRevision(), status,
                            session.proposedOwnerId(), request.decision(), claim, clock.instant()) != 1) {
                        throw new ApiException(ApiCode.RESOURCE_CHANGED);
                    }
                    SessionRow updated = repo.session(sessionId).orElseThrow();
                    return new DiceViews.Outcome(updated.id(), updated.revision(), updated.status(),
                            updated.proposedOwnerId(), updated.outcomeDecision(), updated.claimStatus(),
                            updated.taskId(), updated.taskRevision());
                });
    }

    private void expire(SessionRow session) {
        if (("INVITING".equals(session.status()) || "ROLLING".equals(session.status())
                || "TIE_ROUND".equals(session.status()) || "AWAITING_CLAIM".equals(session.status()))
                && !session.rollEndsAt().isAfter(clock.instant())) {
            repo.expireIfNeeded(session.id(), clock.instant());
        }
    }

    private void invalidateIfTaskChanged(SessionRow session) {
        if (!"INVITING".equals(session.status()) && !"ROLLING".equals(session.status())
                && !"TIE_ROUND".equals(session.status()) && !"AWAITING_CLAIM".equals(session.status())) {
            return;
        }
        DiceRepository.MarkTaskRow task = requireTask(session.taskId());
        if (task.ownerId() != null || task.revision() != session.taskRevision()) {
            repo.transition(session.id(), session.revision(), "INVALIDATED", "INVALIDATED",
                    "任务来源已变化", clock.instant());
        }
    }

    private DiceViews.Session view(SessionRow session, AuthenticatedUser principal) {
        List<ParticipantRow> participants = repo.participants(session.id());
        List<DiceViews.Participant> participantViews = new ArrayList<>();
        Integer myRoll = null;
        for (ParticipantRow participant : participants) {
            RollRow roll = repo.roll(session.id(), session.roundNo(), participant.userId()).orElse(null);
            boolean hasRolled = roll != null;
            if (participant.userId().equals(principal.userId()) && roll != null) {
                myRoll = roll.value();
            }
            participantViews.add(new DiceViews.Participant(participant.userId(),
                    participant.decision(), participant.activeInRound(), hasRolled,
                    participant.userId().equals(principal.userId()) && roll != null ? roll.value() : null));
        }
        List<DiceViews.Round> rounds = new ArrayList<>();
        for (RoundRow round : repo.rounds(session.id())) {
            if (!round.revealed()) continue;
            List<DiceViews.Roll> rolls = repo.rolls(session.id(), round.roundNo()).stream()
                    .map(r -> new DiceViews.Roll(r.id(), r.userId(), r.value(), r.createdAt())).toList();
            rounds.add(new DiceViews.Round(round.roundNo(), candidateIds(round.candidateIds()),
                    rolls, round.winnerId()));
        }
        return new DiceViews.Session(session.id(), session.projectId(), session.taskId(),
                session.taskRevision(), session.revision(), session.rule(), session.targetValue(),
                session.maxRounds(), session.roundNo(), session.status(), session.inviteEndsAt(),
                session.rollEndsAt(), participantViews, rounds, session.proposedOwnerId(),
                session.outcomeDecision(), session.claimStatus(), session.terminationReason(),
                session.createdAt(), myRoll);
    }

    private DiceRepository.MarkTaskRow requireTask(String taskId) {
        DiceRepository.MarkTaskRow task = repo.task(taskId);
        if (task == null) throw ApiException.notFound();
        return task;
    }

    private String requireProjectOwner(String projectId) {
        return repo.projectOwner(projectId);
    }

    private List<String> candidateIds(String json) {
        try {
            return mapper.readValue(json, STRING_LIST);
        } catch (Exception ex) {
            return List.of();
        }
    }

    private String write(List<String> ids) {
        try {
            return mapper.writeValueAsString(ids);
        } catch (Exception ex) {
            throw new IllegalStateException("无法序列化骰子候选", ex);
        }
    }

    private IdempotencyRequest request(AuthenticatedUser principal, String method, String path,
                                       String key, Object body) {
        return new IdempotencyRequest(principal, method, path, key, body, List.of());
    }
}
