package cn.gulupai.server.identity.api;

/**
 * AUTH-01 正文固定为 {}；未知字段由 Jackson 拒绝并返回 422。
 */
public record LogoutRequest() {
}
