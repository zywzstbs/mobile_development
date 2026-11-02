package cn.gulupai.server.governance;

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
public class GovController {

    private final GovService governance;

    public GovController(GovService governance) {
        this.governance = governance;
    }

    @GetMapping("/material-requests/{requestId}")
    public ApiResponse<GovViews.Material> material(@AuthenticationPrincipal AuthenticatedUser principal,
                                                   @PathVariable String requestId) {
        return ApiResponse.ok(governance.material(principal, requestId));
    }

    @GetMapping("/me/material-requests")
    public ApiResponse<ListEnvelope<GovViews.Material>> myMaterials(@AuthenticationPrincipal AuthenticatedUser principal) {
        return ApiResponse.ok(ListEnvelope.full(governance.myMaterials(principal)));
    }

    @GetMapping("/me/membership-history")
    public ApiResponse<ListEnvelope<GovRepository.MembershipHistory>> history(
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return ApiResponse.ok(ListEnvelope.full(governance.membershipHistory(principal)));
    }

    @GetMapping("/me/personnel-handoffs")
    public ApiResponse<ListEnvelope<GovViews.Handoff>> myHandoffs(@AuthenticationPrincipal AuthenticatedUser principal) {
        return ApiResponse.ok(ListEnvelope.full(governance.myHandoffs(principal)));
    }

    @GetMapping("/member-changes/{changeId}")
    public ApiResponse<GovViews.MemberChange> change(@AuthenticationPrincipal AuthenticatedUser principal,
                                                     @PathVariable String changeId) {
        return ApiResponse.ok(governance.change(principal, changeId));
    }

    @GetMapping("/personnel-cases/{caseId}")
    public ApiResponse<GovViews.CaseView> personnelCase(@AuthenticationPrincipal AuthenticatedUser principal,
                                                        @PathVariable String caseId) {
        return ApiResponse.ok(governance.personnelCase(principal, caseId));
    }

    @GetMapping("/personnel-cases/{caseId}/handoff-candidates")
    public ApiResponse<ListEnvelope<GovViews.Person>> candidates(@AuthenticationPrincipal AuthenticatedUser principal,
                                                                 @PathVariable String caseId) {
        return ApiResponse.ok(ListEnvelope.full(governance.handoffCandidates(principal, caseId)));
    }

    @GetMapping("/personnel-cases/{caseId}/material-recipients")
    public ApiResponse<ListEnvelope<GovViews.Person>> recipients(@AuthenticationPrincipal AuthenticatedUser principal,
                                                                 @PathVariable String caseId) {
        return ApiResponse.ok(ListEnvelope.full(governance.materialRecipients(principal, caseId)));
    }

    @GetMapping("/personnel-handoffs/{handoffId}")
    public ApiResponse<GovViews.Handoff> handoff(@AuthenticationPrincipal AuthenticatedUser principal,
                                                 @PathVariable String handoffId) {
        return ApiResponse.ok(governance.handoff(principal, handoffId));
    }

    @GetMapping("/projects/{projectId}/incoming-transfers")
    public ApiResponse<ListEnvelope<GovViews.MemberChange>> incoming(@AuthenticationPrincipal AuthenticatedUser principal,
                                                                     @PathVariable String projectId) {
        return ApiResponse.ok(ListEnvelope.full(governance.incoming(principal, projectId)));
    }

    @GetMapping("/projects/{projectId}/member-changes")
    public ApiResponse<ListEnvelope<GovViews.MemberChange>> projectChanges(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String projectId) {
        return ApiResponse.ok(ListEnvelope.full(governance.projectChanges(principal, projectId)));
    }

    @GetMapping("/projects/{projectId}/removal-votes")
    public ApiResponse<ListEnvelope<GovViews.Vote>> votes(@AuthenticationPrincipal AuthenticatedUser principal,
                                                          @PathVariable String projectId) {
        return ApiResponse.ok(ListEnvelope.full(governance.votes(principal, projectId)));
    }

    @GetMapping("/projects/{projectId}/transfer-targets")
    public ApiResponse<ListEnvelope<GovViews.MemberChange>> targets(@AuthenticationPrincipal AuthenticatedUser principal,
                                                                    @PathVariable String projectId) {
        return ApiResponse.ok(ListEnvelope.full(governance.transferTargets(principal, projectId)));
    }

