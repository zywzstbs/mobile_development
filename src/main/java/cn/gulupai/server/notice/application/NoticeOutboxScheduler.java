package cn.gulupai.server.notice.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 通知投递 outbox 的后台轮询。
 *
 * <p>轮询与投递实现分开注册，测试可以通过 {@code app.notice.outbox.scheduling-enabled=false}
 * 真正不注册后台任务（默认测试配置即关闭），由用例显式调用 {@link NoticeOutboxWorker#drain()}，
 * 避免轮询事务与集成测试清库交叉。生产不配置该属性，{@code matchIfMissing} 保持原有每 5 秒投递。
 */
@Component
@ConditionalOnProperty(name = "app.notice.outbox.scheduling-enabled",
        havingValue = "true", matchIfMissing = true)
public class NoticeOutboxScheduler {

    private final NoticeOutboxWorker worker;
    private final AtomicInteger inFlight = new AtomicInteger();

    public NoticeOutboxScheduler(NoticeOutboxWorker worker) {
        this.worker = worker;
    }

    @Scheduled(fixedDelayString = "${app.notice.outbox.fixed-delay-ms:5000}")
    public void poll() {
        inFlight.incrementAndGet();
        try {
            worker.drain();
        } finally {
            inFlight.decrementAndGet();
        }
    }

    /**
     * 等待当前正在执行的轮询结束。清理或断言前调用，避免把"已停止下次调度"当成"当前事务已结束"。
     *
     * @return 超时前是否已空闲
     */
    public boolean awaitIdle(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (inFlight.get() == 0) {
                return true;
            }
            Thread.sleep(10);
        }
        return inFlight.get() == 0;
    }
}
