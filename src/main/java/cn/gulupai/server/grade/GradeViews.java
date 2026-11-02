package cn.gulupai.server.grade;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

public final class GradeViews {

    private GradeViews() {
    }

    public record LinkView(String kind, String url, String title) {
    }

    public record SourceView(String type, String id, int version) {
    }

    public record GapView(String code, String section, String resourceId, String message) {
    }

    public record LedgerRow(
            String taskId,
            String taskTitle,
            String unitId,
            String unitTitle,
            String points,
            String acceptedPoints,
            boolean signed,
            boolean accepted,
            boolean contributionConfirmed,
            String acceptanceId,
            String acceptanceKind
    ) {
    }

    public record LedgerView(
            String assignmentId,
            String studentId,
            String responsibilityPoints,
            String acceptedPoints,
            String basisStatus,
            String sourceKey,
            List<LedgerRow> rows,
            List<GapView> gaps,
            Object settlement
    ) {
    }

    public record RuleView(
            String id,
            String assignmentId,
            int version,
            String processWeight,
            String description,
            String rounding,
            String confirmedBy,
            String confirmedAt,
            int revision
    ) {
    }

    public record ImpactView(int affectedStudentCount) {
    }

    public record RulePreviewView(
            String previewToken,
            String expiresAt,
            String assignmentId,
            RuleView rule,
            ImpactView impact
    ) {
    }

    public record PointsView(String responsibilityPoints, String acceptedPoints) {
    }

    public record CorrectionView(
            String id,
            String assignmentId,
            String studentId,
            String taskId,
            String unitId,
            PointsView correction,
            String reason,
            List<LinkView> evidenceRefs,
            String authorId,
            int revision,
            String createdAt
    ) {
    }

    public record SettlementView(
            String id,
            String assignmentId,
            String studentId,
            String sourceKey,
            int ruleVersion,
            String responsibilityPoints,
            String acceptedPoints,
            int b,
            String settledAt
    ) {
    }

    public record DraftView(
            String id,
            String assignmentId,
            String studentId,
            int q,
            int delta,
            String deltaReason,
            List<LinkView> evidenceRefs,
            String sourceKey,
            int ruleVersion,
            int revision
    ) {
    }

    public record CandidateView(
            String studentId,
            String studentName,
            String assignmentId,
            String classification,
            String basisDigest,
            List<String> blockers,
            Integer b,
            Integer q,
            Integer delta,
            Integer finalGrade,
            Object rule,
            Object ledger,
            Object draft,
            Object previous
    ) {
    }

    public record PublicationPreviewView(
            String previewToken,
            String expiresAt,
            List<CandidateView> rows,
            List<String> studentIds
    ) {
    }

    public record SnapshotView(
            String id,
            String publicationId,
            String assignmentId,
            String studentId,
            int version,
            int b,
            int q,
            int delta,
            int g,
            String basisDigest,
            Object ruleSnapshot,
            Object ledgerSnapshot,
            Object evidenceSnapshot,
            String createdAt
    ) {
    }

    public record PublicationView(
            String id,
            String assignmentId,
            String publishedAt,
            List<SnapshotView> grades
    ) {
    }

    public record ResponseView(
            int version,
            String authorId,
            String content,
            List<LinkView> evidenceRefs,
            String createdAt
    ) {
    }

    public record AppealView(
            String id,
            String gradeSnapshotId,
            String assignmentId,
            String studentId,
            String studentName,
            String issue,
            List<SourceView> sourceRefs,
            List<LinkView> evidenceRefs,
            String status,
            List<ResponseView> responses,
            String currentGradeSnapshotId,
            @JsonInclude(JsonInclude.Include.ALWAYS) String resolvedGradeSnapshotId,
            int revision,
            String createdAt
    ) {
    }

    public record ConsentView(String decision, int contentVersion) {
    }

    public record SummaryBody(
            String id,
            String assignmentId,
            String studentId,
            String summary,
            String sourceGradeSnapshotId,
            int version,
            String status,
            int revision,
            Object consent
    ) {
    }

    public record SummaryView(SummaryBody summary) {
    }
}
