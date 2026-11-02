package cn.gulupai.server.report;

import cn.gulupai.server.common.api.ApiResponse;
import cn.gulupai.server.common.api.ListEnvelope;
import cn.gulupai.server.common.security.AuthenticatedUser;
import cn.gulupai.server.team.api.TeamViews;
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
public class ReportController {

    private final ReportService reports;

    public ReportController(ReportService reports) {
        this.reports = reports;
    }

    @GetMapping("/projects/{projectId}/delivery-release-draft")
    public ApiResponse<ReportViews.DeliveryDraft> deliveryDraft(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String projectId) {
        return ApiResponse.ok(reports.deliveryDraft(principal, projectId));
    }

    @GetMapping("/projects/{projectId}/delivery-releases")
    public ApiResponse<ListEnvelope<ReportViews.DeliveryRelease>> releases(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String projectId) {
        return ApiResponse.ok(reports.releases(principal, projectId));
    }

    @GetMapping("/projects/{projectId}/report")
    public ApiResponse<ReportViews.Report> report(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String projectId) {
        return ApiResponse.ok(reports.report(principal, projectId));
    }

    @GetMapping("/projects/{projectId}/report-snapshots")
    public ApiResponse<ListEnvelope<ReportViews.Snapshot>> snapshots(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String projectId) {
        return ApiResponse.ok(reports.snapshots(principal, projectId));
    }

    @GetMapping("/report-snapshots/{snapshotId}")
    public ApiResponse<ReportViews.Snapshot> snapshot(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String snapshotId) {
        return ApiResponse.ok(reports.snapshot(principal, snapshotId));
    }

    @PostMapping("/projects/{projectId}/delivery-release-previews")
    public ApiResponse<ReportViews.DeliveryPreview> previewDelivery(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String projectId,
            @Valid @RequestBody ReportRequests.PreviewDelivery request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            HttpServletRequest http) {
        return ApiResponse.ok(reports.previewDelivery(principal, projectId, request, key, http.getRequestURI()));
    }

    @PostMapping("/projects/{projectId}/delivery-releases")
    public ApiResponse<ReportViews.DeliveryRelease> publishDelivery(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String projectId,
            @Valid @RequestBody ReportRequests.PublishDelivery request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            HttpServletRequest http) {
        return ApiResponse.ok(reports.publishDelivery(principal, projectId, request, key, http.getRequestURI()));
    }

    @PostMapping("/projects/{projectId}/lifecycle-actions")
    public ApiResponse<TeamViews.ProjectView> lifecycle(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String projectId,
            @Valid @RequestBody ReportRequests.LifecycleAction request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            HttpServletRequest http) {
        return ApiResponse.ok(reports.lifecycle(principal, projectId, request, key, http.getRequestURI()));
    }

    @PostMapping("/projects/{projectId}/report-snapshots")
    public ApiResponse<ReportViews.Snapshot> createSnapshot(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String projectId,
            @Valid @RequestBody ReportRequests.CreateSnapshot request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            HttpServletRequest http) {
        return ApiResponse.ok(reports.createSnapshot(principal, projectId, request, key, http.getRequestURI()));
    }

    @PutMapping("/projects/{projectId}/delivery-release-draft")
    public ApiResponse<ReportViews.DeliveryDraft> updateDeliveryDraft(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String projectId,
            @Valid @RequestBody ReportRequests.UpdateDeliveryDraft request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            HttpServletRequest http) {
        return ApiResponse.ok(reports.updateDeliveryDraft(principal, projectId, request, key, http.getRequestURI()));
    }

    @PutMapping("/projects/{projectId}/me/reflection")
    public ApiResponse<ReportViews.Reflection> saveReflection(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String projectId,
            @Valid @RequestBody ReportRequests.PersonalReflection request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            HttpServletRequest http) {
        return ApiResponse.ok(reports.saveReflection(principal, projectId, request, key, http.getRequestURI()));
    }

    @PutMapping("/projects/{projectId}/overall-reflection")
    public ApiResponse<ReportViews.OverallReflection> saveOverallReflection(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String projectId,
            @Valid @RequestBody ReportRequests.OverallReflection request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            HttpServletRequest http) {
        return ApiResponse.ok(reports.saveOverallReflection(principal, projectId, request, key, http.getRequestURI()));
    }
}
