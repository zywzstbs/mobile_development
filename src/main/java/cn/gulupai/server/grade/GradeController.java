package cn.gulupai.server.grade;

import cn.gulupai.server.common.api.ApiResponse;
import cn.gulupai.server.common.api.ListEnvelope;
import cn.gulupai.server.common.security.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class GradeController {

    private final GradeService grades;

    public GradeController(GradeService grades) {
        this.grades = grades;
    }

    @GetMapping("/assignments/{assignmentId}/grade-appeals")
    public ApiResponse<ListEnvelope<GradeViews.AppealView>> appeals(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String assignmentId) {
        return ApiResponse.ok(ListEnvelope.full(grades.appeals(principal, assignmentId)));
    }

    @GetMapping("/assignments/{assignmentId}/gradebook")
    public ApiResponse<ListEnvelope<GradeViews.CandidateView>> gradebook(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String assignmentId) {
        return ApiResponse.ok(ListEnvelope.full(grades.gradebook(principal, assignmentId)));
    }

    @GetMapping("/assignments/{assignmentId}/grading-rule")
    public ApiResponse<GradeViews.RuleView> rule(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String assignmentId) {
        return ApiResponse.ok(grades.rule(principal, assignmentId));
    }

    @GetMapping("/assignments/{assignmentId}/students/{studentId}/grade-history")
    public ApiResponse<ListEnvelope<GradeViews.SnapshotView>> history(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String assignmentId,
            @PathVariable String studentId) {
        return ApiResponse.ok(ListEnvelope.full(grades.history(principal, assignmentId, studentId)));
    }

    @GetMapping("/assignments/{assignmentId}/students/{studentId}/ledger")
    public ApiResponse<GradeViews.LedgerView> ledger(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String assignmentId,
            @PathVariable String studentId) {
        return ApiResponse.ok(grades.ledger(principal, assignmentId, studentId));
    }

    @GetMapping("/assignments/{assignmentId}/students/{studentId}/teacher-summary")
    public ApiResponse<GradeViews.SummaryView> teacherSummary(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String assignmentId,
            @PathVariable String studentId) {
        return ApiResponse.ok(grades.teacherSummary(principal, assignmentId, studentId));
    }

    @GetMapping("/grade-appeals/{appealId}")
    public ApiResponse<GradeViews.AppealView> appeal(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String appealId) {
        return ApiResponse.ok(grades.appeal(principal, appealId));
    }

    @GetMapping("/grade-snapshots/{snapshotId}")
    public ApiResponse<GradeViews.SnapshotView> snapshot(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String snapshotId) {
        return ApiResponse.ok(grades.snapshot(principal, snapshotId));
    }

    @GetMapping("/me/grade-appeals")
    public ApiResponse<ListEnvelope<GradeViews.AppealView>> myAppeals(
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return ApiResponse.ok(ListEnvelope.full(grades.myAppeals(principal)));
    }

    @GetMapping("/me/grades")
    public ApiResponse<ListEnvelope<GradeViews.SnapshotView>> myGrades(
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return ApiResponse.ok(ListEnvelope.full(grades.myGrades(principal)));
    }

    @GetMapping("/me/teacher-summaries")
    public ApiResponse<ListEnvelope<GradeViews.SummaryView>> mySummaries(
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return ApiResponse.ok(ListEnvelope.full(grades.mySummaries(principal)));
    }

    @GetMapping("/teacher-summaries/{summaryId}")
    public ApiResponse<GradeViews.SummaryView> summary(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String summaryId) {
        return ApiResponse.ok(grades.summary(principal, summaryId));
    }

    @PostMapping("/assignments/{assignmentId}/grade-publication-previews")
    public ApiResponse<GradeViews.PublicationPreviewView> previewPublication(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String assignmentId,
            @Valid @RequestBody GradeRequests.PublicationPreview request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, HttpServletRequest http) {
        return ApiResponse.ok(grades.previewPublication(principal, assignmentId, request, key, http.getRequestURI()));
    }

    @PostMapping("/assignments/{assignmentId}/grade-publications")
    public ApiResponse<GradeViews.PublicationView> publish(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String assignmentId,
            @Valid @RequestBody GradeRequests.Publish request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, HttpServletRequest http) {
        return ApiResponse.ok(grades.publish(principal, assignmentId, request, key, http.getRequestURI()));
    }

    @PostMapping("/assignments/{assignmentId}/grading-rule-previews")
    public ApiResponse<GradeViews.RulePreviewView> previewRule(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String assignmentId,
            @Valid @RequestBody GradeRequests.RulePreview request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, HttpServletRequest http) {
        return ApiResponse.ok(grades.previewRule(principal, assignmentId, request, key, http.getRequestURI()));
    }

    @PostMapping("/assignments/{assignmentId}/grading-rules")
    public ApiResponse<GradeViews.RuleView> confirmRule(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String assignmentId,
            @Valid @RequestBody GradeRequests.ConfirmRule request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, HttpServletRequest http) {
        return ApiResponse.ok(grades.confirmRule(principal, assignmentId, request, key, http.getRequestURI()));
    }

    @PostMapping("/assignments/{assignmentId}/students/{studentId}/ledger-corrections")
    public ApiResponse<GradeViews.CorrectionView> correct(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String assignmentId,
            @PathVariable String studentId, @Valid @RequestBody GradeRequests.Correction request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, HttpServletRequest http) {
        return ApiResponse.ok(grades.correct(principal, assignmentId, studentId, request, key, http.getRequestURI()));
    }

    @PostMapping("/assignments/{assignmentId}/students/{studentId}/settlements")
    public ApiResponse<GradeViews.SettlementView> settle(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String assignmentId,
            @PathVariable String studentId, @Valid @RequestBody GradeRequests.Settle request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, HttpServletRequest http) {
        return ApiResponse.ok(grades.settle(principal, assignmentId, studentId, request, key, http.getRequestURI()));
    }

    @PostMapping("/grade-appeals/{appealId}/responses")
    public ApiResponse<GradeViews.AppealView> respond(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String appealId,
            @Valid @RequestBody GradeRequests.AppealResponse request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, HttpServletRequest http) {
        return ApiResponse.ok(grades.respond(principal, appealId, request, key, http.getRequestURI()));
    }

    @PostMapping("/grade-snapshots/{snapshotId}/appeals")
    public ApiResponse<GradeViews.AppealView> openAppeal(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String snapshotId,
            @Valid @RequestBody GradeRequests.Appeal request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, HttpServletRequest http) {
        return ApiResponse.ok(grades.openAppeal(principal, snapshotId, request, key, http.getRequestURI()));
    }

    @PostMapping("/teacher-summaries/{summaryId}/consents")
    public ApiResponse<GradeViews.SummaryView> consent(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String summaryId,
            @Valid @RequestBody GradeRequests.Consent request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, HttpServletRequest http) {
        return ApiResponse.ok(grades.consent(principal, summaryId, request, key, http.getRequestURI()));
    }

    @PostMapping("/teacher-summaries/{summaryId}/withdrawals")
    public ApiResponse<GradeViews.SummaryView> withdraw(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String summaryId,
            @Valid @RequestBody GradeRequests.Withdraw request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, HttpServletRequest http) {
        return ApiResponse.ok(grades.withdraw(principal, summaryId, request, key, http.getRequestURI()));
    }

    @PutMapping("/assignments/{assignmentId}/students/{studentId}/grade-draft")
    public ApiResponse<GradeViews.DraftView> saveDraft(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String assignmentId,
            @PathVariable String studentId, @Valid @RequestBody GradeRequests.Draft request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, HttpServletRequest http) {
        return ApiResponse.ok(grades.saveDraft(principal, assignmentId, studentId, request, key, http.getRequestURI()));
    }

    @PutMapping("/assignments/{assignmentId}/students/{studentId}/teacher-summary")
    public ApiResponse<GradeViews.SummaryView> saveSummary(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String assignmentId,
            @PathVariable String studentId, @Valid @RequestBody GradeRequests.Summary request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, HttpServletRequest http) {
        return ApiResponse.ok(grades.saveSummary(principal, assignmentId, studentId, request, key, http.getRequestURI()));
    }
}
