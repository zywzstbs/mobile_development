package cn.gulupai.server.platform.domain;

import java.util.List;

/**
 * 平台管理审计的显式事件分类。
 *
 * <p>只有这里登记过的 action + resourceType 组合才允许进入管理读取。未知组合默认不展示：
 * 启用 {@code PLATFORM_AUDIT_READ} 不等于同时具备工单、事项或普通业务数据的读取资格。
 * 读取侧只做投影与范围过滤，不删除、截断或改写 {@code audit_event} 中的原始记录。</p>
 *
 * <p>{@code reason} 默认不回原文。只有管理员自己填写的账号处置理由与超管授撤理由可以返回；
 * 教师资格核验、课程认领与助理授权撤销的理由可能含核验材料或人员依据，统一按
 * {@link ReasonVisibility#WITHHELD} 处理，返回空字符串。</p>
 */
public final class ManagementAuditEvent {

    /** 理由字段（reason）能否原样返回给管理端。 */
    public enum ReasonVisibility {
        VISIBLE,
        WITHHELD
    }

    /** 一条允许进入管理审计的事件类型。 */
    public record Entry(String storedAction, String resourceType, String mappedAction, String result,
                        ReasonVisibility reason) {
    }

    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String REJECTED = "REJECTED";

    private static final List<Entry> ENTRIES = List.of(
            new Entry("ACCOUNT_DISABLED", "USER", "ACCOUNT_DISABLED", SUCCEEDED, ReasonVisibility.VISIBLE),
            new Entry("ACCOUNT_RESTORED", "USER", "ACCOUNT_RESTORED", SUCCEEDED, ReasonVisibility.VISIBLE),
            new Entry("PASSWORD_RESET_REQUESTED", "USER", "PASSWORD_RESET_REQUESTED", SUCCEEDED,
                    ReasonVisibility.VISIBLE),
            new Entry("SUPER_ADMIN_GRANTED", "PLATFORM_ADMIN_GRANT", "SUPER_ADMIN_GRANTED", SUCCEEDED,
                    ReasonVisibility.VISIBLE),
            new Entry("SUPER_ADMIN_REVOKED", "PLATFORM_ADMIN_GRANT", "SUPER_ADMIN_REVOKED", SUCCEEDED,
                    ReasonVisibility.VISIBLE),
            new Entry("ADMIN_GRANT_REVOKED", "CLASS_GRANT", "ADMIN_GRANT_REVOKED", SUCCEEDED,
                    ReasonVisibility.WITHHELD),
            new Entry("CLASS_GRANT_REVOKE", "CLASS_GRANT", "ADMIN_GRANT_REVOKED", SUCCEEDED,
                    ReasonVisibility.WITHHELD),
            new Entry("REGISTRATION_APPROVE", "REGISTRATION", "TEACHER_APPLICATION_APPROVED", SUCCEEDED,
                    ReasonVisibility.WITHHELD),
            new Entry("REGISTRATION_REJECT", "REGISTRATION", "TEACHER_APPLICATION_REJECTED", REJECTED,
                    ReasonVisibility.WITHHELD),
            new Entry("TEACHER_APPLICATION_APPROVED", "REGISTRATION", "TEACHER_APPLICATION_APPROVED", SUCCEEDED,
                    ReasonVisibility.WITHHELD),
            new Entry("TEACHER_APPLICATION_REJECTED", "REGISTRATION", "TEACHER_APPLICATION_REJECTED", REJECTED,
                    ReasonVisibility.WITHHELD),
            new Entry("TEACHER_CLAIM_APPROVE", "TEACHER_CLAIM", "COURSE_CLAIM_APPROVED", SUCCEEDED,
                    ReasonVisibility.WITHHELD),
            new Entry("TEACHER_CLAIM_REJECT", "TEACHER_CLAIM", "COURSE_CLAIM_REJECTED", REJECTED,
                    ReasonVisibility.WITHHELD),
            new Entry("COURSE_CLAIM_APPROVED", "TEACHER_CLAIM", "COURSE_CLAIM_APPROVED", SUCCEEDED,
                    ReasonVisibility.WITHHELD),
            new Entry("COURSE_CLAIM_REJECTED", "TEACHER_CLAIM", "COURSE_CLAIM_REJECTED", REJECTED,
                    ReasonVisibility.WITHHELD));

    private ManagementAuditEvent() {
    }

    /** 全部分类；SQL 白名单与读取投影共用同一份数据，避免两处漂移。 */
    public static List<Entry> entries() {
        return ENTRIES;
    }

    /** 按库存 action 与资源类型查分类；未登记返回 null。 */
    public static Entry find(String storedAction, String resourceType) {
        if (storedAction == null || resourceType == null) {
            return null;
        }
        for (Entry entry : ENTRIES) {
            if (entry.storedAction().equals(storedAction) && entry.resourceType().equals(resourceType)) {
                return entry;
            }
        }
        return null;
    }

    /**
     * 明确映射结果：审核驳回动作是“决定为驳回”，仍是一次成功执行，契约里保留
     * {@code REJECTED}；其余登记事件一律 {@code SUCCEEDED}，不做“包含 REJECT”推断。
     */
    public static String result(String storedAction, String resourceType) {
        Entry entry = find(storedAction, resourceType);
        if (entry == null) {
            throw new IllegalStateException("未列入管理审计的事件不应进入投影: " + storedAction + "/" + resourceType);
        }
        return entry.result();
    }

    /** 该事件的理由能否原样返回。 */
    public static boolean reasonVisible(String storedAction, String resourceType) {
        Entry entry = find(storedAction, resourceType);
        return entry != null && entry.reason() == ReasonVisibility.VISIBLE;
    }

    /** 某资源类型下已登记的事件 action，用于按目标加载时同样套用白名单。 */
    public static List<String> actionsFor(String resourceType) {
        return ENTRIES.stream()
                .filter(entry -> entry.resourceType().equals(resourceType))
                .map(Entry::storedAction)
                .distinct()
                .toList();
    }
}