    @GetMapping("/removal-votes/{voteId}")
    public ApiResponse<GovViews.Vote> vote(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String voteId) {
        return ApiResponse.ok(governance.vote(principal, voteId));
    }

    @GetMapping("/squares/{squareId}/member-changes")
    public ApiResponse<ListEnvelope<GovViews.MemberChange>> squareChanges(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String squareId) {
        return ApiResponse.ok(ListEnvelope.full(governance.squareChanges(principal, squareId)));
    }

    @GetMapping("/squares/{squareId}/personnel-cases")
    public ApiResponse<ListEnvelope<GovViews.CaseView>> squareCases(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String squareId) {
        return ApiResponse.ok(ListEnvelope.full(governance.squareCases(principal, squareId)));
    }

    @PostMapping("/material-requests/{requestId}/responses")
    public ApiResponse<GovViews.Material> respondMaterial(@AuthenticationPrincipal AuthenticatedUser principal,
                                                          @PathVariable String requestId,
                                                          @Valid @RequestBody GovRequests.MaterialResponse request,
                                                          @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                          HttpServletRequest http) {
        return ApiResponse.ok(governance.respondMaterial(principal, requestId, request, key, http.getRequestURI()));
    }

    @PostMapping("/material-requests/{requestId}/reviews")
    public ApiResponse<GovViews.Material> reviewMaterial(@AuthenticationPrincipal AuthenticatedUser principal,
                                                         @PathVariable String requestId,
                                                         @Valid @RequestBody GovRequests.MaterialReview request,
                                                         @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                         HttpServletRequest http) {
        return ApiResponse.ok(governance.reviewMaterial(principal, requestId, request, key, http.getRequestURI()));
    }

    @PostMapping("/material-requests/{requestId}/withdrawals")
    public ApiResponse<GovViews.Material> withdrawMaterial(@AuthenticationPrincipal AuthenticatedUser principal,
                                                           @PathVariable String requestId,
                                                           @Valid @RequestBody GovRequests.RevisionReason request,
                                                           @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                           HttpServletRequest http) {
        return ApiResponse.ok(governance.withdrawMaterial(principal, requestId, request, key, http.getRequestURI()));
    }

    @PostMapping("/member-changes/{changeId}/cancellations")
    public ApiResponse<GovViews.MemberChange> cancel(@AuthenticationPrincipal AuthenticatedUser principal,
                                                     @PathVariable String changeId,
                                                     @Valid @RequestBody GovRequests.RevisionReason request,
                                                     @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                     HttpServletRequest http) {
        return ApiResponse.ok(governance.cancel(principal, changeId, request, key, http.getRequestURI()));
    }

    @PostMapping("/member-changes/{changeId}/executions")
    public ApiResponse<GovViews.MemberChange> execute(@AuthenticationPrincipal AuthenticatedUser principal,
                                                      @PathVariable String changeId,
                                                      @Valid @RequestBody GovRequests.Execute request,
                                                      @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                      HttpServletRequest http) {
        return ApiResponse.ok(governance.execute(principal, changeId, request, key, http.getRequestURI()));
    }

    @PostMapping("/member-changes/{changeId}/handover-responses")
    public ApiResponse<GovViews.MemberChange> respond(@AuthenticationPrincipal AuthenticatedUser principal,
                                                      @PathVariable String changeId,
                                                      @Valid @RequestBody GovRequests.HandoverResponse request,
                                                      @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                      HttpServletRequest http) {
        return ApiResponse.ok(governance.respondHandover(principal, changeId, request, key, http.getRequestURI()));
    }

    @PostMapping("/member-changes/{changeId}/personnel-decisions")
    public ApiResponse<GovViews.MemberChange> decide(@AuthenticationPrincipal AuthenticatedUser principal,
                                                     @PathVariable String changeId,
                                                     @Valid @RequestBody GovRequests.PersonnelDecision request,
                                                     @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                     HttpServletRequest http) {
        return ApiResponse.ok(governance.decide(principal, changeId, request, key, http.getRequestURI()));
    }

