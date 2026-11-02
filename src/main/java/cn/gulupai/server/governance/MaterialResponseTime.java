package cn.gulupai.server.governance;

/**
 * 材料版本提交时间的可信度与来源。
 *
 * <p>对外只有两条合法组合：EXACT + 非空 createdAt；UNKNOWN + 显式 null。
 * legacy_recorded_at 仅保留 V021 回填值的诊断证据，不向前端输出。
 */
public final class MaterialResponseTime {

    public static final String EXACT = "EXACT";
    public static final String UNKNOWN = "UNKNOWN";

    /** 新提交由服务器 Clock 产生。 */
    public static final String SERVER_SUBMISSION = "SERVER_SUBMISSION";
    /** 由受控恢复程序绑定可靠回执后恢复。 */
    public static final String RECOVERED_EVIDENCE = "RECOVERED_EVIDENCE";
    /** V021 用最后修改时间回填、无法证明是提交时间。 */
    public static final String LEGACY_UNKNOWN = "LEGACY_UNKNOWN";

    private MaterialResponseTime() {
    }

    public static boolean validConfidence(String confidence) {
        return EXACT.equals(confidence) || UNKNOWN.equals(confidence);
    }
}
