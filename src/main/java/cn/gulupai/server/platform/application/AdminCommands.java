package cn.gulupai.server.platform.application;

import cn.gulupai.server.common.api.ApiCode;
import cn.gulupai.server.common.api.ApiException;
import cn.gulupai.server.platform.api.PlatformRequests;
import cn.gulupai.server.platform.domain.PlatformAction;

import java.util.Objects;

/**
 * 把 P66 写入和再认证正文收成同一组业务字段。摘要与幂等指纹都只用这份结果。
 */
public final class AdminCommands {

    private AdminCommands() {
    }

    public record CanonicalCommand(PlatformAction action, String targetId, Integer expectedRevision,
                                   String precondition, String decision, String status, String reason,
                                   String note, String targetUserId) {
    }

    public static CanonicalCommand statusChange(String userId, PlatformRequests.StatusChangeRequest request) {
        String status = AdminTexts.blankToNull(request.status());
        if (!"ACTIVE".equals(status) && !"DISABLED".equals(status)) {
            throw new ApiException(ApiCode.VALIDATION_FAILED, "status 只能是 ACTIVE 或 DISABLED");
        }
        return new CanonicalCommand(PlatformAction.ACCOUNT_STATUS_CHANGE, AdminTexts.requireId(userId, "userId"),
                AdminTexts.revision(request.expectedRevision(), "expectedRevision", true),
                null, null, status, AdminTexts.requiredText(request.reason(), "reason"), null, null);
    }

    public static CanonicalCommand passwordReset(String userId, PlatformRequests.PasswordResetRequest request) {
        return new CanonicalCommand(PlatformAction.PASSWORD_RESET_REQUEST, AdminTexts.requireId(userId, "userId"),
                null, PlatformAction.PASSWORD_RESET_REQUEST.precondition(), null, null,
                AdminTexts.requiredText(request.reason(), "reason"), null, null);
    }

    public static CanonicalCommand teacherDecision(String applicationId, PlatformRequests.DecisionRequest request) {
        return decision(PlatformAction.TEACHER_APPLICATION_DECIDE, applicationId, "applicationId", request);
    }

    public static CanonicalCommand claimDecision(String claimId, PlatformRequests.DecisionRequest request) {
        return decision(PlatformAction.COURSE_CLAIM_DECIDE, claimId, "claimId", request);
    }

    public static CanonicalCommand grantRevocation(String grantId, PlatformRequests.RevocationRequest request) {
        return new CanonicalCommand(PlatformAction.ADMIN_GRANT_REVOKE, AdminTexts.requireId(grantId, "grantId"),
                AdminTexts.revision(request.expectedRevision(), "expectedRevision", true),
                null, null, null, AdminTexts.requiredText(request.reason(), "reason"), null, null);
    }

    public static CanonicalCommand superAdminGrant(PlatformRequests.SuperAdminGrantRequest request) {
        String targetUserId = AdminTexts.requireId(request.targetUserId(), "targetUserId");
        return new CanonicalCommand(PlatformAction.SUPER_ADMIN_GRANT, targetUserId, null,
                PlatformAction.SUPER_ADMIN_GRANT.precondition(), null, null,
                AdminTexts.requiredText(request.reason(), "reason"), null, targetUserId);
    }

    public static CanonicalCommand superAdminRevocation(String userId, PlatformRequests.RevocationRequest request) {
        return new CanonicalCommand(PlatformAction.SUPER_ADMIN_REVOKE, AdminTexts.requireId(userId, "userId"),
                AdminTexts.revision(request.expectedRevision(), "expectedRevision", true),
                null, null, null, AdminTexts.requiredText(request.reason(), "reason"), null, null);
    }

