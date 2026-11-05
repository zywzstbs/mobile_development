package cn.gulupai.server.merge;

import cn.gulupai.server.common.api.ApiCode;
import cn.gulupai.server.common.api.ApiException;
import cn.gulupai.server.common.idempotency.IdempotencyRequest;
import cn.gulupai.server.common.idempotency.IdempotencyService;
import cn.gulupai.server.common.security.AuthenticatedUser;
import cn.gulupai.server.common.security.CurrentUserPolicy;
import cn.gulupai.server.course.persistence.AssignmentRow;
import cn.gulupai.server.course.persistence.SquareRepository;
import cn.gulupai.server.course.persistence.SquareRow;
import cn.gulupai.server.merge.MergeRepository.BallotRow;
import cn.gulupai.server.merge.MergeRepository.ProposalRow;
import cn.gulupai.server.teaching.persistence.TeachingRepository;
import cn.gulupai.server.team.persistence.TeamRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 同一真实教学班、同一当前教师下的广场合并。作业和项目编号保持不变。
 */
@Service
public class MergeService {

    private static final Duration VOTING = Duration.ofHours(72);
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, List<String>>> ELECTORATES = new TypeReference<>() {
    };
    private static final TypeReference<List<MergeViews.LinkView>> LINKS = new TypeReference<>() {
    };
    private static final TypeReference<List<Map<String, Object>>> MAPS = new TypeReference<>() {
    };

    private final MergeRepository merges;
    private final SquareRepository squares;
    private final TeachingRepository teaching;
    private final TeamRepository teams;
    private final CurrentUserPolicy policy;
    private final IdempotencyService idempotency;
    private final ObjectMapper json;
    private final Clock clock;

    public MergeService(MergeRepository merges, SquareRepository squares, TeachingRepository teaching,
                        TeamRepository teams, CurrentUserPolicy policy, IdempotencyService idempotency,
                        ObjectMapper json, Clock clock) {
        this.merges = merges;
        this.squares = squares;
        this.teaching = teaching;
        this.teams = teams;
        this.policy = policy;
        this.idempotency = idempotency;
        this.json = json;
        this.clock = clock;
    }

    public MergeViews.ProposalView proposal(AuthenticatedUser principal, String id) {
        policy.requireActiveBusinessUser(principal);
        ProposalRow row = merges.lock(id).orElseThrow(ApiException::notFound);
        settle(row);
        requireReader(principal, row);
        return view(merges.find(id).orElseThrow(), principal.userId());
    }

    public List<MergeViews.ProposalView> forSquare(AuthenticatedUser principal, String squareId) {
        policy.requireActiveBusinessUser(principal);
        SquareRow square = squares.findById(squareId).orElseThrow(ApiException::notFound);
        requireParticipant(principal, square);
        return merges.forSquare(squareId).stream().map(row -> view(row, principal.userId())).toList();
    }

    public MergeViews.ProposalView create(AuthenticatedUser principal, MergeRequests.Create request, String key, String path) {
        policy.requireActiveBusinessUser(principal);
        return write(principal, "POST", path, key, request, MergeViews.ProposalView.class,
                () -> createOnce(principal, request));
    }

    public MergeViews.ProposalView cancel(AuthenticatedUser principal, String id, MergeRequests.Cancel request,
                                         String key, String path) {
        policy.requireActiveBusinessUser(principal);
        return write(principal, "POST", path, key, request, MergeViews.ProposalView.class,
                () -> cancelOnce(principal, id, request));
    }

    public MergeViews.ProposalView factCheck(AuthenticatedUser principal, String id, MergeRequests.FactCheck request,
                                            String key, String path) {
        policy.requireActiveBusinessUser(principal);
        return write(principal, "POST", path, key, request, MergeViews.ProposalView.class,
                () -> factCheckOnce(principal, id, request));
    }

    public MergeViews.ProposalView execute(AuthenticatedUser principal, String id, MergeRequests.Execute request,
                                          String key, String path) {
        policy.requireActiveBusinessUser(principal);
        return write(principal, "POST", path, key, request, MergeViews.ProposalView.class,
                () -> executeOnce(principal, id, request));
    }

