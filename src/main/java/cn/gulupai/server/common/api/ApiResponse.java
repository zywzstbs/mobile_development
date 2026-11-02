package cn.gulupai.server.common.api;

/**
 * 统一响应信封：成功与失败都返回该结构；失败时 data 固定为 null。
 */
public record ApiResponse<T>(String code, String message, T data) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(ApiCode.OK.name(), ApiCode.OK.defaultMessage(), data);
    }

    public static ApiResponse<Void> error(ApiCode code) {
        return new ApiResponse<>(code.name(), code.defaultMessage(), null);
    }

    public static ApiResponse<Void> error(ApiCode code, String message) {
        return new ApiResponse<>(code.name(), message, null);
    }
}
