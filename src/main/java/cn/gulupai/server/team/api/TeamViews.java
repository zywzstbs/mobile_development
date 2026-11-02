package cn.gulupai.server.team.api;

import java.time.Instant;
import java.util.List;

public final class TeamViews {

    private TeamViews() {
    }

    public record RoleView(String id, String clientKey, String roleName, String duty,
                           int total, int filled, int reserved) {
    }

    public record RecruitmentSettings(boolean open, String description, String professionalDirection,
                                      int weeklyHours, boolean acceptBeginners) {
    }

    public record StageView(String id, String name) {
    }

    public record MembershipView(String id, String membershipId, String projectId, String userId,
                                 String displayName, String roleSlotId, String projectRole,
                                 String status, String color, int colorRevision,
                                 Instant joinedAt, int revision) {
    }

    public record ProjectView(
            String id,
            String projectId,
            String squareId,
            String squareNo,
            String courseName,
            String assignmentId,
            String assignmentName,
            String mode,
            String name,
            String displayName,
            String intro,
            int capacity,
            String ownerId,
            String leaderName,
            String status,
            Instant dueAt,
            String discussionTime,
            RecruitmentSettings recruitment,
            List<RoleView> roles,
            List<StageView> stages,
            int revision,
            int memberCount,
            int reservedCount,
            String myProjectRole,
            MembershipView myMembership,
            boolean historical,
            int closingCycle,
            boolean canApply,
            boolean eligibleForDiscovery,
            String ineligibleReason,
            int availableSeats,
            String myApplicationStatus,
            String myInvitationId
    ) {
        public ProjectView {
            roles = List.copyOf(roles);
            stages = List.copyOf(stages);
        }
    }

    public record Application(String id, String projectId, String applicantId, String roleSlotId,
                              String message, String status, String invitationId, String reason,
                              int revision, Instant createdAt) {
    }

    public record ApplicantProfile(String userId, String displayName, List<String> skills) {
        public ApplicantProfile {
            skills = List.copyOf(skills);
        }
    }

    public record ApplicationView(Application application, ProjectView project,
                                  ApplicantProfile applicantProfile, String invitation,
                                  String nextAction) {
    }

    public record Invitation(String id, String projectId, String recipientId, String roleSlotId,
                             String message, String status, Instant expiresAt, String reason,
                             int revision, Instant createdAt) {
    }

    public record InvitationView(Invitation invitation, ProjectView project, boolean canRespond,
                                 MembershipView membership) {
    }

    public record InviteCandidateView(String userId, String displayName, String userNo,
                                      List<String> skills, String source, String applicationId,
                                      boolean eligible, String reasonCode, String reason) {
        public InviteCandidateView {
            skills = List.copyOf(skills);
        }
    }

    public record InviteCandidatesView(List<InviteCandidateView> items, String nextCursor,
                                       boolean hasMore, int total, int projectRevision) {
        public InviteCandidatesView {
            items = List.copyOf(items);
        }
    }

    public record ProjectCodeView(String projectId, String projectName, String code,
                                  boolean canManage, boolean canActivate, boolean enabled,
                                  int revision) {
    }

    public record CodeResolutionView(String projectId, ProjectView recruitment,
                                     boolean canApply, String reasonCode) {
    }

    public record TodoView(String id, String type, String resourceType, String resourceId,
                           String projectId, String assignmentId, String title, String state,
                           Instant updatedAt, int revision) {
    }

    public record WorkEvidence(String projectId, int acceptedDeliveries, int confirmedUnits,
                               int independentReviews, int onTimeDeliveries, int lateDeliveries) {
    }

    /** 顶层 projectId 保留为 P50 兼容附加字段；标准字段在 myEvidence.projectId。 */
    public record WorkSourceProjectView(String id, String displayName, String projectId,
                                        WorkEvidence myEvidence) {
    }

    public record DashboardView(int actionCount, int waitingCount, int pendingApplicationCount,
                                int pendingInvitationCount, int activeProjectCount,
                                List<TodoView> items) {
        public DashboardView {
            items = List.copyOf(items);
        }
    }
}
