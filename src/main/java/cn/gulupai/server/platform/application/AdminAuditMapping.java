package cn.gulupai.server.platform.application;

import cn.gulupai.server.platform.domain.ManagementAuditEvent;

/**
 * 历史 CLI 审计保持原 action 入库。读取时映射到 P66 文案，不把 cli 改写成某个超管。
 *
 * <p>管理读取只投影 {@link ManagementAuditEvent} 登记过的事件，映射与结果也以该分类为准，
 * 不再用“action 里含 REJECT”之类的前缀/包含推断。</p>
 */
public final class AdminAuditMapping {

    private AdminAuditMapping() {
    }

    public static String action(String stored, String resourceType) {
        ManagementAuditEvent.Entry entry = ManagementAuditEvent.find(stored, resourceType);
        if (entry != null) {
            return entry.mappedAction();
        }
        if (stored == null || stored.isBlank()) {
            return "UNKNOWN";
        }
        return switch (stored) {
            case "REGISTRATION_APPROVE" -> "TEACHER_APPLICATION_APPROVED";
            case "REGISTRATION_REJECT" -> "TEACHER_APPLICATION_REJECTED";
            case "TEACHER_CLAIM_APPROVE" -> "COURSE_CLAIM_APPROVED";
            case "TEACHER_CLAIM_REJECT" -> "COURSE_CLAIM_REJECTED";
            case "CLASS_GRANT_REVOKE" -> "ADMIN_GRANT_REVOKED";
            default -> stored;
        };
    }

    public static String result(String stored, String resourceType) {
        return ManagementAuditEvent.result(stored, resourceType);
    }

    public static String targetType(String resourceType) {
        if (resourceType == null || resourceType.isBlank()) {
            return "UNKNOWN";
        }
        return switch (resourceType) {
            case "REGISTRATION" -> "TEACHER_APPLICATION";
            case "TEACHER_CLAIM" -> "COURSE_CLAIM";
            case "CLASS_GRANT" -> "ADMIN_GRANT";
            case "PLATFORM_ADMIN_GRANT" -> "SUPER_ADMIN";
            case "USER" -> "USER";
            default -> resourceType.length() <= 80 ? resourceType : "UNKNOWN";
        };
    }

    public static String summary(String mappedAction) {
        return switch (mappedAction) {
            case "TEACHER_APPLICATION_APPROVED" -> "教师资格核验通过";
            case "TEACHER_APPLICATION_REJECTED" -> "教师资格核验驳回";
            case "COURSE_CLAIM_APPROVED" -> "课程认领通过";
            case "COURSE_CLAIM_REJECTED" -> "课程认领驳回";
            case "ADMIN_GRANT_REVOKED" -> "助理授权已撤销";
            case "ACCOUNT_DISABLED" -> "账号已停用";
            case "ACCOUNT_RESTORED" -> "账号已恢复";
            case "PASSWORD_RESET_REQUESTED" -> "已受理密码重置请求";
            case "SUPER_ADMIN_GRANTED" -> "已授予超级管理员";
            case "SUPER_ADMIN_REVOKED" -> "已撤销超级管理员";
            default -> "管理操作";
        };
    }

    public static String roleLabel(String actorId, String roles) {
        if (actorId == null || actorId.isBlank() || "cli".equals(actorId)) {
            return "CLI";
        }
        if (roles == null || roles.isBlank()) {
            return "UNKNOWN";
        }
        if (roles.contains("SUPER_ADMIN")) {
            return "SUPER_ADMIN";
        }
        return roles.split(",")[0];
    }
}
