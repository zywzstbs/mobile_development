package cn.gulupai.server.platform.api;

/**
 * P66 当前写入字段。operationId 放在 X-Operation-Id，不进入正文，避免和幂等键混用。
 * toString 不包含口令或再认证令牌。
 */
public final class PlatformRequests {

    private PlatformRequests() {
    }

    public record StatusChangeRequest(String status, String reason, Integer expectedRevision, String reauthToken) {
        @Override
        public String toString() {
            return "StatusChangeRequest[status=" + status + ", expectedRevision=" + expectedRevision + "]";
        }
    }

    public record PasswordResetRequest(String reason, String reauthToken) {
        @Override
        public String toString() {
            return "PasswordResetRequest";
        }
    }

    public record DecisionRequest(String decision, String note, Integer expectedRevision, String reauthToken) {
        @Override
        public String toString() {
            return "DecisionRequest[decision=" + decision + ", expectedRevision=" + expectedRevision + "]";
        }
    }

    public record RevocationRequest(String reason, Integer expectedRevision, String reauthToken) {
        @Override
        public String toString() {
            return "RevocationRequest[expectedRevision=" + expectedRevision + "]";
        }
    }

    public record SuperAdminGrantRequest(String targetUserId, String reason, String reauthToken) {
        @Override
        public String toString() {
            return "SuperAdminGrantRequest[targetUserId=" + targetUserId + "]";
        }
    }

    public record ReauthenticationRequest(String password, String operationId, String action, String targetType,
                                          String targetId, Integer expectedRevision, String precondition,
                                          String decision, String reason, String note, String status,
                                          String targetUserId, String payloadDigest) {
        @Override
        public String toString() {
            return "ReauthenticationRequest[action=" + action + ", targetType=" + targetType
                    + ", targetId=" + targetId + ", operationId=" + operationId + "]";
        }
    }

    public record BusinessFingerprint(String operationId, String action, String targetType, String targetId,
                                      Integer expectedRevision, String precondition, String decision,
                                      String status, String reason, String note, String targetUserId) {
    }
}
