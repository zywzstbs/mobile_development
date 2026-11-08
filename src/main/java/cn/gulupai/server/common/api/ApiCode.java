package cn.gulupai.server.common.api;

import org.springframework.http.HttpStatus;

/**
 * 业务错误码基线，来源：API 接口设计文档 §8 及补充规则。
 *
 * <p>HTTP 状态与业务码分开；枚举名即响应中的 {@code code}。协议层补充码
 * （METHOD_NOT_ALLOWED、UNSUPPORTED_MEDIA_TYPE）只用于把框架异常收敛成统一信封，
 * 不承载业务语义。
 */
public enum ApiCode {

    OK(HttpStatus.OK, "成功"),

    // ---- 400 协议/控制参数 ----
    MALFORMED_JSON(HttpStatus.BAD_REQUEST, "请求格式无效"),
    VERSION_REQUIRED(HttpStatus.BAD_REQUEST, "缺少当前版本"),
    IDEMPOTENCY_KEY_REQUIRED(HttpStatus.BAD_REQUEST, "缺少幂等键"),

    // ---- 401 认证 ----
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "登录名或密码错误"),
    SESSION_EXPIRED(HttpStatus.UNAUTHORIZED, "登录状态已失效，请重新登录"),

    // ---- 403 授权 ----
    ACCOUNT_DISABLED(HttpStatus.FORBIDDEN, "账号已停用"),
    ACCOUNT_PENDING_REVIEW(HttpStatus.FORBIDDEN, "账号正在审核，通过后才能登录"),
    ACCOUNT_REJECTED(HttpStatus.FORBIDDEN, "注册申请未通过"),
    REGISTRATION_DISABLED(HttpStatus.FORBIDDEN, "当前未开放注册"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "无权执行此操作"),
    RECUSAL_REQUIRED(HttpStatus.FORBIDDEN, "该操作需要回避"),
    SELF_REVIEW_FORBIDDEN(HttpStatus.FORBIDDEN, "不能验收本人参与的交付"),
    REAUTH_REQUIRED(HttpStatus.FORBIDDEN, "需要重新验证身份后才能执行"),
    REAUTH_EXPIRED(HttpStatus.FORBIDDEN, "重新验证已过期"),
    REAUTH_CONTEXT_MISMATCH(HttpStatus.FORBIDDEN, "重新验证与当前操作不一致"),

    // ---- 404 ----
    NOT_FOUND(HttpStatus.NOT_FOUND, "资源不存在"),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "资源不存在"),
    PATH_NOT_FOUND(HttpStatus.NOT_FOUND, "请求路径不存在"),

    // ---- 409 版本/状态/唯一性 ----
    RESOURCE_CHANGED(HttpStatus.CONFLICT, "内容已更新，请重新读取后确认"),
    SOURCE_CHANGED(HttpStatus.CONFLICT, "来源内容已变化，请重新读取后确认"),
    DRAFT_CONFLICT(HttpStatus.CONFLICT, "草稿已在其他位置更新"),
    PREVIEW_STALE(HttpStatus.CONFLICT, "预览已过期，请重新预览"),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.CONFLICT, "同一幂等键已用于其他请求"),
    REVISION_CONFLICT(HttpStatus.CONFLICT, "内容已更新，请重新读取后确认"),
    INVALID_STATE_TRANSITION(HttpStatus.CONFLICT, "当前状态不允许此变更"),
    LAST_ACTIVE_SUPER_ADMIN(HttpStatus.CONFLICT, "必须保留至少一位可恢复管理的超级管理员"),
    CLIENT_MESSAGE_ID_REUSED(HttpStatus.CONFLICT, "客户端消息标识已用于其他内容"),
    CLIENT_REQUEST_ID_REUSED(HttpStatus.CONFLICT, "客户端请求标识已用于其他内容"),
    OPERATION_IN_PROGRESS(HttpStatus.CONFLICT, "同一操作正在处理中"),
    ROLE_FULL(HttpStatus.CONFLICT, "岗位已满"),
    ALREADY_IN_ASSIGNMENT_TEAM(HttpStatus.CONFLICT, "已加入该作业的团队"),
    INVITATION_EXPIRED(HttpStatus.CONFLICT, "邀请已失效"),
    APPLICATION_EXISTS(HttpStatus.CONFLICT, "申请已存在"),
    ACCOUNT_EXISTS(HttpStatus.CONFLICT, "账号已存在"),
    ASSIGNMENT_LOCKED(HttpStatus.CONFLICT, "作业已确认，不能再修改"),
    ASSIGNMENT_IN_USE(HttpStatus.CONFLICT, "已有小组使用该作业，不能修改截止时间或人数"),
    DEPENDENCY_BLOCKED(HttpStatus.CONFLICT, "前置任务尚未完成"),
    AGREEMENT_REQUIRED(HttpStatus.CONFLICT, "尚未确认正式约定"),
    SUBMISSION_ALREADY_REVIEWED(HttpStatus.CONFLICT, "提交已验收"),
    TASK_ALREADY_CLAIMED(HttpStatus.CONFLICT, "任务已被认领"),
    DICE_ROUND_CHANGED(HttpStatus.CONFLICT, "骰子轮次已变化"),
    DICE_EXPIRED(HttpStatus.CONFLICT, "骰子会话已过期"),
    DICE_OUTCOME_CHANGED(HttpStatus.CONFLICT, "骰子结果已变化"),
    TASK_CHANGED(HttpStatus.CONFLICT, "任务已变化"),
    ANNOUNCEMENT_VERSION_CHANGED(HttpStatus.CONFLICT, "公告内容版本已变化"),
    ACK_NOT_REQUESTED(HttpStatus.CONFLICT, "该公告未要求知悉"),
    CURSOR_EXPIRED(HttpStatus.CONFLICT, "分页游标已失效，请重新加载"),
    CHAT_SYNC_EXPIRED(HttpStatus.CONFLICT, "聊天同步游标已失效，请重新读取"),
    CODE_INVALID(HttpStatus.CONFLICT, "邀请码无效"),
    CODE_DISABLED(HttpStatus.CONFLICT, "邀请码已停用"),
    CODE_EXPIRED(HttpStatus.CONFLICT, "邀请码已过期"),
    CODE_REVOKED(HttpStatus.CONFLICT, "邀请码已撤销"),
    MESSAGE_WITHDRAW_WINDOW_CLOSED(HttpStatus.CONFLICT, "已超过撤回时限"),
    TICKET_REOPEN_WINDOW_CLOSED(HttpStatus.CONFLICT, "已超过重开时限"),
    GRADE_NOT_READY(HttpStatus.CONFLICT, "成绩尚未准备好"),
    UNIT_NOT_ASSIGNED(HttpStatus.CONFLICT, "未分配该交付项"),
    PROJECT_READ_ONLY(HttpStatus.CONFLICT, "项目当前只读"),
    PIN_LIMIT_REACHED(HttpStatus.CONFLICT, "置顶数量已达上限"),
    CONTENT_WITHDRAWN(HttpStatus.CONFLICT, "内容已撤回"),

    // ---- 413 大小限制 ----
    PAYLOAD_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "请求内容过大"),
    FILE_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "文件超过大小限制"),
    COLLECTION_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "结果集超过大小限制"),

    // ---- 422 校验 ----
    VALIDATION_FAILED(HttpStatus.UNPROCESSABLE_ENTITY, "字段校验未通过"),
    FIELD_NOT_ALLOWED(HttpStatus.UNPROCESSABLE_ENTITY, "包含不允许的字段"),
    POINTS_INVALID(HttpStatus.UNPROCESSABLE_ENTITY, "点数字段无效"),
    STANDARD_NOT_CHECKED(HttpStatus.UNPROCESSABLE_ENTITY, "尚有标准未核对"),

    // ---- 429 限流 ----
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "操作过于频繁，请稍后再试"),
    REMINDER_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "催阅过于频繁"),

    // ---- 503 服务/渠道 ----
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "服务暂时不可用"),
    COURSE_REVIEW_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "课程审核暂不可用"),
    SUPPORT_ROUTE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "暂无可用的受理渠道"),
    REPORT_ROUTE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "暂无可用的举报受理渠道"),

    // ---- 500 ----
    SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "服务器无法完成请求"),
    ENDPOINT_NOT_IMPLEMENTED(HttpStatus.NOT_IMPLEMENTED, "该管理功能尚未开放"),

    // ---- 传输层补充 ----
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "请求方法不支持"),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "请求内容类型不支持");

    private final HttpStatus httpStatus;
    private final String defaultMessage;

    ApiCode(HttpStatus httpStatus, String defaultMessage) {
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
