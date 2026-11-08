package cn.gulupai.server.platform.application;

import cn.gulupai.server.common.api.ApiCode;
import cn.gulupai.server.common.api.ApiException;
import cn.gulupai.server.common.api.FieldErrorDetail;

import java.util.List;
import java.util.regex.Pattern;

public final class AdminTexts {

    public static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,120}");
    public static final int REASON_MAX = 500;
    public static final int QUERY_MAX = 40;
    public static final int LIMIT_MAX = 50;

    private AdminTexts() {
    }

    public static String requireId(String value, String field) {
        if (value == null || !ID.matcher(value).matches()) {
            throw fields(field, "INVALID_FORMAT", "格式不正确");
        }
        return value;
    }

    public static String optionalId(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return requireId(value, field);
    }

    public static String requiredText(String raw, String field) {
        String value = raw == null ? "" : raw.strip();
        if (value.isEmpty()) {
            throw fields(field, "REQUIRED", "请填写此项");
        }
        if (value.length() > REASON_MAX) {
            throw fields(field, "TOO_LONG", "超出长度限制");
        }
        return value;
    }

    public static int revision(Integer value, String field, boolean required) {
        if (value == null) {
            if (required) {
                throw fields(field, "REQUIRED", "请填写此项");
            }
            return 0;
        }
        if (value < 1 || value > 999_999_999) {
            throw fields(field, "OUT_OF_RANGE", "超出允许范围");
        }
        return value;
    }

    public static int limit(Integer value) {
        int limit = value == null ? 20 : value;
        if (limit < 1 || limit > LIMIT_MAX) {
            throw fields("limit", "OUT_OF_RANGE", "超出允许范围");
        }
        return limit;
    }

    public static String query(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String value = raw.strip();
        if (value.length() > QUERY_MAX) {
            throw fields("query", "TOO_LONG", "超出长度限制");
        }
        return value;
    }

    public static String blankToNull(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.strip();
        return value.isEmpty() ? null : value;
    }

    private static ApiException fields(String field, String code, String message) {
        return new ApiException(ApiCode.VALIDATION_FAILED, "请检查填写内容",
                List.of(new FieldErrorDetail(field, code, message)));
    }
}
