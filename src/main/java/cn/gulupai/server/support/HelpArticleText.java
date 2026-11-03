package cn.gulupai.server.support;

/**
 * 帮助步骤与兼容正文的共同上限。契约允许 20 条、每条 500 字；
 * body 只保存换行拼接结果，必须容得下这个结构，不能截断。
 */
public final class HelpArticleText {

    public static final int MAX_STEPS = 20;
    public static final int MAX_STEP_CHARS = 500;
    public static final int MAX_DERIVED_BODY = MAX_STEPS * MAX_STEP_CHARS + (MAX_STEPS - 1);

    private HelpArticleText() {
    }
}
