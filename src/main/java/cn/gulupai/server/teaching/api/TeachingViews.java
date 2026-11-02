package cn.gulupai.server.teaching.api;

import cn.gulupai.server.course.api.SourceReferenceView;
import cn.gulupai.server.course.domain.CourseSlot;

import java.time.Instant;
import java.util.List;

public final class TeachingViews {

    private TeachingViews() {
    }

    public record TeacherClaimView(
            String id,
            String squareId,
            String teacherId,
            String status,
            String message,
            List<SourceReferenceView> proofRefs,
            int revision,
            Instant createdAt
    ) {
        public TeacherClaimView {
            proofRefs = List.copyOf(proofRefs);
        }
    }

    public record GrantView(
            String id,
            String squareId,
            String studentId,
            String displayName,
            List<String> capabilities,
            String status,
            String grantedBy,
            Instant expiresAt,
            int revision,
            boolean active
    ) {
        public GrantView {
            capabilities = List.copyOf(capabilities);
        }
    }

    public record RelinquishmentView(
            String squareId,
            String teacherId,
            String status,
            int revision
    ) {
    }

    public record TeacherTransferView(
            String id,
            String squareId,
            String sourceTeacherId,
            String recipientTeacherId,
            String reason,
            String status,
            int squareRevision,
            int revision,
            Instant createdAt
    ) {
    }

    public record TeachingProgressCounts(int TODO, int IN_PROGRESS, int REVIEW_PENDING, int DONE) {
    }

    public record TeachingProgressView(
            String id,
            String projectId,
            String displayName,
            String assignmentId,
            String assignmentName,
            int memberCount,
            TeachingProgressCounts counts,
            Instant dueAt,
            String status
    ) {
    }

    public record TeachingWorkspaceView(
            String id,
            String squareId,
            String squareNo,
            String schoolId,
            String termId,
            String fullCourseName,
            String teacherName,
            List<CourseSlot> slots,
            String status,
            int revision,
            List<String> capabilities,
            String myMembershipStatus,
            String claimStatus
    ) {
        public TeachingWorkspaceView {
            slots = List.copyOf(slots);
            capabilities = List.copyOf(capabilities);
        }
    }

    public record TeachingWorkspacesView(
            List<TeachingWorkspaceView> items,
            String nextCursor,
            boolean hasMore,
            int total,
            List<TeacherTransferView> pendingTransfers
    ) {
        public TeachingWorkspacesView {
            items = List.copyOf(items);
            pendingTransfers = List.copyOf(pendingTransfers);
        }
    }
}