    @PostMapping("/member-changes/{changeId}/target-cancellations")
    public ApiResponse<GovViews.MemberChange> cancelTarget(@AuthenticationPrincipal AuthenticatedUser principal,
                                                           @PathVariable String changeId,
                                                           @Valid @RequestBody GovRequests.RevisionReason request,
                                                           @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                           HttpServletRequest http) {
        return ApiResponse.ok(governance.cancelTarget(principal, changeId, request, key, http.getRequestURI()));
    }

    @PostMapping("/member-changes/{changeId}/target-invitations")
    public ApiResponse<GovViews.MemberChange> invite(@AuthenticationPrincipal AuthenticatedUser principal,
                                                     @PathVariable String changeId,
                                                     @Valid @RequestBody GovRequests.TargetInvite request,
                                                     @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                     HttpServletRequest http) {
        return ApiResponse.ok(governance.inviteTarget(principal, changeId, request, key, http.getRequestURI()));
    }

    @PostMapping("/member-changes/{changeId}/target-responses")
    public ApiResponse<GovViews.MemberChange> respondTarget(@AuthenticationPrincipal AuthenticatedUser principal,
                                                            @PathVariable String changeId,
                                                            @Valid @RequestBody GovRequests.TargetResponse request,
                                                            @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                            HttpServletRequest http) {
        return ApiResponse.ok(governance.respondTarget(principal, changeId, request, key, http.getRequestURI()));
    }

    @PostMapping("/personnel-cases/{caseId}/acceptances")
    public ApiResponse<GovViews.CaseView> acceptCase(@AuthenticationPrincipal AuthenticatedUser principal,
                                                     @PathVariable String caseId,
                                                     @Valid @RequestBody GovRequests.CaseRevision request,
                                                     @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                     HttpServletRequest http) {
        return ApiResponse.ok(governance.acceptCase(principal, caseId, request, key, http.getRequestURI()));
    }

    @PostMapping("/personnel-cases/{caseId}/closures")
    public ApiResponse<GovViews.CaseView> closeCase(@AuthenticationPrincipal AuthenticatedUser principal,
                                                    @PathVariable String caseId,
                                                    @Valid @RequestBody GovRequests.CloseCase request,
                                                    @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                    HttpServletRequest http) {
        return ApiResponse.ok(governance.closeCase(principal, caseId, request, key, http.getRequestURI()));
    }

    @PostMapping("/personnel-cases/{caseId}/handoffs")
    public ApiResponse<GovViews.Handoff> createHandoff(@AuthenticationPrincipal AuthenticatedUser principal,
                                                       @PathVariable String caseId,
                                                       @Valid @RequestBody GovRequests.CaseHandoff request,
                                                       @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                       HttpServletRequest http) {
        return ApiResponse.ok(governance.createHandoff(principal, caseId, request, key, http.getRequestURI()));
    }

    @PostMapping("/personnel-cases/{caseId}/material-requests")
    public ApiResponse<GovViews.Material> requestMaterial(@AuthenticationPrincipal AuthenticatedUser principal,
                                                          @PathVariable String caseId,
                                                          @Valid @RequestBody GovRequests.MaterialCreate request,
                                                          @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                          HttpServletRequest http) {
        return ApiResponse.ok(governance.requestMaterial(principal, caseId, request, key, http.getRequestURI()));
    }

    @PostMapping("/personnel-cases/{caseId}/recoveries")
    public ApiResponse<GovViews.CaseView> recover(@AuthenticationPrincipal AuthenticatedUser principal,
                                                  @PathVariable String caseId,
                                                  @Valid @RequestBody GovRequests.CloseCase request,
                                                  @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                  HttpServletRequest http) {
        return ApiResponse.ok(governance.recover(principal, caseId, request, key, http.getRequestURI()));
    }

    @PostMapping("/personnel-cases/{caseId}/updates")
    public ApiResponse<GovViews.CaseView> updateCase(@AuthenticationPrincipal AuthenticatedUser principal,
                                                     @PathVariable String caseId,
                                                     @Valid @RequestBody GovRequests.CaseUpdate request,
                                                     @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                     HttpServletRequest http) {
        return ApiResponse.ok(governance.updateCase(principal, caseId, request, key, http.getRequestURI()));
    }

