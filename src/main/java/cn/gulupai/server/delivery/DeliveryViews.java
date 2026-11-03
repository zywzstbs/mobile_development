package cn.gulupai.server.delivery;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

public final class DeliveryViews {

    private DeliveryViews() {
    }

    public record PolicyView(
            String userId, String kind,
            @JsonInclude(JsonInclude.Include.NON_NULL) String displayName
    ) {
    }

    public record PlanCriterionView(String id, String text) {
    }

    /**
     * 正式约定响应里的份额。accepted、displayName 缺失时保持空，避免旧回执被反序列化成 false。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AgreementShareView(
            String userId, String points, String responsibility, Boolean accepted, String displayName
    ) {
    }

    /**
     * 正式约定响应的分项。和计划输入分开，避免只读状态写进提交计划。
     * 旧回执没有的状态、版本、验收保持空，不补 0 或 false。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AgreementUnitView(
            String id, String clientKey, String title, String points,
            List<AgreementShareView> shares, List<PlanCriterionView> criteria,
            Instant dueAt, Integer revision, String status, Object acceptance,
            List<ContributionConfirmationView> contributionConfirmations
    ) {
    }

    public record ContributionConfirmationView(
            String acceptanceId, Instant createdAt, String statement, String submissionId, String userId
    ) {
    }

    public record AckView(String userId, String decision, Instant at) {
    }

    public record AgreementView(
            String id, String taskId, int version, String status,
            String ownerId, Instant dueAt, List<AgreementUnitView> units, PolicyView reviewerPolicy,
            List<String> affectedUserIds, String totalPoints, String ownerName,
            boolean confirmationSourceValid, List<AckView> acknowledgements, int revision, Instant createdAt,
            @JsonInclude(JsonInclude.Include.NON_NULL) String memberChangeId,
            @JsonInclude(JsonInclude.Include.NON_NULL) String renewalReason,
            @JsonInclude(JsonInclude.Include.NON_NULL) Boolean reviewerAcceptanceInherited
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
            String reason, String decision, Instant acceptedAt, int revision
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
