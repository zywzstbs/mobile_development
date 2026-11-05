package cn.gulupai.server.common.api;

import java.util.List;

/**
 * 可预期的业务异常。message 直接面向用户，不包含堆栈、SQL 或私人内容。
 */
public class ApiException extends RuntimeException {

    private final ApiCode code;
    private final List<FieldErrorDetail> fieldErrors;

    public ApiException(ApiCode code) {
        super(code.defaultMessage());
        this.code = code;
        this.fieldErrors = List.of();
    }

    public ApiException(ApiCode code, String message) {
        super(message);
        this.code = code;
        this.fieldErrors = List.of();
    }

    public ApiException(ApiCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.fieldErrors = List.of();
    }

    public ApiException(ApiCode code, String message, List<FieldErrorDetail> fieldErrors) {
        super(message);
        this.code = code;
        this.fieldErrors = fieldErrors == null ? List.of() : List.copyOf(fieldErrors);
    }

    public ApiCode code() {
        return code;
    }

    public List<FieldErrorDetail> fieldErrors() {
        return fieldErrors;
    }

    public static ApiException notFound() {
        return new ApiException(ApiCode.NOT_FOUND);
    }

    public static ApiException validation(String message) {
        return new ApiException(ApiCode.VALIDATION_FAILED, message);
    }

    public static ApiException fields(List<FieldErrorDetail> fieldErrors) {
        return new ApiException(ApiCode.VALIDATION_FAILED, "请检查填写内容", fieldErrors);
    }
}
