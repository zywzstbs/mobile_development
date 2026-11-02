package cn.gulupai.server.role.api;

import cn.gulupai.server.team.api.TeamViews;

import java.time.Instant;
import java.util.List;

public final class RoleViews {

    private RoleViews() {
    }

    public record RoleSnapshot(String id, String roleName, String duty, int total, int filled, int reserved) {
    }

    public record Party(String userId, String displayName, String membershipId, int membershipRevision,
                        Object originalRole, Object targetRole) {
    }

    public record Confirmation(String userId, String decision, Instant at) {
    }

    public record Plan(String id, String projectId, String projectName, String authorId, String reason,
                       List<Party> parties, List<Confirmation> confirmations, String status,
                       int revision, Instant createdAt) {
    }

    public record PlanView(Plan plan, int projectRevision, String availability, boolean myConfirmed,
                           boolean canConfirm, boolean canDecline, boolean canWithdraw,
                           boolean canOpenProject) {
    }

    public record Proposal(String id, String projectId, String projectName, String authorId,
                           String recipientId, String membershipId, Object targetRole, String message,
                           String status, int revision, Instant createdAt) {
    }

    public record ProposalView(Proposal proposal, int projectRevision, String availability,
                               Object currentRole, boolean canAccept, boolean canDecline,
                               boolean canWithdraw, boolean canOpenProject) {
    }

    public record Swap(String id, String projectId, String projectName, String authorId, String reason,
                       List<Party> parties, List<Confirmation> confirmations, String status,
                       int revision, Instant createdAt) {
    }

    public record SwapView(Swap swap, int projectRevision, String availability, boolean myConfirmed,
                           boolean canConfirm, boolean canDecline, boolean canWithdraw,
                           boolean canOpenProject) {
    }

    public record Blocker(String code, String message) {
    }

    public record RoleSlotView(TeamViews.ProjectView project, List<TeamViews.RoleView> roles,
                               List<TeamViews.MembershipView> members, List<Blocker> conversionBlockers) {
    }
}