    public MergeViews.ProposalView ballot(AuthenticatedUser principal, String id, MergeRequests.Ballot request,
                                         String key, String path) {
        policy.requireStudent(principal);
        return write(principal, "PUT", path, key, request, MergeViews.ProposalView.class,
                () -> ballotOnce(principal, id, request));
    }

    private MergeViews.ProposalView createOnce(AuthenticatedUser principal, MergeRequests.Create request) {
        if (request.sourceSquareId().equals(request.targetSquareId())) {
            throw ApiException.validation("不能把广场合并到自己");
        }
        SquareRow first = squares.findByIdForUpdate(min(request.sourceSquareId(), request.targetSquareId()))
                .orElseThrow(ApiException::notFound);
        SquareRow second = squares.findByIdForUpdate(max(request.sourceSquareId(), request.targetSquareId()))
                .orElseThrow(ApiException::notFound);
        SquareRow source = request.sourceSquareId().equals(first.id()) ? first : second;
        SquareRow target = request.targetSquareId().equals(first.id()) ? first : second;
        if (source.revision() != request.expectedSourceRevision() || target.revision() != request.expectedTargetRevision()) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        requireSameClass(source, target);
        requireParticipant(principal, source, target);
        if (!"ACTIVE".equals(source.status()) || !"ACTIVE".equals(target.status())) {
            throw ApiException.validation("只能合并两门仍在使用的课程广场");
        }
        if (merges.openInvolving(source.id(), target.id())) {
            throw ApiException.validation("有广场已经在另一项未结束的合并里");
        }
        List<String> sourceElectorate = merges.activeStudents(source.id());
        List<String> targetElectorate = merges.activeStudents(target.id());
        if (sourceElectorate.isEmpty() || targetElectorate.isEmpty()) {
            throw ApiException.validation("两侧都要有当前学生才能合并");
        }
        Instant now = clock.instant();
        String id = UUID.randomUUID().toString();
        List<MergeViews.LinkView> evidence = evidence(request.evidenceRefs());
        merges.insert(new ProposalRow(id, source.id(), target.id(), principal.userId(),
                request.reason() == null ? "" : request.reason().strip(), write(evidence), "VOTING",
                write(Map.of("source", sourceElectorate, "target", targetElectorate)),
                write(Map.of("source", source.authorizationEpoch(), "target", target.authorizationEpoch())),
                "{}", now.plus(VOTING), 1, now), now);
        return view(merges.find(id).orElseThrow(), principal.userId());
    }

    private MergeViews.ProposalView cancelOnce(AuthenticatedUser principal, String id, MergeRequests.Cancel request) {
        ProposalRow row = locked(id, request.expectedRevision());
        if (!principal.userId().equals(row.initiatorId()) && !teacher(principal, row)) {
            throw new ApiException(ApiCode.FORBIDDEN);
        }
        if (!List.of("VOTING", "WAIT_FACT", "READY", "RETURNED", "QUALIFICATION_REVIEW").contains(row.status())) {
            throw ApiException.validation("已经结束的合并不能取消");
        }
        save(row, "CANCELLED", row.factCheckJson());
        return view(merges.find(id).orElseThrow(), principal.userId());
    }

