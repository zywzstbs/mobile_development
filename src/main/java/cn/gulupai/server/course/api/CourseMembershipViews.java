package cn.gulupai.server.course.api;

import cn.gulupai.server.course.domain.CourseSlot;

import java.time.Instant;
import java.util.List;

public final class CourseMembershipViews {

    private CourseMembershipViews() {
    }

    public record CourseCodeView(
            String id,
            String squareId,
            String code,
            String issuedBy,
            Instant expiresAt,
            String status,
            int revision
    ) {
    }

    public record JoinCodeSquareView(
            String id,
            String squareNo,
            String schoolId,
            String termId,
            String fullCourseName,
            String teacherName,
            List<CourseSlot> slots,
            int revision
    ) {
        public JoinCodeSquareView {
            slots = List.copyOf(slots);
        }
    }

    public record CodeResolutionView(
            JoinCodeSquareView square,
            String membershipStatus,
            String verificationStatus,
            boolean canConfirm,
            String existingRequestId,
            String confirmationToken
    ) {
    }

    public record MembershipConfirmationView(
            String squareId,
            String userId,
            String membershipStatus,
            int revision
    ) {
    }

    public record JoinRequestView(
            String id,
            String squareId,
            String requesterId,
            String status,
            String message,
            List<SourceReferenceView> proofRefs,
            String reason,
            int revision,
            Instant createdAt
    ) {
        public JoinRequestView {
            proofRefs = List.copyOf(proofRefs);
        }
    }

    public record MembershipView(
            String squareId,
            String userId,
            String status,
            int revision
    ) {
    }

    public record JoinRequestDecisionView(
            JoinRequestView request,
            MembershipView membership
    ) {
    }
}
