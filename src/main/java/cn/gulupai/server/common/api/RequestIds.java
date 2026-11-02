package cn.gulupai.server.common.api;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 请求关联 ID 的读取入口。只用于日志/排障，不属于业务响应字段。
 */
public final class RequestIds {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    private static final Pattern ACCEPTED = Pattern.compile("[A-Za-z0-9._:-]{1,64}");
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private RequestIds() {
    }

    public static String newId() {
        return UUID.randomUUID().toString();
    }

    public static String normalize(String candidate) {
        if (candidate != null && ACCEPTED.matcher(candidate).matches()) {
            return candidate;
        }
        return newId();
    }

    public static String current() {
        return CURRENT.get();
    }

    static void set(String requestId) {
        CURRENT.set(requestId);
    }

    static void clear() {
        CURRENT.remove();
    }
}
