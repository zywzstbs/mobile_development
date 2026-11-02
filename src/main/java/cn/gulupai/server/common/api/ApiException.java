package cn.gulupai.server.common.api;

/**
 * 可预期的业务异常。message 直接面向用户，不包含堆栈、SQL 或私人内容。
 */
public class ApiException extends RuntimeException {

    private final ApiCode code;

    public ApiException(ApiCode code) {
        super(code.defaultMessage());
        this.code = code;
    }

    public ApiException(ApiCode code, String message) {
        super(message);
        this.code = code;
    }

    public ApiException(ApiCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public ApiCode code() {
        return code;
    }

    public static ApiException notFound() {
        return new ApiException(ApiCode.NOT_FOUND);
    }

    public static ApiException validation(String message) {
        return new ApiException(ApiCode.VALIDATION_FAILED, message);
    }
}
