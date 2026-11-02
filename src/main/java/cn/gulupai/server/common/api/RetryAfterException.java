package cn.gulupai.server.common.api;

/**
 * 限流异常，携带 Retry-After 秒数；由统一异常处理写入响应头。
 */
public class RetryAfterException extends ApiException {

    private final long retryAfterSeconds;

    public RetryAfterException(ApiCode code, String message, long retryAfterSeconds) {
        super(code, message);
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
