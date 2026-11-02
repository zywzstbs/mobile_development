package cn.gulupai.server.common.idempotency;

/**
 * 幂等回放投影扩展点。
 *
 * <p>成功回执只保证“同一次业务效果、同一稳定资源ID”，不保证资源内容在回放时仍然可见。
 * 对可撤回、可修订或权限会变化的资源，回放必须按当前数据库状态重新投影，
 * 不能把首次响应中的正文、引用预览或授权结论直接返回。
 *
 * <p>默认实现原样返回，供不可变回执继续沿用；聊天、留言等可撤回内容必须提供
 * 会重新读取当前资源的实现。
 */
@FunctionalInterface
public interface ReplayProjector<T> {

    T project(T cached);

    static <T> ReplayProjector<T> identity() {
        return cached -> cached;
    }
}
