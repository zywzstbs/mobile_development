package cn.gulupai.server.profile.api;

import cn.gulupai.server.common.api.ApiResponse;
import cn.gulupai.server.common.api.ListEnvelope;
import cn.gulupai.server.common.security.AuthenticatedUser;
import cn.gulupai.server.profile.application.ProfileWorksService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class ProfileController {

    private final ProfileWorksService profiles;

    public ProfileController(ProfileWorksService profiles) {
        this.profiles = profiles;
    }

    @GetMapping("/me/cooperations")
    public ApiResponse<ListEnvelope<ProfileViews.CooperationView>> cooperations(
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return ApiResponse.ok(ListEnvelope.full(profiles.cooperations(principal)));
    }

    @GetMapping("/me/works")
    public ApiResponse<ListEnvelope<ProfileViews.WorkView>> myWorks(
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return ApiResponse.ok(ListEnvelope.full(profiles.myWorks(principal)));
    }

    @PostMapping("/me/works")
    public ApiResponse<ProfileViews.WorkView> createWork(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody ProfileRequests.WorkRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(profiles.createWork(principal, request, key,
                servletRequest.getRequestURI()));
    }

    @PatchMapping("/me/works/{workId}")
    public ApiResponse<ProfileViews.WorkView> patchWork(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String workId,
            @Valid @RequestBody ProfileRequests.WorkRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(profiles.patchWork(principal, workId, request, key,
                servletRequest.getRequestURI()));
    }

    @PostMapping("/me/works/{workId}/withdrawals")
    public ApiResponse<ProfileViews.WorkView> withdrawWork(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String workId,
            @Valid @RequestBody ProfileRequests.WorkWithdrawRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(profiles.withdrawWork(principal, workId, request, key,
                servletRequest.getRequestURI()));
    }

    @GetMapping("/works/{workId}")
    public ApiResponse<ProfileViews.WorkView> work(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String workId) {
        return ApiResponse.ok(profiles.work(principal, workId));
    }

    @GetMapping("/users/{userId}/profile")
    public ApiResponse<ProfileViews.ProfileView> profile(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String userId,
            @RequestParam(required = false) String contextProjectId,
            @RequestParam(required = false) String contextApplicationId) {
        return ApiResponse.ok(profiles.userProfile(principal, userId, contextProjectId,
                contextApplicationId));
    }

    @GetMapping("/projects/{projectId}/peer-review-context")
    public ApiResponse<ProfileViews.PeerContextView> peerContext(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String projectId) {
        return ApiResponse.ok(profiles.peerContext(principal, projectId));
    }

    @GetMapping("/peer-reviews/{reviewId}")
    public ApiResponse<ProfileViews.PeerReviewView> peerReview(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String reviewId) {
        return ApiResponse.ok(profiles.peerReview(principal, reviewId));
    }

    @PostMapping("/projects/{projectId}/peer-reviews")
    public ApiResponse<ProfileViews.PeerReviewView> createPeerReview(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String projectId,
            @Valid @RequestBody ProfileRequests.PeerReviewRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(profiles.createPeerReview(principal, projectId, request, key,
                servletRequest.getRequestURI()));
    }

    @PutMapping("/peer-reviews/{reviewId}")
    public ApiResponse<ProfileViews.PeerReviewView> updatePeerReview(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String reviewId,
            @Valid @RequestBody ProfileRequests.PeerReviewRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(profiles.updatePeerReview(principal, reviewId, request, key,
                servletRequest.getRequestURI()));
    }

    @PostMapping("/peer-reviews/{reviewId}/withdrawals")
    public ApiResponse<ProfileViews.PeerReviewView> withdrawPeerReview(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String reviewId,
            @Valid @RequestBody ProfileRequests.PeerReviewWithdrawRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(profiles.withdrawPeerReview(principal, reviewId, request, key,
                servletRequest.getRequestURI()));
    }

    @GetMapping("/projects/{projectId}/ai-references")
    public ApiResponse<ListEnvelope<ProfileViews.AiReferenceView>> aiReferences(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String projectId) {
        return ApiResponse.ok(ListEnvelope.full(profiles.aiReferences(principal, projectId)));
    }

    @PostMapping("/projects/{projectId}/client-ai-references")
    public ApiResponse<ProfileViews.AiReferenceView> createAiReference(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String projectId,
            @Valid @RequestBody ProfileRequests.AiReferenceRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(profiles.createAiReference(principal, projectId, request, key,
                servletRequest.getRequestURI()));
    }
}
