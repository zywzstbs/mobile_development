package cn.gulupai.server.common.api;

/**
 * 面向客户端的字段错误。只包含字段路径、错误码和中文说明，不包含用户填写的值。
 */
public record FieldErrorDetail(String field, String code, String message) {
}
