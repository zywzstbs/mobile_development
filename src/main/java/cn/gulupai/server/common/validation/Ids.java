package cn.gulupai.server.common.validation;

import java.util.regex.Pattern;

/**
 * ID 语法工具；服务层路径参数/引用参数复用，避免只靠 DTO 注解。
 */
public final class Ids {

    private static final Pattern ALLOWED = Pattern.compile("[A-Za-z0-9_-]{1,120}");

    private Ids() {
    }

    public static boolean isValid(String value) {
        return value != null && ALLOWED.matcher(value).matches();
    }

    public static String requireValid(String value) {
        if (!isValid(value)) {
            throw new IllegalArgumentException("ID格式无效");
        }
        return value;
    }
}