    @PostMapping("/personnel-handoffs/{handoffId}/cancellations")
    public ApiResponse<GovViews.Handoff> cancelHandoff(@AuthenticationPrincipal AuthenticatedUser principal,
                                                       @PathVariable String handoffId,
                                                       @Valid @RequestBody GovRequests.RevisionReason request,
                                                       @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                       HttpServletRequest http) {
        return ApiResponse.ok(governance.cancelHandoff(principal, handoffId, request, key, http.getRequestURI()));
    }

    @PostMapping("/personnel-handoffs/{handoffId}/responses")
    public ApiResponse<GovViews.Handoff> respondHandoff(@AuthenticationPrincipal AuthenticatedUser principal,
                                                        @PathVariable String handoffId,
                                                        @Valid @RequestBody GovRequests.HandoffDecision request,
                                                        @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                        HttpServletRequest http) {
        return ApiResponse.ok(governance.respondHandoff(principal, handoffId, request, key, http.getRequestURI()));
    }

    @PostMapping("/projects/{projectId}/member-changes")
    public ApiResponse<GovViews.MemberChange> createChange(@AuthenticationPrincipal AuthenticatedUser principal,
                                                           @PathVariable String projectId,
                                                           @Valid @RequestBody GovRequests.CreateChange request,
                                                           @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                           HttpServletRequest http) {
        return ApiResponse.ok(governance.createChange(principal, projectId, request, key, http.getRequestURI()));
    }

    @PostMapping("/projects/{projectId}/removal-votes")
    public ApiResponse<GovViews.Vote> startVote(@AuthenticationPrincipal AuthenticatedUser principal,
                                                @PathVariable String projectId,
                                                @Valid @RequestBody GovRequests.StartVote request,
                                                @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                HttpServletRequest http) {
        return ApiResponse.ok(governance.startVote(principal, projectId, request, key, http.getRequestURI()));
    }

    @PostMapping("/removal-votes/{voteId}/cancellations")
    public ApiResponse<GovViews.Vote> cancelVote(@AuthenticationPrincipal AuthenticatedUser principal,
                                                 @PathVariable String voteId,
                                                 @Valid @RequestBody GovRequests.RevisionReason request,
                                                 @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                 HttpServletRequest http) {
        return ApiResponse.ok(governance.cancelVote(principal, voteId, request, key, http.getRequestURI()));
    }

    @PostMapping("/removal-votes/{voteId}/personnel-decisions")
    public ApiResponse<GovViews.Vote> decideVote(@AuthenticationPrincipal AuthenticatedUser principal,
                                                 @PathVariable String voteId,
                                                 @Valid @RequestBody GovRequests.PersonnelDecision request,
                                                 @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                 HttpServletRequest http) {
        return ApiResponse.ok(governance.decideVote(principal, voteId, request, key, http.getRequestURI()));
    }

    @PostMapping("/removal-votes/{voteId}/statements")
    public ApiResponse<GovViews.Vote> statement(@AuthenticationPrincipal AuthenticatedUser principal,
                                                @PathVariable String voteId,
                                                @Valid @RequestBody GovRequests.Statement request,
                                                @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                HttpServletRequest http) {
        return ApiResponse.ok(governance.statement(principal, voteId, request, key, http.getRequestURI()));
    }

    @PutMapping("/member-changes/{changeId}/handover-plan")
    public ApiResponse<GovViews.MemberChange> plan(@AuthenticationPrincipal AuthenticatedUser principal,
                                                   @PathVariable String changeId,
                                                   @Valid @RequestBody GovRequests.SavePlan request,
                                                   @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                   HttpServletRequest http) {
        return ApiResponse.ok(governance.savePlan(principal, changeId, request, key, http.getRequestURI()));
    }

    @PutMapping("/removal-votes/{voteId}/me/ballot")
    public ApiResponse<GovViews.Vote> ballot(@AuthenticationPrincipal AuthenticatedUser principal,
                                             @PathVariable String voteId,
                                             @Valid @RequestBody GovRequests.Ballot request,
                                             @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                             HttpServletRequest http) {
        return ApiResponse.ok(governance.ballot(principal, voteId, request, key, http.getRequestURI()));
    }
}
