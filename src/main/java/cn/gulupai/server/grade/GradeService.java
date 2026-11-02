package cn.gulupai.server.grade;

import cn.gulupai.server.common.api.ApiCode;
import cn.gulupai.server.common.api.ApiException;
import cn.gulupai.server.common.idempotency.IdempotencyRequest;
import cn.gulupai.server.common.idempotency.IdempotencyService;
import cn.gulupai.server.common.security.AuthenticatedUser;
import cn.gulupai.server.common.security.CurrentUserPolicy;
import cn.gulupai.server.common.security.Hashing;
import cn.gulupai.server.common.validation.DecimalStrings;
import cn.gulupai.server.course.persistence.SquareRepository;
import cn.gulupai.server.course.persistence.PreviewTokenRow;
import cn.gulupai.server.grade.GradeRepository.AcceptanceFact;
import cn.gulupai.server.grade.GradeRepository.AppealFact;
import cn.gulupai.server.grade.GradeRepository.AssignmentHead;
import cn.gulupai.server.grade.GradeRepository.CorrectionFact;
import cn.gulupai.server.grade.GradeRepository.DraftFact;
import cn.gulupai.server.grade.GradeRepository.HandoverFact;
import cn.gulupai.server.grade.GradeRepository.PendingSubmissionFact;
import cn.gulupai.server.grade.GradeRepository.RuleFact;
import cn.gulupai.server.grade.GradeRepository.SettlementFact;
import cn.gulupai.server.grade.GradeRepository.ShareFact;
import cn.gulupai.server.grade.GradeRepository.SnapshotFact;
import cn.gulupai.server.grade.GradeRepository.SummaryFact;
import cn.gulupai.server.teaching.persistence.TeachingRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 正式成绩只来自已签份额、验收、本人确认和最新教学更正。普通任务不计点。
 */
@Service
public class GradeService {

