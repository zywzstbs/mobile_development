package cn.gulupai.server.merge;

import java.time.Instant;
import java.util.List;

public final class MergeViews {

    private MergeViews() {
    }

    public record LinkView(String kind, String url, String title) {
    }

    public record BallotView(String choice, int revision, String squareId, Instant updatedAt) {
    }

    public record SideView(
            String squareId, int eligibleCount, int agreeCount, int disagree, int abstain, int unvoted,
            boolean passed, int requiredCount, Object myBallot, boolean canVote
    ) {
    }

    public record AssignmentView(
            String id, String squareId, String name, String requirements, String origin,
            String confirmationStatus, Instant hardDueAt, int minMembers, int maxMembers,
            boolean gradingEnabled, int revision, List<LinkView> sourceReferences
    ) {
    }

    public record ProposalView(
            String id, String sourceSquareId, String targetSquareId, String reason, List<LinkView> evidenceRefs,
            String status, List<SideView> sides, List<AssignmentView> sourceAssignments,
            List<Object> assignmentMappings, boolean canCheck, boolean canExecute, int revision
    ) {
    }
}
