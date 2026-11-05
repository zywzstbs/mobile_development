package cn.gulupai.server.common.api;

import java.util.List;

/**
 * 校验失败时放进响应 data 的结构。没有字段错误时仍返回 data=null，兼容旧客户端。
 */
public record FieldErrorBody(List<FieldErrorDetail> fieldErrors) {
    public FieldErrorBody {
        fieldErrors = List.copyOf(fieldErrors);
    }
}
