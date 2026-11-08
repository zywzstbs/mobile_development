package cn.gulupai.server.common.audit;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 审计写入前的测试钩子。生产路径为空操作；测试可让下一次写入失败，以证明业务与审计同一事务回滚。
 */
@Component
public class AdminAuditGate {

    private final AtomicReference<RuntimeException> failure = new AtomicReference<>();

    public void failNext(RuntimeException exception) {
        failure.set(exception);
    }

    public void clear() {
        failure.set(null);
    }

    public void beforePersist() {
        RuntimeException exception = failure.getAndSet(null);
        if (exception != null) {
            throw exception;
        }
    }
}
