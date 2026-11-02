package cn.gulupai.server.common.api;

import java.io.IOException;

/**
 * 请求正文读取超过 1MiB 时由限流输入流抛出，由统一异常处理转为 413。
 */
public class RequestTooLargeException extends IOException {

    public RequestTooLargeException() {
        super("request body exceeds limit");
    }
}
