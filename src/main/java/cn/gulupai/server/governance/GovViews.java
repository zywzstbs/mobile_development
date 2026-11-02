package cn.gulupai.server.governance;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

public final class GovViews {

    private GovViews() {
    }

    public record HistoryItem(String action, String actorId, Instant at, String reason) {
    }

    public record Outstanding(String id, String title, int revision) {
    }

    public record HandoverItem(String id, String kind, String taskId, Integer sourceTaskRevision,
                               String recipientId, String recipientName, String responsibility, boolean accepted) {
    }

    public record HandoverPlan(int version, List<HandoverItem> items, String nextLeaderId, String notes) {
    }

    public record MemberChange(
            String id, String projectId, String sourceProjectId, String squareId, String assignmentId,
            String targetUserId, String kind, String reason, String status, String targetProjectId,
            int planVersion, Object handoverPlan, Object personnelDecision, List<HistoryItem> history,
            int revision, Instant createdAt, String initiatorId, boolean planValid, boolean canDecide,
            boolean canManage, List<Outstanding> outstandingTasks, Object targetInvitation, boolean targetValid,
            boolean canInviteTarget, boolean canCancelTarget, boolean canRespondTarget, String targetProjectName
    ) {
    }

    public record Vote(
            String id, String projectId, String targetUserId, String status, String facts, String statement,
            Instant statementEndsAt, Instant votingEndsAt, List<String> electorate, int agree, int disagree,
            int abstain, int revision, String memberChangeId
    ) {
    }

    public record MaterialResponseItem(
            int version, String authorId, String content, List<Object> evidenceRefs, Instant createdAt,
            String createdAtConfidence, boolean withdrawn
    ) {
    }

    public record MaterialReviewItem(
            String reviewerId, String decision, int responseVersion, List<Object> unitResults,
            String reason, Instant createdAt
    ) {
    }

    public record Material(
            String id, String caseId, String recipientId, String recipientName, String caseTitle, String status,
            String requirements, Instant dueAt, int responseVersion, List<MaterialResponseItem> responses,
            List<MaterialReviewItem> reviews, Object response, boolean canRespond, boolean canReview,
            boolean canWithdraw, int revision
    ) {
    }

    public record CaseView(
            String id, String squareId, String projectId, String subjectUserId, String assigneeId, String title,
            String status, int revision, List<Material> materials,
            @JsonInclude(JsonInclude.Include.NON_NULL) List<Object> staffRecords
    ) {
    }

    public record Handoff(
            String id, String caseId, String fromUserId, String toUserId, String sourceUserId,
            String recipientTeacherId, String status, int sourceCaseRevision, String facts,
            String unresolvedQuestions, String decisionsNeeded, String sourceName, String recipientName,
            boolean sourceValid, boolean canRespond, boolean canCancel, int revision
    ) {
    }

    public record Person(String userId, String displayName) {
    }
}