    private MergeViews.ProposalView ballotOnce(AuthenticatedUser principal, String id, MergeRequests.Ballot request) {
        ProposalRow row = merges.lock(id).orElseThrow(ApiException::notFound);
        settle(row);
        row = merges.lock(id).orElseThrow();
        if (!"VOTING".equals(row.status())) {
            throw new ApiException(ApiCode.SOURCE_CHANGED, "当前不能投票");
        }
        Map<String, List<String>> electorates = read(row.electoratesJson(), ELECTORATES);
        String side = request.squareId().equals(row.sourceSquareId()) ? "source"
                : request.squareId().equals(row.targetSquareId()) ? "target" : null;
        if (side == null || !electorates.get(side).contains(principal.userId())) {
            throw new ApiException(ApiCode.FORBIDDEN);
        }
        if (!merges.activeStudents(request.squareId()).contains(principal.userId())) {
            disqualify(row);
            throw new ApiException(ApiCode.SOURCE_CHANGED, "选民资格已变化，原表决作废");
        }
        var existing = merges.ballot(id, request.squareId(), principal.userId());
        if (existing.isEmpty()) {
            if (request.expectedRevision() != 0) {
                throw new ApiException(ApiCode.RESOURCE_CHANGED);
            }
            merges.insertBallot(id, request.squareId(), principal.userId(), request.choice(), clock.instant());
        } else if (existing.get().revision() != request.expectedRevision()) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        } else if (!existing.get().decision().equals(request.choice())) {
            merges.updateBallot(id, request.squareId(), principal.userId(), request.choice(),
                    existing.get().revision(), clock.instant());
        }
        settle(merges.lock(id).orElseThrow());
        return view(merges.find(id).orElseThrow(), principal.userId());
    }

    private MergeViews.ProposalView factCheckOnce(AuthenticatedUser principal, String id, MergeRequests.FactCheck request) {
        ProposalRow row = locked(id, request.expectedRevision());
        if (!teacher(principal, row)) {
            throw new ApiException(ApiCode.FORBIDDEN);
        }
        settle(row);
        row = merges.lock(id).orElseThrow();
        if (!"WAIT_FACT".equals(row.status())) {
            throw ApiException.validation("两侧都严格过半之后才能核对作业");
        }
        List<AssignmentRow> assignments = squares.listAssignments(row.sourceSquareId());
        if ("RETURN".equals(request.decision())) {
            save(row, "RETURNED", write(Map.of("decision", "RETURN", "reason", request.reason())));
            return view(merges.find(id).orElseThrow(), principal.userId());
        }
        if (request.assignmentMappings().size() != assignments.size()) {
            throw ApiException.validation("必须逐项核对本广场的全部作业");
        }
        List<String> seen = new ArrayList<>();
        for (MergeRequests.Mapping mapping : request.assignmentMappings()) {
            if (!Boolean.TRUE.equals(mapping.keepSeparate())) {
                throw ApiException.validation("作业必须分开保留，不能合并成绩");
            }
            if (assignments.stream().noneMatch(item -> item.id().equals(mapping.fromAssignmentId()))
                    || !seen.add(mapping.fromAssignmentId()) || seen.size() != seen.stream().distinct().count()) {
                throw ApiException.validation("作业映射必须覆盖来源作业且不能重复");
            }
        }
        save(row, "READY", write(request.assignmentMappings()));
        return view(merges.find(id).orElseThrow(), principal.userId());
    }

    private MergeViews.ProposalView executeOnce(AuthenticatedUser principal, String id, MergeRequests.Execute request) {
        ProposalRow row = locked(id, request.expectedRevision());
        if (!teacher(principal, row)) {
            throw new ApiException(ApiCode.FORBIDDEN);
        }
        if (!"READY".equals(row.status())) {
            throw ApiException.validation("事实核对完成前不能执行合并");
        }
        if (!bothPassed(row)) {
            throw ApiException.validation("有一侧没有严格过半，不能执行");
        }
        SquareRow lockedMin = squares.findByIdForUpdate(min(row.sourceSquareId(), row.targetSquareId())).orElseThrow();
        SquareRow lockedMax = squares.findByIdForUpdate(max(row.sourceSquareId(), row.targetSquareId())).orElseThrow();
        SquareRow source = row.sourceSquareId().equals(lockedMin.id()) ? lockedMin : lockedMax;
        SquareRow target = row.targetSquareId().equals(lockedMin.id()) ? lockedMin : lockedMax;
        if (!"ACTIVE".equals(source.status()) || !"ACTIVE".equals(target.status())) {
            throw new ApiException(ApiCode.SOURCE_CHANGED);
        }
        List<String> mapped = read(row.factCheckJson(), MAPS).stream()
                .map(item -> String.valueOf(item.get("fromAssignmentId"))).sorted().toList();
        List<String> current = squares.listAssignments(row.sourceSquareId()).stream().map(AssignmentRow::id).sorted().toList();
        if (!mapped.equals(current)) {
            throw new ApiException(ApiCode.SOURCE_CHANGED, "来源作业已经变化，请重新核对");
        }
        Instant now = clock.instant();
        merges.moveAssignments(row.sourceSquareId(), row.targetSquareId());
        merges.moveProjects(row.sourceSquareId(), row.targetSquareId());
        merges.moveMemberships(row.sourceSquareId(), row.targetSquareId(), now);
        merges.revokeCodes(row.sourceSquareId(), now);
        merges.markMerged(row.sourceSquareId(), row.targetSquareId(), now);
        save(row, "COMPLETED", row.factCheckJson());
        return view(merges.find(id).orElseThrow(), principal.userId());
    }

    private void settle(ProposalRow row) {
        if (!"VOTING".equals(row.status())) {
            return;
        }
        Map<String, List<String>> electorates = read(row.electoratesJson(), ELECTORATES);
        if (!stillQualified(row.sourceSquareId(), electorates.get("source"))
                || !stillQualified(row.targetSquareId(), electorates.get("target"))) {
            disqualify(row);
            return;
        }
        if (bothPassed(row)) {
            save(row, "WAIT_FACT", row.factCheckJson());
        } else if (!clock.instant().isBefore(row.votingEndsAt())) {
            save(row, "REJECTED", row.factCheckJson());
        }
    }

    private boolean stillQualified(String squareId, List<String> electorate) {
        List<String> current = merges.activeStudents(squareId);
        return electorate.stream().allMatch(current::contains);
    }

    private boolean bothPassed(ProposalRow row) {
        Map<String, List<String>> electorates = read(row.electoratesJson(), ELECTORATES);
        List<BallotRow> ballots = merges.ballots(row.id());
        return passed(electorates.get("source"), ballots, row.sourceSquareId())
                && passed(electorates.get("target"), ballots, row.targetSquareId());
    }

    private boolean passed(List<String> electorate, List<BallotRow> ballots, String squareId) {
        long agree = ballots.stream().filter(item -> squareId.equals(item.squareId()) && "AGREE".equals(item.decision())).count();
        return agree * 2 > electorate.size();
    }

    private void disqualify(ProposalRow row) {
        merges.deleteBallots(row.id());
        save(row, "QUALIFICATION_REVIEW", row.factCheckJson());
    }

    private void requireSameClass(SquareRow source, SquareRow target) {
        if (source.verifiedCourseId() == null || !source.verifiedCourseId().equals(target.verifiedCourseId())) {
            throw ApiException.validation("只能合并管理员核验为同一真实教学班的广场");
        }
        if (!source.schoolId().equals(target.schoolId()) || !source.termId().equals(target.termId())) {
            throw ApiException.validation("只能合并同一学校、同一学期的广场");
        }
        var sourceTeacher = teaching.activeAssignment(source.id()).orElse(null);
        var targetTeacher = teaching.activeAssignment(target.id()).orElse(null);
        if (sourceTeacher == null || targetTeacher == null
                || !sourceTeacher.userId().equals(targetTeacher.userId())) {
            throw ApiException.validation("两侧必须是同一位当前教师");
        }
    }

    private void requireParticipant(AuthenticatedUser principal, SquareRow... squaresToCheck) {
        for (SquareRow square : squaresToCheck) {
            if (teaching.isActiveTeacher(square.id(), principal.userId())
                    || merges.activeStudents(square.id()).contains(principal.userId())) {
                return;
            }
        }
        throw new ApiException(ApiCode.FORBIDDEN, "只有两侧的当前学生或当前教师可以提出合并");
    }

    private void requireReader(AuthenticatedUser principal, ProposalRow row) {
        SquareRow source = squares.findById(row.sourceSquareId()).orElseThrow(ApiException::notFound);
        SquareRow target = squares.findById(row.targetSquareId()).orElseThrow(ApiException::notFound);
        requireParticipant(principal, source, target);
    }

    private boolean teacher(AuthenticatedUser principal, ProposalRow row) {
        return teaching.isActiveTeacher(row.sourceSquareId(), principal.userId())
                && teaching.isActiveTeacher(row.targetSquareId(), principal.userId());
    }

    private ProposalRow locked(String id, int revision) {
        ProposalRow row = merges.lock(id).orElseThrow(ApiException::notFound);
        if (row.revision() != revision) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        return row;
    }

    private void save(ProposalRow row, String status, String factCheck) {
        if (merges.update(new ProposalRow(row.id(), row.sourceSquareId(), row.targetSquareId(), row.initiatorId(),
                row.reason(), row.evidenceJson(), status, row.electoratesJson(), row.epochsJson(), factCheck,
                row.votingEndsAt(), row.revision(), row.createdAt()), clock.instant()) != 1) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
    }

    private MergeViews.ProposalView view(ProposalRow row, String userId) {
        Map<String, List<String>> electorates = read(row.electoratesJson(), ELECTORATES);
        List<BallotRow> ballots = merges.ballots(row.id());
        List<MergeViews.SideView> sides = List.of(
                side(row.sourceSquareId(), electorates.get("source"), ballots, userId, row.status()),
                side(row.targetSquareId(), electorates.get("target"), ballots, userId, row.status()));
        List<MergeViews.AssignmentView> assignments = squares.listAssignments(row.sourceSquareId()).stream()
                .map(item -> new MergeViews.AssignmentView(item.id(), item.squareId(), item.name(), item.requirements(),
                        item.origin(), item.confirmationStatus(), item.hardDueAt(), item.minMembers(), item.maxMembers(),
                        item.gradingEnabled(), item.revision(), read(item.sourceReferencesJson(), LINKS)))
                .toList();
        String fact = row.factCheckJson() == null ? "" : row.factCheckJson().trim();
        List<Object> mappings = fact.startsWith("[") ? new ArrayList<>(read(fact, MAPS)) : List.of();
        boolean ready = "READY".equals(row.status());
        boolean canCheck = "WAIT_FACT".equals(row.status());
        return new MergeViews.ProposalView(row.id(), row.sourceSquareId(), row.targetSquareId(), row.reason(),
                read(row.evidenceJson(), LINKS), row.status(), sides, assignments, mappings, canCheck, ready, row.revision());
    }

    private MergeViews.SideView side(String squareId, List<String> electorate, List<BallotRow> ballots,
                                    String userId, String status) {
        int eligible = electorate == null ? 0 : electorate.size();
        int agree = count(ballots, squareId, "AGREE");
        int disagree = count(ballots, squareId, "DISAGREE");
        int abstain = count(ballots, squareId, "ABSTAIN");
        int required = eligible / 2 + 1;
        BallotRow mine = ballots.stream().filter(item -> squareId.equals(item.squareId()) && userId.equals(item.userId()))
                .findFirst().orElse(null);
        Object ballot = mine == null ? Map.of() : new MergeViews.BallotView(
                mine.decision(), mine.revision(), mine.squareId(), mine.at());
        boolean canVote = "VOTING".equals(status)
                && electorate != null
                && electorate.contains(userId)
                && merges.activeStudents(squareId).contains(userId);
        boolean passed = eligible > 0 && agree >= required;
        return new MergeViews.SideView(squareId, eligible, agree, disagree, abstain,
                Math.max(0, eligible - agree - disagree - abstain), passed, required, ballot, canVote);
    }

    private int count(List<BallotRow> ballots, String squareId, String decision) {
        return (int) ballots.stream().filter(item -> squareId.equals(item.squareId())
                && decision.equals(item.decision())).count();
    }

    private List<MergeViews.LinkView> evidence(List<MergeRequests.LinkRef> refs) {
        if (refs == null) {
            return List.of();
        }
        return refs.stream().map(item -> new MergeViews.LinkView(item.kind(), item.url(), item.title())).toList();
    }

    private <T> T write(AuthenticatedUser principal, String method, String path, String key, Object body, Class<T> type,
                        java.util.function.Supplier<T> action) {
        return idempotency.execute(new IdempotencyRequest(principal, method, path, key, body, List.of()), type,
                () -> policy.requireActiveBusinessUser(principal), action);
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private <T> T read(String value, TypeReference<T> type) {
        try {
            return json.readValue(value == null || value.isBlank() ? "[]" : value, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String min(String left, String right) {
        return left.compareTo(right) <= 0 ? left : right;
    }

    private static String max(String left, String right) {
        return left.compareTo(right) > 0 ? left : right;
    }
}
