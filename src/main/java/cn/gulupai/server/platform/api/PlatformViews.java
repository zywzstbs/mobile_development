package cn.gulupai.server.platform.api;

import java.time.Instant;
import java.util.List;

public final class PlatformViews {

    private PlatformViews() {
    }

    public record PageView<T>(List<T> items, boolean hasMore, String nextCursor, int total) {
        public PageView {
            items = List.copyOf(items);
        }
    }

    public record DashboardView(CountsView counts, List<RecentOperationView> recentOperations) {
        public DashboardView {
            recentOperations = List.copyOf(recentOperations);
        }
    }

    public record CountsView(int teacherApplicationsPending, int courseClaimsPending, int exceptionsPending) {
    }

    public record RecentOperationView(String id, String action, String targetType, String targetId,
                                      String summary, String result, Instant createdAt) {
    }

    public record AccountItem(String id, String displayName, String loginId, String schoolId,
                              String accountStatus, String identityStatus, List<String> roles,
                              List<String> platformCapabilities, String summary) {
        public AccountItem {
            roles = List.copyOf(roles);
            platformCapabilities = List.copyOf(platformCapabilities);
        }
    }

    /**
     * A03 详情里的账号投影。{@code restorationEligible} 是目标事实，不是操作权限：
     * 只表示该账号当前是否处于“管理停用、可由 A04 恢复”的状态；真正恢复仍需
     * {@code PLATFORM_ACCOUNT_MANAGE}、当前范围、正确版本、再认证及事务内复查。
     */
    public record AccountUser(String id, String displayName, String loginId, String schoolId,
                              String accountStatus, String identityStatus, List<String> roles,
                              List<String> platformCapabilities, String summary, boolean restorationEligible,
                              int revision) {
        public AccountUser {
            roles = List.copyOf(roles);
            platformCapabilities = List.copyOf(platformCapabilities);
        }
    }

    public record CourseGrantView(String squareId, String fullCourseName, List<String> capabilities,
                                  String status, Instant expiresAt) {
        public CourseGrantView {
            capabilities = List.copyOf(capabilities);
        }
    }

    public record TeachingCourseView(String squareId, String fullCourseName, String claimStatus) {
    }

    public record ManagementRecordView(String id, String action, String result, Instant createdAt) {
    }

    public record AccountDetail(AccountUser user, List<CourseGrantView> grants,
                                List<TeachingCourseView> teachingCourses,
                                List<ManagementRecordView> managementRecords) {
        public AccountDetail {
            grants = List.copyOf(grants);
            teachingCourses = List.copyOf(teachingCourses);
            managementRecords = List.copyOf(managementRecords);
        }
    }

    public record TeacherApplicationItem(String id, String applicantUserId, String applicantName,
                                         Instant submittedAt, String status, int revision, String schoolId) {
    }

    public record TeacherHistoryItem(String id, String action, String actorName, String note, Instant createdAt) {
    }

    public record TeacherApplicationDetail(String id, String applicantUserId, String applicantName,
                                           Instant submittedAt, String status, int revision, String schoolId,
                                           String teacherApplication, List<TeacherHistoryItem> history) {
        public TeacherApplicationDetail {
            history = List.copyOf(history);
        }
    }

    public record CourseItem(String squareId, String fullCourseName, String squareNo, String schoolId,
                             String termId, String currentTeacherId, String currentTeacherName,
                             String claimStatus, int pendingClaims) {
    }

    public record CourseCore(String squareId, String squareNo, String fullCourseName, String schoolId,
                             String termId, String currentTeacherId, String currentTeacherName) {
    }

    public record ClaimItem(String id, String teacherId, String teacherName, String message, String status,
                            Instant submittedAt, int revision) {
    }

    public record AssistantGrantItem(String grantId, String studentId, String studentName,
                                     List<String> capabilities, String status, Instant expiresAt,
                                     int revision, boolean active) {
        public AssistantGrantItem {
            capabilities = List.copyOf(capabilities);
        }
    }

    public record CourseDetail(CourseCore course, List<ClaimItem> claims, List<AssistantGrantItem> grants) {
        public CourseDetail {
            claims = List.copyOf(claims);
            grants = List.copyOf(grants);
        }
    }

    public record AuditItem(String id, String actorId, String actorName, String actorRole, String action,
                            String targetType, String targetId, String scope, String reason, String result,
                            String requestId, Instant createdAt) {
    }

    public record SuperAdminItem(String userId, String displayName, String status, Instant grantedAt,
                                 String grantedBy, int revision) {
    }

    public record SuperAdminList(List<SuperAdminItem> items) {
        public SuperAdminList {
            items = List.copyOf(items);
        }
    }

    public record ReauthenticationView(String reauthToken, Instant expiresAt) {
        @Override
        public String toString() {
            return "ReauthenticationView[expiresAt=" + expiresAt + "]";
        }
    }

    public record OperationView(String id, String action, String targetType, String targetId, String status,
                                int resultingRevision, String auditId) {
    }

    public record ResourceView(String id, String status, int revision) {
    }

    public record AdminOperationReceipt(OperationView operation, ResourceView resource) {
    }
}