    private static final Duration PREVIEW = Duration.ofMinutes(10);
    private static final TypeReference<List<GradeViews.LinkView>> LINKS = new TypeReference<>() {
    };
    private static final TypeReference<List<GradeViews.SourceView>> SOURCES = new TypeReference<>() {
    };
    private static final TypeReference<List<GradeViews.ResponseView>> RESPONSES = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };
    private static final TypeReference<List<Map<String, Object>>> MAPS = new TypeReference<>() {
    };

    private final GradeRepository grades;
    private final SquareRepository squares;
    private final TeachingRepository teaching;
    private final CurrentUserPolicy policy;
    private final IdempotencyService idempotency;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public GradeService(GradeRepository grades, SquareRepository squares, TeachingRepository teaching,
                        CurrentUserPolicy policy, IdempotencyService idempotency, ObjectMapper objectMapper,
                        Clock clock) {
        this.grades = grades;
        this.squares = squares;
        this.teaching = teaching;
        this.policy = policy;
        this.idempotency = idempotency;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public List<GradeViews.AppealView> appeals(AuthenticatedUser principal, String assignmentId) {
        AssignmentHead assignment = readable(principal, assignmentId);
        requireTeacher(principal, assignment);
        return grades.appealsForAssignment(assignmentId).stream().map(this::appealView).toList();
    }

    public List<GradeViews.CandidateView> gradebook(AuthenticatedUser principal, String assignmentId) {
        AssignmentHead assignment = readable(principal, assignmentId);
        requireTeacher(principal, assignment);
        return grades.gradedStudentIds(assignmentId).stream().map(studentId -> candidate(assignment, studentId)).toList();
    }

    public GradeViews.RuleView rule(AuthenticatedUser principal, String assignmentId) {
        AssignmentHead assignment = readable(principal, assignmentId);
        requireTeacher(principal, assignment);
        return ruleView(grades.latestRule(assignmentId).orElseThrow(ApiException::notFound));
    }

    public List<GradeViews.SnapshotView> history(AuthenticatedUser principal, String assignmentId, String studentId) {
        AssignmentHead assignment = readable(principal, assignmentId);
        requireReader(principal, assignment, studentId);
        return grades.history(assignmentId, studentId).stream().map(this::snapshotView).toList();
    }

    public GradeViews.LedgerView ledger(AuthenticatedUser principal, String assignmentId, String studentId) {
        AssignmentHead assignment = readable(principal, assignmentId);
        requireReader(principal, assignment, studentId);
        return projection(assignment, studentId).view();
    }

    public GradeViews.SummaryView teacherSummary(AuthenticatedUser principal, String assignmentId, String studentId) {
        AssignmentHead assignment = readable(principal, assignmentId);
        requireReader(principal, assignment, studentId);
        SummaryFact row = grades.summaryFor(assignmentId, studentId).orElseThrow(ApiException::notFound);
        return summaryView(row, principal.userId().equals(studentId));
    }

    public GradeViews.AppealView appeal(AuthenticatedUser principal, String appealId) {
        policy.requireActiveBusinessUser(principal);
        AppealFact row = grades.appeal(appealId).orElseThrow(ApiException::notFound);
        requireReader(principal, requireAssignment(row.assignmentId()), row.studentId());
        return appealView(row);
    }

    public GradeViews.SnapshotView snapshot(AuthenticatedUser principal, String snapshotId) {
        policy.requireActiveBusinessUser(principal);
        SnapshotFact row = grades.snapshot(snapshotId).orElseThrow(ApiException::notFound);
        requireReader(principal, requireAssignment(row.assignmentId()), row.studentId());
        return snapshotView(row);
    }

    public List<GradeViews.AppealView> myAppeals(AuthenticatedUser principal) {
        policy.requireActiveBusinessUser(principal);
        return grades.myAppeals(principal.userId()).stream().map(this::appealView).toList();
    }

    public List<GradeViews.SnapshotView> myGrades(AuthenticatedUser principal) {
        policy.requireActiveBusinessUser(principal);
        return grades.myGrades(principal.userId()).stream().map(this::snapshotView).toList();
    }

    public List<GradeViews.SummaryView> mySummaries(AuthenticatedUser principal) {
        policy.requireActiveBusinessUser(principal);
        return grades.mySummaries(principal.userId()).stream()
                .map(row -> summaryView(row, true)).toList();
    }

    public GradeViews.SummaryView summary(AuthenticatedUser principal, String summaryId) {
        policy.requireActiveBusinessUser(principal);
        SummaryFact row = grades.summary(summaryId).orElseThrow(ApiException::notFound);
        requireReader(principal, requireAssignment(row.assignmentId()), row.studentId());
        return summaryView(row, principal.userId().equals(row.studentId()));
    }

    public GradeViews.RulePreviewView previewRule(AuthenticatedUser principal, String assignmentId,
                                                  GradeRequests.RulePreview request, String key, String path) {
        return write(principal, "POST", path, key, request, GradeViews.RulePreviewView.class, () -> {
            AssignmentHead assignment = lock(assignmentId);
            requireTeacher(principal, assignment);
            if (assignment.revision() != request.expectedAssignmentRevision()) {
                throw new ApiException(ApiCode.RESOURCE_CHANGED);
            }
            BigDecimal weight = weight(request.processWeight());
            String description = request.description() == null ? "" : request.description().strip();
            int next = grades.latestRule(assignmentId).map(rule -> rule.version() + 1).orElse(1);
            String token = UUID.randomUUID().toString();
            Instant expires = clock.instant().plus(PREVIEW);
            String payload = write(Map.of(
                    "processWeight", money(weight), "description", description,
                    "assignmentId", assignmentId, "assignmentRevision", assignment.revision(), "version", next));
            String hash = Hashing.sha256Hex(payload);
            squares.insertPreviewToken(UUID.randomUUID().toString(), Hashing.sha256Hex(token), principal.userId(),
                    "GRADE_RULE", hash, hash, payload, expires);
            RuleFact preview = new RuleFact(token, assignmentId, next, weight, description,
                    principal.userId(), clock.instant());
            return new GradeViews.RulePreviewView(token, expires.toString(), assignmentId, ruleView(preview),
                    new GradeViews.ImpactView(grades.affectedStudents(assignmentId)));
        });
    }

    public GradeViews.RuleView confirmRule(AuthenticatedUser principal, String assignmentId,
                                           GradeRequests.ConfirmRule request, String key, String path) {
        return write(principal, "POST", path, key, request, GradeViews.RuleView.class, () -> {
            AssignmentHead assignment = lock(assignmentId);
            requireTeacher(principal, assignment);
            Map<String, Object> payload = tokenPayload(principal, request.previewToken(), "GRADE_RULE");
            if (!assignmentId.equals(payload.get("assignmentId"))
                    || assignment.revision() != ((Number) payload.get("assignmentRevision")).intValue()) {
                throw new ApiException(ApiCode.RESOURCE_CHANGED);
            }
            BigDecimal weight = weight(String.valueOf(payload.get("processWeight")));
            String description = String.valueOf(payload.get("description"));
            RuleFact current = grades.latestRule(assignmentId).orElse(null);
            if (current != null && money(current.weight()).equals(money(weight))
                    && current.description().equals(description)) {
                return ruleView(current);
            }
            RuleFact created = new RuleFact(UUID.randomUUID().toString(), assignmentId,
                    current == null ? 1 : current.version() + 1, weight, description,
                    principal.userId(), clock.instant());
            grades.insertRule(created);
            return ruleView(created);
        });
    }

    public GradeViews.CorrectionView correct(AuthenticatedUser principal, String assignmentId, String studentId,
                                             GradeRequests.Correction request, String key, String path) {
        return write(principal, "POST", path, key, request, GradeViews.CorrectionView.class, () -> {
            AssignmentHead assignment = lock(assignmentId);
            requireTeacher(principal, assignment);
            requireStudent(assignment, studentId);
            grades.lockTasks(assignmentId);
            Built built = projection(assignment, studentId);
            if (!built.sourceKey().equals(request.expectedSourceKey())) {
                throw new ApiException(ApiCode.SOURCE_CHANGED);
            }
            Built.Line line = built.lines().stream().filter(item -> item.share().unitId().equals(request.unitId()))
                    .findFirst().orElseThrow(() -> ApiException.validation("这名学生在该交付项上没有正式份额"));
            if (!line.share().taskId().equals(request.taskId())) {
                throw ApiException.validation("交付项不属于这个任务");
            }
            BigDecimal responsibility = points(request.correction().responsibilityPoints());
            BigDecimal accepted = points(request.correction().acceptedPoints());
            if (accepted.compareTo(responsibility) > 0) {
                throw ApiException.validation("验收点数不能超过责任点数");
            }
            CorrectionFact row = new CorrectionFact(UUID.randomUUID().toString(), request.taskId(), request.unitId(),
                    responsibility, accepted, request.reason().strip(), write(links(request.evidenceRefs())),
                    built.sourceKey(), principal.userId(), 1, clock.instant());
            grades.insertCorrection(row, assignmentId, studentId);
            return correctionView(row, assignmentId, studentId);
        });
    }

    public GradeViews.SettlementView settle(AuthenticatedUser principal, String assignmentId, String studentId,
                                            GradeRequests.Settle request, String key, String path) {
        return write(principal, "POST", path, key, request, GradeViews.SettlementView.class, () -> {
            AssignmentHead assignment = lock(assignmentId);
            requireTeacher(principal, assignment);
            requireStudent(assignment, studentId);
            grades.lockTasks(assignmentId);
            Built built = projection(assignment, studentId);
            if (!built.sourceKey().equals(request.expectedSourceKey())) {
                throw new ApiException(ApiCode.SOURCE_CHANGED);
            }
            RuleFact rule = grades.latestRule(assignmentId).orElseThrow(() -> ApiException.validation("还没有确认评分规则"));
            if (rule.version() != request.ruleVersion()) {
                throw new ApiException(ApiCode.SOURCE_CHANGED, "评分规则已经变化");
            }
            if (!built.gaps().isEmpty()) {
                throw ApiException.validation(built.gaps().getFirst().message());
            }
            if (built.responsibility().signum() <= 0) {
                throw ApiException.validation("责任分母为 0，不能结算");
            }
            SettlementFact existing = grades.settlement(assignmentId, studentId, built.sourceKey()).orElse(null);
            if (existing != null) {
                return settlementView(existing);
            }
            int b = GradeMath.performance(built.accepted(), built.responsibility());
            SettlementFact created = new SettlementFact(UUID.randomUUID().toString(), assignmentId, studentId,
                    built.sourceKey(), rule.version(), built.responsibility(), built.accepted(), b, clock.instant());
            grades.insertSettlement(created);
            return settlementView(created);
        });
    }

    public GradeViews.DraftView saveDraft(AuthenticatedUser principal, String assignmentId, String studentId,
                                          GradeRequests.Draft request, String key, String path) {
        return write(principal, "PUT", path, key, request, GradeViews.DraftView.class, () -> {
            AssignmentHead assignment = lock(assignmentId);
            requireTeacher(principal, assignment);
            requireStudent(assignment, studentId);
            if (request.delta() != 0 && (request.deltaReason() == null || request.deltaReason().isBlank())) {
                throw ApiException.validation("调整不为 0 时必须写明理由");
            }
            Built built = projection(assignment, studentId);
            RuleFact rule = grades.latestRule(assignmentId).orElseThrow(() -> ApiException.validation("还没有确认评分规则"));
            String reason = request.deltaReason() == null ? "" : request.deltaReason().strip();
            String evidence = write(links(request.evidenceRefs()));
            DraftFact current = grades.draft(assignmentId, studentId).orElse(null);
            if (current == null) {
                if (request.expectedRevision() != 0) {
                    throw new ApiException(ApiCode.RESOURCE_CHANGED);
                }
                DraftFact created = new DraftFact(UUID.randomUUID().toString(), assignmentId, studentId,
                        request.q(), request.delta(), reason, evidence, built.sourceKey(), rule.version(), 1);
                grades.insertDraft(created);
                return draftView(created);
            }
            if (current.revision() != request.expectedRevision()) {
                throw new ApiException(ApiCode.RESOURCE_CHANGED);
            }
            if (current.q() == request.q() && current.delta() == request.delta()
                    && current.deltaReason().equals(reason) && current.evidenceJson().equals(evidence)
                    && current.sourceKey().equals(built.sourceKey()) && current.ruleVersion() == rule.version()) {
                return draftView(current);
            }
            DraftFact next = new DraftFact(current.id(), assignmentId, studentId, request.q(), request.delta(),
                    reason, evidence, built.sourceKey(), rule.version(), current.revision());
            if (grades.updateDraft(next) != 1) {
                throw new ApiException(ApiCode.RESOURCE_CHANGED);
            }
            return draftView(grades.draft(assignmentId, studentId).orElseThrow());
        });
    }

    public GradeViews.PublicationPreviewView previewPublication(AuthenticatedUser principal, String assignmentId,
                                                                GradeRequests.PublicationPreview request,
                                                                String key, String path) {
        return write(principal, "POST", path, key, request, GradeViews.PublicationPreviewView.class, () -> {
            AssignmentHead assignment = lock(assignmentId);
            requireTeacher(principal, assignment);
            List<String> studentIds = request.studentIds().stream().distinct().toList();
            if (studentIds.size() != request.studentIds().size()) {
                throw ApiException.validation("学生不能重复");
            }
            List<GradeViews.CandidateView> rows = new ArrayList<>();
            for (String studentId : studentIds) {
                requireStudent(assignment, studentId);
                rows.add(candidate(assignment, studentId));
            }
            if (request.expectedCandidates() != null) {
                if (request.expectedCandidates().size() != rows.size()) {
                    throw ApiException.validation("名册候选与所选学生不一致");
                }
                for (GradeRequests.Candidate expected : request.expectedCandidates()) {
                    GradeViews.CandidateView actual = rows.stream()
                            .filter(row -> row.studentId().equals(expected.studentId())).findFirst()
                            .orElseThrow(() -> ApiException.validation("名册候选与所选学生不一致"));
                    if (!actual.classification().equals(expected.classification())
                            || !actual.basisDigest().equals(expected.basisDigest())) {
                        throw ApiException.validation("名册候选已经变化，请重新读取");
                    }
                }
            }
            String token = UUID.randomUUID().toString();
            Instant expires = clock.instant().plus(PREVIEW);
            List<Map<String, String>> stored = rows.stream().map(row -> Map.of(
                    "studentId", row.studentId(), "classification", row.classification(),
                    "basisDigest", row.basisDigest())).toList();
            String payload = write(Map.of(
                    "assignmentId", assignmentId, "studentIds", studentIds, "rows", stored,
                    "appealLinks", request.appealLinks() == null ? List.of() : request.appealLinks()));
            String hash = Hashing.sha256Hex(payload);
            String previewId = UUID.randomUUID().toString();
            squares.insertPreviewToken(previewId, Hashing.sha256Hex(token), principal.userId(),
                    "GRADE_PUBLICATION", hash, hash, payload, expires);
            return new GradeViews.PublicationPreviewView(token, expires.toString(), rows, studentIds);
        });
    }

    public GradeViews.PublicationView publish(AuthenticatedUser principal, String assignmentId,
                                              GradeRequests.Publish request, String key, String path) {
        return write(principal, "POST", path, key, request, GradeViews.PublicationView.class, () -> {
            AssignmentHead assignment = lock(assignmentId);
            requireTeacher(principal, assignment);
            grades.lockTasks(assignmentId);
            PreviewTokenRow token = requireToken(principal, request.previewToken(), "GRADE_PUBLICATION");
            if (grades.publicationExists(token.id())) {
                throw ApiException.validation("这份预览已经发布");
            }
            Map<String, Object> payload = read(token.payloadJson(), MAP);
            if (!assignmentId.equals(payload.get("assignmentId"))) {
                throw new ApiException(ApiCode.PREVIEW_STALE);
            }
            List<Map<String, Object>> stored = read(write(payload.get("rows")), MAPS);
            List<GradeRequests.AppealLink> links = read(write(payload.get("appealLinks")),
                    new TypeReference<List<GradeRequests.AppealLink>>() {
                    });
            List<SnapshotFact> created = new ArrayList<>();
            String publicationId = UUID.randomUUID().toString();
            Instant now = clock.instant();
            boolean anyChange = false;
            for (Map<String, Object> storedRow : stored) {
                String studentId = String.valueOf(storedRow.get("studentId"));
                GradeViews.CandidateView current = candidate(assignment, studentId);
                if (!current.classification().equals(storedRow.get("classification"))
                        || !current.basisDigest().equals(storedRow.get("basisDigest"))) {
                    throw new ApiException(ApiCode.PREVIEW_STALE);
                }
                if ("BLOCKED".equals(current.classification())) {
                    throw ApiException.validation(current.blockers().isEmpty()
                            ? "有学生尚未就绪，不能发布" : current.blockers().getFirst());
                }
                GradeRequests.AppealLink link = links.stream()
                        .filter(item -> studentId.equals(item.studentId())).findFirst().orElse(null);
                if ("UNCHANGED".equals(current.classification())) {
                    if (link != null) {
                        throw ApiException.validation("没有新的正式成绩，复核不能结案");
                    }
                    created.add(grades.latestSnapshot(assignmentId, studentId).orElseThrow());
                    continue;
                }
                anyChange = true;
                SnapshotFact previous = grades.latestSnapshot(assignmentId, studentId).orElse(null);
                Built built = projection(assignment, studentId);
                DraftFact draft = grades.draft(assignmentId, studentId).orElseThrow();
                SettlementFact settlement = grades.settlement(assignmentId, studentId, built.sourceKey()).orElseThrow();
                RuleFact rule = grades.latestRule(assignmentId).orElseThrow();
                int g = GradeMath.courseGrade(rule.weight(), settlement.b(), draft.q(), draft.delta());
                SnapshotFact snapshot = new SnapshotFact(UUID.randomUUID().toString(), publicationId, assignmentId,
                        studentId, previous == null ? 1 : previous.version() + 1, settlement.b(), draft.q(),
                        draft.delta(), g, current.basisDigest(), write(ruleView(rule)), write(built.view()),
                        draft.evidenceJson(), now);
                created.add(snapshot);
                if (link != null) {
                    AppealFact appeal = grades.lockAppeal(link.appealId()).orElseThrow(ApiException::notFound);
                    if (!studentId.equals(appeal.studentId()) || !"AWAITING_REVISED_PUBLICATION".equals(appeal.status())) {
                        throw ApiException.validation("复核还没有进入等待新成绩");
                    }
                }
            }
            if (anyChange) {
                grades.insertPublication(publicationId, assignmentId, token.id(), principal.userId(), now);
                for (SnapshotFact snapshot : created) {
                    if (publicationId.equals(snapshot.publicationId())) {
                        grades.insertSnapshot(snapshot);
                    }
                }
                for (GradeRequests.AppealLink link : links) {
                    SnapshotFact snapshot = created.stream().filter(item -> item.studentId().equals(link.studentId())
                            && publicationId.equals(item.publicationId())).findFirst().orElseThrow();
                    AppealFact appeal = grades.lockAppeal(link.appealId()).orElseThrow();
                    AppealFact resolved = new AppealFact(appeal.id(), appeal.gradeSnapshotId(), appeal.assignmentId(),
                            appeal.studentId(), "RESOLVED_REVISED", appeal.issue(), appeal.sourceJson(),
                            appeal.evidenceJson(), appeal.responsesJson(), snapshot.id(), appeal.revision(),
                            appeal.createdAt());
                    if (grades.updateAppeal(resolved, appeal.revision()) != 1) {
                        throw new ApiException(ApiCode.RESOURCE_CHANGED);
                    }
                }
            }
            return new GradeViews.PublicationView(anyChange ? publicationId : token.id(), assignmentId,
                    now.toString(), created.stream().map(this::snapshotView).toList());
        });
    }

    public GradeViews.AppealView openAppeal(AuthenticatedUser principal, String snapshotId,
                                            GradeRequests.Appeal request, String key, String path) {
        return write(principal, "POST", path, key, request, GradeViews.AppealView.class, () -> {
            policy.requireActiveBusinessUser(principal);
            SnapshotFact snapshot = grades.snapshot(snapshotId).orElseThrow(ApiException::notFound);
            if (!principal.userId().equals(snapshot.studentId())) {
                throw new ApiException(ApiCode.FORBIDDEN);
            }
            if (grades.openAppeal(snapshotId)) {
                throw ApiException.validation("这份成绩已经有未结束的复核");
            }
            AppealFact row = new AppealFact(UUID.randomUUID().toString(), snapshotId, snapshot.assignmentId(),
                    snapshot.studentId(), "PENDING", request.issue().strip(),
                    write(request.sourceRefs() == null ? List.of() : request.sourceRefs()),
                    write(links(request.evidenceRefs())), "[]", null, 1, clock.instant());
            grades.insertAppeal(row);
            return appealView(row);
        });
    }

    public GradeViews.AppealView respond(AuthenticatedUser principal, String appealId,
                                         GradeRequests.AppealResponse request, String key, String path) {
        return write(principal, "POST", path, key, request, GradeViews.AppealView.class, () -> {
            AppealFact appeal = grades.lockAppeal(appealId).orElseThrow(ApiException::notFound);
            requireTeacher(principal, requireAssignment(appeal.assignmentId()));
            if (appeal.revision() != request.expectedRevision()) {
                throw new ApiException(ApiCode.RESOURCE_CHANGED);
            }
            if (!appeal.gradeSnapshotId().equals(request.expectedCurrentGradeSnapshotId())) {
                throw new ApiException(ApiCode.SOURCE_CHANGED);
            }
            if (!"PENDING".equals(appeal.status()) && !"REVIEWING".equals(appeal.status())) {
                throw ApiException.validation("这份复核已经回应过");
            }
            String status = "MAINTAIN".equals(request.decision())
                    ? "RESOLVED_MAINTAINED" : "AWAITING_REVISED_PUBLICATION";
            List<GradeViews.ResponseView> responses = new ArrayList<>(read(appeal.responsesJson(), RESPONSES));
            responses.add(new GradeViews.ResponseView(responses.size() + 1, principal.userId(),
                    request.reason().strip(), List.of(), clock.instant().toString()));
            AppealFact next = new AppealFact(appeal.id(), appeal.gradeSnapshotId(), appeal.assignmentId(),
                    appeal.studentId(), status, appeal.issue(), appeal.sourceJson(), appeal.evidenceJson(),
                    write(responses), null, appeal.revision(), appeal.createdAt());
            if (grades.updateAppeal(next, appeal.revision()) != 1) {
                throw new ApiException(ApiCode.RESOURCE_CHANGED);
            }
            return appealView(grades.appeal(appealId).orElseThrow());
        });
    }

    public GradeViews.SummaryView saveSummary(AuthenticatedUser principal, String assignmentId, String studentId,
                                              GradeRequests.Summary request, String key, String path) {
        return write(principal, "PUT", path, key, request, GradeViews.SummaryView.class, () -> {
            AssignmentHead assignment = lock(assignmentId);
            requireTeacher(principal, assignment);
            SnapshotFact snapshot = grades.snapshot(request.sourceGradeSnapshotId()).orElseThrow(ApiException::notFound);
            if (!assignmentId.equals(snapshot.assignmentId()) || !studentId.equals(snapshot.studentId())) {
                throw ApiException.validation("文字评价必须引用这名学生的正式成绩");
            }
            String text = request.summary().strip();
            SummaryFact current = grades.summaryFor(assignmentId, studentId).orElse(null);
            if (current == null) {
                if (request.expectedRevision() != 0) {
                    throw new ApiException(ApiCode.RESOURCE_CHANGED);
                }
                SummaryFact created = new SummaryFact(UUID.randomUUID().toString(), assignmentId, studentId,
                        1, 1, snapshot.id(), text, "ACTIVE");
                grades.insertSummary(created);
                return summaryView(created, false);
            }
            if (current.revision() != request.expectedRevision()) {
                throw new ApiException(ApiCode.RESOURCE_CHANGED);
            }
            if (!"ACTIVE".equals(current.status())) {
                throw ApiException.validation("撤回后的评价不能直接改，请重新撰写");
            }
            if (current.summary().equals(text) && current.snapshotId().equals(snapshot.id())) {
                return summaryView(current, false);
            }
            if (grades.updateSummary(new SummaryFact(current.id(), assignmentId, studentId, current.version(),
                    current.revision(), snapshot.id(), text, "ACTIVE"), current.revision(), true) != 1) {
                throw new ApiException(ApiCode.RESOURCE_CHANGED);
            }
            return summaryView(grades.summary(current.id()).orElseThrow(), false);
        });
    }

    public GradeViews.SummaryView consent(AuthenticatedUser principal, String summaryId,
                                          GradeRequests.Consent request, String key, String path) {
        return write(principal, "POST", path, key, request, GradeViews.SummaryView.class, () -> {
            SummaryFact summary = grades.lockSummary(summaryId).orElseThrow(ApiException::notFound);
            if (!principal.userId().equals(summary.studentId())) {
                throw new ApiException(ApiCode.FORBIDDEN);
            }
            if (!"ACTIVE".equals(summary.status())) {
                throw ApiException.validation("撤回后的评价不能授权展示");
            }
            if (summary.revision() != request.expectedRevision()
                    || summary.version() != request.expectedContentVersion()) {
                throw new ApiException(ApiCode.RESOURCE_CHANGED);
            }
            grades.upsertConsent(summary.id(), summary.version(), summary.studentId(), request.decision());
            return summaryView(summary, true);
        });
    }

    public GradeViews.SummaryView withdraw(AuthenticatedUser principal, String summaryId,
                                           GradeRequests.Withdraw request, String key, String path) {
        return write(principal, "POST", path, key, request, GradeViews.SummaryView.class, () -> {
            SummaryFact summary = grades.lockSummary(summaryId).orElseThrow(ApiException::notFound);
            requireTeacher(principal, requireAssignment(summary.assignmentId()));
            if (summary.revision() != request.expectedRevision()) {
                throw new ApiException(ApiCode.RESOURCE_CHANGED);
            }
            if ("WITHDRAWN".equals(summary.status())) {
                return summaryView(summary, false);
            }
            if (grades.updateSummary(new SummaryFact(summary.id(), summary.assignmentId(), summary.studentId(),
                    summary.version(), summary.revision(), summary.snapshotId(), summary.summary(), "WITHDRAWN"),
                    summary.revision(), false) != 1) {
                throw new ApiException(ApiCode.RESOURCE_CHANGED);
            }
            return summaryView(grades.summary(summaryId).orElseThrow(), false);
        });
    }

    private Built projection(AssignmentHead assignment, String studentId) {
        Map<String, ShareFact> chosen = new LinkedHashMap<>();
        for (ShareFact share : grades.shares(assignment.id(), studentId)) {
            ShareFact previous = chosen.get(share.unitId());
            if (share.activeVersion() == null) {
                if (previous == null || share.agreementVersion() > previous.agreementVersion()) {
                    chosen.put(share.unitId(), share);
                }
            } else if (share.agreementVersion() == share.activeVersion()) {
                chosen.put(share.unitId(), share);
            }
        }
        Map<String, AcceptanceFact> acceptances = new LinkedHashMap<>();
        grades.passedAcceptances(assignment.id(), studentId).forEach(item -> acceptances.put(item.unitId(), item));
        Map<String, CorrectionFact> corrections = new LinkedHashMap<>();
        grades.latestCorrections(assignment.id(), studentId).forEach(item -> corrections.put(item.unitId(), item));
        Map<String, String> pending = new LinkedHashMap<>();
        for (PendingSubmissionFact item : grades.pendingSubmissions(assignment.id(), studentId)) {
            pending.putIfAbsent(item.unitId(), item.submissionId());
        }
        Map<String, List<HandoverFact>> handovers = new LinkedHashMap<>();
        for (HandoverFact item : grades.handovers(assignment.id())) {
            handovers.computeIfAbsent(item.taskId(), key -> new ArrayList<>()).add(item);
        }
        // 同一任务的全部实际阻断按稳定键去重并排序，不能随机取一条。
        handovers.replaceAll((taskId, items) -> items.stream()
                .sorted(Comparator.comparing(HandoverFact::changeId)
                        .thenComparingInt(HandoverFact::planVersion)
                        .thenComparing(HandoverFact::itemId))
                .collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toMap(
                                item -> item.changeId() + "|" + item.planVersion() + "|" + item.itemId(),
                                item -> item, (left, right) -> left, LinkedHashMap::new),
                        map -> new ArrayList<>(map.values()))));
        Instant now = clock.instant();
        List<Built.Line> lines = new ArrayList<>();
        for (ShareFact share : chosen.values().stream().sorted(Comparator.comparing(ShareFact::unitId)).toList()) {
            AcceptanceFact acceptance = acceptances.get(share.unitId());
            boolean confirmed = acceptance != null && grades.confirmedUnits(studentId, acceptance.acceptanceId())
                    .contains(share.unitId());
            CorrectionFact correction = corrections.get(share.unitId());
            boolean duePassed = !now.isBefore(share.dueAt());
            BigDecimal responsibility = correction == null ? share.points() : correction.responsibility();
            String pendingSubmission = pending.get(share.unitId());
            List<HandoverFact> taskHandovers = handovers.getOrDefault(share.taskId(), List.of());
            boolean agreementPending = share.activeVersion() == null;
            List<GradeViews.GapView> gaps = new ArrayList<>();
            // 程序性阻断独立计算，教学更正分支不得绕过。
            if (!share.signed()) {
                gaps.add(gap("UNSIGNED", share.unitId(), "份额还没有本人确认"));
            }
            if (agreementPending) {
                gaps.add(gap("AGREEMENT_PENDING", share.unitId(),
                        share.openAgreementVersion() > 0
                                ? "正式约定尚未生效，当前份额不能结算"
                                : "缺少生效中的正式约定，当前份额不能结算"));
            }
            if (pendingSubmission != null) {
                gaps.add(gap("PENDING_SUBMISSION", share.unitId(),
                        "该交付项还有待验收提交，不能按到期未完成结算"));
            }
            if (!taskHandovers.isEmpty()) {
                gaps.add(gap("HANDOVER_PENDING", share.unitId(),
                        "该任务仍在交接或重签过程中，不能结算"));
            }
            if (acceptance == null && !duePassed) {
                gaps.add(gap("BEFORE_DUE", share.unitId(), "截止前尚未完成，不能结算"));
            }
            if (acceptance != null && !confirmed) {
                gaps.add(gap("UNCONFIRMED", share.unitId(), "已验收但本人还没有确认贡献"));
            }
            BigDecimal accepted;
            if (correction != null) {
                accepted = correction.accepted();
            } else if (acceptance != null && confirmed) {
                accepted = share.points();
            } else {
                accepted = BigDecimal.ZERO.setScale(2);
            }
            lines.add(new Built.Line(share, acceptance, confirmed, correction, pendingSubmission,
                    taskHandovers, responsibility, accepted, gaps));
        }
        BigDecimal responsibility = lines.stream().map(Built.Line::responsibility).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal accepted = lines.stream().map(Built.Line::accepted).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<GradeViews.GapView> gaps = new ArrayList<>();
        lines.forEach(line -> gaps.addAll(line.gaps()));
        if (gaps.isEmpty() && responsibility.signum() <= 0) {
            gaps.add(gap("ZERO_DENOMINATOR", assignment.id(), "责任分母为 0"));
        }
        String sourceKey = Hashing.sha256Hex(sourceText(lines));
        SettlementFact settlement = grades.settlement(assignment.id(), studentId, sourceKey).orElse(null);
        return new Built(assignment.id(), studentId, lines, responsibility, accepted, gaps, sourceKey, settlement);
    }

    private GradeViews.CandidateView candidate(AssignmentHead assignment, String studentId) {
        Built built = projection(assignment, studentId);
        RuleFact rule = grades.latestRule(assignment.id()).orElse(null);
        DraftFact draft = grades.draft(assignment.id(), studentId).orElse(null);
        SnapshotFact previous = grades.latestSnapshot(assignment.id(), studentId).orElse(null);
        List<String> blockers = new ArrayList<>(built.gaps().stream().map(GradeViews.GapView::message).toList());
        if (rule == null) {
            blockers.add("还没有确认评分规则");
        }
        if (built.settlement() == null) {
            blockers.add("还没有与当前来源一致的结算");
        } else if (rule != null && built.settlement().ruleVersion() != rule.version()) {
            blockers.add("结算使用的规则已经过期");
        }
        if (draft == null) {
            blockers.add("还没有评价草稿");
        } else if (!draft.sourceKey().equals(built.sourceKey()) || rule == null || draft.ruleVersion() != rule.version()) {
            blockers.add("评价草稿绑定的来源已经失效");
        }
        String digest = Hashing.sha256Hex(basisText(built, rule, draft));
        String classification;
        Integer b = null;
        Integer q = null;
        Integer delta = null;
        Integer grade = null;
        if (!blockers.isEmpty()) {
            classification = "BLOCKED";
        } else if (previous != null && previous.basisDigest().equals(digest)) {
            classification = "UNCHANGED";
            b = previous.b();
            q = previous.q();
            delta = previous.delta();
            grade = previous.g();
        } else {
            classification = previous == null ? "FIRST" : "REVISION";
            b = built.settlement().b();
            q = draft.q();
            delta = draft.delta();
            grade = GradeMath.courseGrade(rule.weight(), b, q, delta);
        }
        return new GradeViews.CandidateView(studentId, grades.displayName(studentId), assignment.id(),
                classification, digest, blockers, b, q, delta, grade,
                rule == null ? Map.of() : ruleView(rule), built.view(),
                draft == null ? Map.of() : draftView(draft),
                previous == null ? Map.of() : snapshotView(previous));
    }

    private String sourceText(List<Built.Line> lines) {
        StringBuilder text = new StringBuilder("grade-ledger-v2");
        for (Built.Line line : lines) {
            ShareFact share = line.share();
            text.append('\n').append(share.unitId()).append('|').append(share.shareId())
                    .append('|').append(share.agreementVersion()).append('|').append(money(share.points()))
                    .append('|').append(share.signed()).append('|').append(share.dueAt())
                    .append('|').append(share.activeVersion() == null ? "" : share.activeVersion())
                    .append('|').append(share.openAgreementVersion()).append('|').append(share.openAgreementStatus())
                    .append('|').append(line.acceptance() == null ? "" : line.acceptance().acceptanceId())
                    .append('|').append(line.acceptance() == null ? "" : line.acceptance().kind())
                    .append('|').append(line.confirmed())
                    .append('|').append(line.correction() == null ? ""
                            : line.correction().id() + ":" + line.correction().revision())
                    .append('|').append(line.pendingSubmissionId() == null ? "" : line.pendingSubmissionId())
                    .append('|').append(line.handovers().isEmpty() ? ""
                            : line.handovers().stream().map(item -> item.changeId() + ":" + item.planVersion()
                                    + ":" + item.itemId() + ":" + item.status() + ":" + item.blocked())
                                    .collect(java.util.stream.Collectors.joining(",")))
                    .append('|').append(money(line.responsibility())).append('|').append(money(line.accepted()));
        }
        return text.toString();
    }

    private String basisText(Built built, RuleFact rule, DraftFact draft) {
        return "grade-basis-v1\n" + built.sourceKey()
                + "\n" + (rule == null ? "" : rule.version() + "|" + money(rule.weight()))
                + "\n" + (draft == null ? "" : draft.q() + "|" + draft.delta() + "|" + draft.deltaReason()
                + "|" + draft.evidenceJson() + "|" + draft.sourceKey())
                + "\n" + (built.settlement() == null ? "" : built.settlement().id() + "|" + built.settlement().b());
    }

    private AssignmentHead readable(AuthenticatedUser principal, String assignmentId) {
        policy.requireActiveBusinessUser(principal);
        return requireAssignment(assignmentId);
    }

    private AssignmentHead requireAssignment(String assignmentId) {
        return grades.assignment(assignmentId).orElseThrow(ApiException::notFound);
    }

    private AssignmentHead lock(String assignmentId) {
        return grades.lockAssignment(assignmentId).orElseThrow(ApiException::notFound);
    }

    private void requireTeacher(AuthenticatedUser principal, AssignmentHead assignment) {
        policy.requireActiveBusinessUser(principal);
        if (!teaching.isActiveTeacher(assignment.squareId(), principal.userId())) {
            throw new ApiException(ApiCode.FORBIDDEN);
        }
    }

    private void requireReader(AuthenticatedUser principal, AssignmentHead assignment, String studentId) {
        if (!principal.userId().equals(studentId)
                && !teaching.isActiveTeacher(assignment.squareId(), principal.userId())) {
            throw new ApiException(ApiCode.FORBIDDEN);
        }
    }

    private void requireStudent(AssignmentHead assignment, String studentId) {
        if (!grades.activeMember(assignment.squareId(), studentId)) {
            throw ApiException.notFound();
        }
    }

    private Map<String, Object> tokenPayload(AuthenticatedUser principal, String token, String kind) {
        return read(requireToken(principal, token, kind).payloadJson(), MAP);
    }

    private PreviewTokenRow requireToken(AuthenticatedUser principal, String token, String kind) {
        PreviewTokenRow row = squares.findPreviewToken(Hashing.sha256Hex(token)).orElseThrow(ApiException::notFound);
        if (!principal.userId().equals(row.userId()) || !kind.equals(row.kind())
                || !clock.instant().isBefore(row.expiresAt())) {
            throw new ApiException(ApiCode.PREVIEW_STALE);
        }
        return row;
    }

    private GradeViews.GapView gap(String code, String resourceId, String message) {
        return new GradeViews.GapView(code, "LEDGER", resourceId, message);
    }

    private List<GradeViews.LinkView> links(List<GradeRequests.LinkRef> refs) {
        if (refs == null) {
            return List.of();
        }
        return refs.stream().map(ref -> new GradeViews.LinkView(ref.kind(), ref.url(), ref.title())).toList();
    }

    private GradeViews.RuleView ruleView(RuleFact row) {
        return new GradeViews.RuleView(row.id(), row.assignmentId(), row.version(), money(row.weight()),
                row.description(), "HALF_UP", row.confirmedBy(), row.confirmedAt().toString(), row.version());
    }

    private GradeViews.CorrectionView correctionView(CorrectionFact row, String assignmentId, String studentId) {
        return new GradeViews.CorrectionView(row.id(), assignmentId, studentId, row.taskId(), row.unitId(),
                new GradeViews.PointsView(money(row.responsibility()), money(row.accepted())), row.reason(),
                read(row.evidenceJson(), LINKS), row.authorId(), row.revision(), row.createdAt().toString());
    }

    private GradeViews.SettlementView settlementView(SettlementFact row) {
        return new GradeViews.SettlementView(row.id(), row.assignmentId(), row.studentId(), row.sourceKey(),
                row.ruleVersion(), money(row.responsibility()), money(row.accepted()), row.b(),
                row.settledAt().toString());
    }

    private GradeViews.DraftView draftView(DraftFact row) {
        return new GradeViews.DraftView(row.id(), row.assignmentId(), row.studentId(), row.q(), row.delta(),
                row.deltaReason(), read(row.evidenceJson(), LINKS), row.sourceKey(), row.ruleVersion(),
                row.revision());
    }

    private GradeViews.SnapshotView snapshotView(SnapshotFact row) {
        return new GradeViews.SnapshotView(row.id(), row.publicationId(), row.assignmentId(), row.studentId(),
                row.version(), row.b(), row.q(), row.delta(), row.g(), row.basisDigest(),
                read(row.ruleJson(), Object.class), read(row.ledgerJson(), Object.class),
                read(row.evidenceJson(), Object.class), row.createdAt().toString());
    }

    private GradeViews.AppealView appealView(AppealFact row) {
        return new GradeViews.AppealView(row.id(), row.gradeSnapshotId(), row.assignmentId(), row.studentId(),
                grades.displayName(row.studentId()), row.issue(), read(row.sourceJson(), SOURCES),
                read(row.evidenceJson(), LINKS), row.status(), read(row.responsesJson(), RESPONSES),
                row.gradeSnapshotId(), row.revisedSnapshotId(), row.revision(), row.createdAt().toString());
    }

    private GradeViews.SummaryView summaryView(SummaryFact row, boolean includeConsent) {
        Object consent = Map.of();
        if (includeConsent) {
            consent = grades.consent(row.id(), row.version(), row.studentId())
                    .map(decision -> (Object) new GradeViews.ConsentView(decision, row.version()))
                    .orElse(Map.of());
        }
        return new GradeViews.SummaryView(new GradeViews.SummaryBody(row.id(), row.assignmentId(), row.studentId(),
                row.summary(), row.snapshotId(), row.version(), row.status(), row.revision(), consent));
    }

    private BigDecimal weight(String raw) {
        if (!DecimalStrings.isWellFormed(raw)) {
            throw ApiException.validation("履约权重格式无效");
        }
        BigDecimal value = DecimalStrings.parse(raw);
        if (value.signum() < 0 || value.compareTo(BigDecimal.ONE) > 0) {
            throw ApiException.validation("履约权重必须在 0 到 1 之间");
        }
        return value;
    }

    private BigDecimal points(String raw) {
        if (!DecimalStrings.isWellFormed(raw)) {
            throw ApiException.validation("点数格式无效");
        }
        BigDecimal value = DecimalStrings.parse(raw);
        if (value.signum() < 0 || value.compareTo(new BigDecimal("1000000")) > 0) {
            throw ApiException.validation("点数超出允许范围");
        }
        return value;
    }

    private static String money(BigDecimal value) {
        return DecimalStrings.canonical(value);
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private <T> T read(String json, TypeReference<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private <T> T read(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private <T> T write(AuthenticatedUser principal, String method, String path, String key, Object body,
                        Class<T> type, Supplier<T> action) {
        policy.requireActiveBusinessUser(principal);
        return idempotency.execute(new IdempotencyRequest(principal, method, path, key, body, List.of()),
                type, () -> {
                }, action);
    }

    private record Built(String assignmentId, String studentId, List<Line> lines, BigDecimal responsibility,
                         BigDecimal accepted, List<GradeViews.GapView> gaps, String sourceKey,
                         SettlementFact settlement) {
        GradeViews.LedgerView view() {
            List<GradeViews.LedgerRow> rows = lines.stream().map(line -> new GradeViews.LedgerRow(
                    line.share().taskId(), line.share().taskTitle(), line.share().unitId(), line.share().unitTitle(),
                    money(line.responsibility()), money(line.accepted()), line.share().signed(),
                    line.acceptance() != null, line.confirmed(),
                    line.acceptance() == null ? "" : line.acceptance().acceptanceId(),
                    line.acceptance() == null ? "" : line.acceptance().kind())).toList();
            return new GradeViews.LedgerView(assignmentId, studentId, money(responsibility), money(accepted),
                    gaps.isEmpty() ? "READY" : "NEEDS_CHECK", sourceKey, rows, gaps,
                    settlement == null ? Map.of() : new GradeViews.SettlementView(settlement.id(),
                            settlement.assignmentId(), settlement.studentId(), settlement.sourceKey(),
                            settlement.ruleVersion(), money(settlement.responsibility()), money(settlement.accepted()),
                            settlement.b(), settlement.settledAt().toString()));
        }

        private record Line(ShareFact share, AcceptanceFact acceptance, boolean confirmed, CorrectionFact correction,
                            String pendingSubmissionId, List<HandoverFact> handovers,
                            BigDecimal responsibility, BigDecimal accepted, List<GradeViews.GapView> gaps) {
        }
    }
}
