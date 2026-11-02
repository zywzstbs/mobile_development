package cn.gulupai.server.merge;

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
public class MergeController {

    private final MergeService merges;

    public MergeController(MergeService merges) {
        this.merges = merges;
    }

    @GetMapping("/square-merge-proposals/{proposalId}")
    public ApiResponse<MergeViews.ProposalView> proposal(@AuthenticationPrincipal AuthenticatedUser principal,
                                                         @PathVariable String proposalId) {
        return ApiResponse.ok(merges.proposal(principal, proposalId));
    }

    @GetMapping("/squares/{squareId}/merge-proposals")
    public ApiResponse<ListEnvelope<MergeViews.ProposalView>> list(@AuthenticationPrincipal AuthenticatedUser principal,
                                                                   @PathVariable String squareId) {
        return ApiResponse.ok(ListEnvelope.full(merges.forSquare(principal, squareId)));
    }

    @PostMapping("/square-merge-proposals")
    public ApiResponse<MergeViews.ProposalView> create(@AuthenticationPrincipal AuthenticatedUser principal,
                                                       @Valid @RequestBody MergeRequests.Create request,
                                                       @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                       HttpServletRequest http) {
        return ApiResponse.ok(merges.create(principal, request, key, http.getRequestURI()));
    }

    @PostMapping("/square-merge-proposals/{proposalId}/cancellations")
    public ApiResponse<MergeViews.ProposalView> cancel(@AuthenticationPrincipal AuthenticatedUser principal,
                                                       @PathVariable String proposalId,
                                                       @Valid @RequestBody MergeRequests.Cancel request,
                                                       @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                       HttpServletRequest http) {
        return ApiResponse.ok(merges.cancel(principal, proposalId, request, key, http.getRequestURI()));
    }

    @PostMapping("/square-merge-proposals/{proposalId}/fact-checks")
    public ApiResponse<MergeViews.ProposalView> factCheck(@AuthenticationPrincipal AuthenticatedUser principal,
                                                          @PathVariable String proposalId,
                                                          @Valid @RequestBody MergeRequests.FactCheck request,
                                                          @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                          HttpServletRequest http) {
        return ApiResponse.ok(merges.factCheck(principal, proposalId, request, key, http.getRequestURI()));
    }

    @PostMapping("/square-merge-proposals/{proposalId}/executions")
    public ApiResponse<MergeViews.ProposalView> execute(@AuthenticationPrincipal AuthenticatedUser principal,
                                                        @PathVariable String proposalId,
                                                        @Valid @RequestBody MergeRequests.Execute request,
                                                        @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                        HttpServletRequest http) {
        return ApiResponse.ok(merges.execute(principal, proposalId, request, key, http.getRequestURI()));
    }

    @PutMapping("/square-merge-proposals/{proposalId}/me/ballots")
    public ApiResponse<MergeViews.ProposalView> ballot(@AuthenticationPrincipal AuthenticatedUser principal,
                                                       @PathVariable String proposalId,
                                                       @Valid @RequestBody MergeRequests.Ballot request,
                                                       @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                       HttpServletRequest http) {
        return ApiResponse.ok(merges.ballot(principal, proposalId, request, key, http.getRequestURI()));
    }
}