    public static CanonicalCommand reauthentication(PlatformRequests.ReauthenticationRequest request) {
        PlatformAction action = PlatformAction.parse(request.action());
        if (action == null || request.targetType() == null || !action.targetType().equals(request.targetType().strip())) {
            throw new ApiException(ApiCode.VALIDATION_FAILED, "action 或 targetType 无效");
        }
        String targetId = AdminTexts.requireId(request.targetId(), "targetId");
        AdminTexts.requireId(request.operationId(), "operationId");
        Integer revision = null;
        if (action.revisionRequired()) {
            revision = AdminTexts.revision(request.expectedRevision(), "expectedRevision", true);
        } else if (request.expectedRevision() != null) {
            throw new ApiException(ApiCode.VALIDATION_FAILED, "该操作没有版本，不能填写 expectedRevision");
        }
        String precondition = AdminTexts.blankToNull(request.precondition());
        if (!Objects.equals(precondition, action.precondition())) {
            throw new ApiException(ApiCode.REAUTH_CONTEXT_MISMATCH, "前置条件与操作类型不一致");
        }
        return switch (action) {
            case ACCOUNT_STATUS_CHANGE -> {
                String status = AdminTexts.blankToNull(request.status());
                if (!"ACTIVE".equals(status) && !"DISABLED".equals(status)) {
                    throw new ApiException(ApiCode.VALIDATION_FAILED, "status 只能是 ACTIVE 或 DISABLED");
                }
                yield new CanonicalCommand(action, targetId, revision, null, null, status,
                        AdminTexts.requiredText(request.reason(), "reason"), null, null);
            }
            case PASSWORD_RESET_REQUEST -> new CanonicalCommand(action, targetId, null, action.precondition(),
                    null, null, AdminTexts.requiredText(request.reason(), "reason"), null, null);
            case TEACHER_APPLICATION_DECIDE, COURSE_CLAIM_DECIDE -> {
                String decision = AdminTexts.blankToNull(request.decision());
                if (!"APPROVE".equals(decision) && !"REJECT".equals(decision)) {
                    throw new ApiException(ApiCode.VALIDATION_FAILED, "decision 只能是 APPROVE 或 REJECT");
                }
                yield new CanonicalCommand(action, targetId, revision, null, decision, null, null,
                        AdminTexts.requiredText(request.note(), "note"), null);
            }
            case ADMIN_GRANT_REVOKE, SUPER_ADMIN_REVOKE -> new CanonicalCommand(action, targetId, revision, null,
                    null, null, AdminTexts.requiredText(request.reason(), "reason"), null, null);
            case SUPER_ADMIN_GRANT -> {
                String targetUserId = AdminTexts.requireId(request.targetUserId(), "targetUserId");
                if (!targetUserId.equals(targetId)) {
                    throw new ApiException(ApiCode.REAUTH_CONTEXT_MISMATCH, "增设目标与操作目标不一致");
                }
                yield new CanonicalCommand(action, targetId, null, action.precondition(), null, null,
                        AdminTexts.requiredText(request.reason(), "reason"), null, targetUserId);
            }
        };
    }

    public static PlatformRequests.BusinessFingerprint fingerprint(String operationId, CanonicalCommand command) {
        return new PlatformRequests.BusinessFingerprint(operationId, command.action().name(),
                command.action().targetType(), command.targetId(), command.expectedRevision(),
                command.precondition(), command.decision(), command.status(), command.reason(),
                command.note(), command.targetUserId());
    }

    private static CanonicalCommand decision(PlatformAction action, String id, String field,
                                             PlatformRequests.DecisionRequest request) {
        String decision = AdminTexts.blankToNull(request.decision());
        if (!"APPROVE".equals(decision) && !"REJECT".equals(decision)) {
            throw new ApiException(ApiCode.VALIDATION_FAILED, "decision 只能是 APPROVE 或 REJECT");
        }
        return new CanonicalCommand(action, AdminTexts.requireId(id, field),
                AdminTexts.revision(request.expectedRevision(), "expectedRevision", true),
                null, decision, null, null, AdminTexts.requiredText(request.note(), "note"), null);
    }
}
