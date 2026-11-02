package cn.gulupai.server.delivery;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

public final class DeliveryViews {

    private DeliveryViews() {
    }

    public record PolicyView(String userId, String kind) {
    }

    public record PlanShareView(String userId, String points, String responsibility) {
    }

    public record PlanCriterionView(String id, String text) {
    }

    public record PlanUnitView(
            String id, String clientKey, String title, String points,
            List<PlanShareView> shares, List<PlanCriterionView> criteria
    ) {
    }

    public record PlanView(String ownerId, Instant dueAt, List<PlanUnitView> units, PolicyView reviewerPolicy) {
    }

    public record AckView(String userId, String decision, Instant at) {
    }

    public record AgreementView(
            String id, String taskId, int version, String status, PlanView plan,
            List<AckView> acknowledgements, int revision, Instant createdAt
    ) {
    }

    public record ConfirmationView(String userId, String decision, Instant at) {
    }

    public record OpinionView(
            String id, String authorId, String comment, String proposalText, String decision
    ) {
    }

    public record ProposalView(
            String id, String taskId, int baseAgreementVersion, List<Object> changes, String reason,
            List<String> affectedUserIds, List<ConfirmationView> confirmations, List<OpinionView> opinions,
            String status, int revision, boolean sourceValid, boolean canManage
    ) {
    }

    public record ArtifactView(
            String kind,
            @JsonInclude(JsonInclude.Include.NON_NULL) String url,
            @JsonInclude(JsonInclude.Include.NON_NULL) String fileId,
            String title
    ) {
    }

    public record DraftView(
            String id, String taskId, String userId, String summary, List<ArtifactView> artifacts,
            List<String> unitIds, String status, Instant savedAt, int revision
    ) {
    }

    public record CheckView(String unitId, String criterionId, boolean met) {
    }

    public record AcceptanceView(
            String id, String submissionId, String reviewerId, String kind, List<CheckView> checks,
            String reason, String decision, Instant acceptedAt
    ) {
    }

    public record CriterionSnapshotView(String id, String text, String unitId) {
    }

    public record SubmissionView(
            String id, String taskId, int sequence, String submittedBy, String summary,
            List<ArtifactView> artifacts, List<String> unitIds, Instant submittedAt, Instant dueSnapshot,
            List<CriterionSnapshotView> criteriaSnapshot, String status, int revision, Object acceptance
    ) {
    }

    public record ReviewResult(AcceptanceView acceptance, SubmissionView submission, String taskStatus, int taskRevision) {
    }

    public record InvitationView(
            String id, String taskId, String recipientId, String status, int arrangementVersion,
            Instant expiresAt, int revision
    ) {
    }

    public record ContributionView(
            String id, String unitId, String userId, String acceptanceId, String submissionId,
            String statement, Instant at
    ) {
    }
}
