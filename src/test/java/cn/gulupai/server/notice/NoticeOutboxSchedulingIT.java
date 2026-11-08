package cn.gulupai.server.notice;

import cn.gulupai.server.it.TestSchemaCleaner;
import cn.gulupai.server.notice.application.NoticeOutboxScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * 调度专用测试：显式开启 outbox 后台轮询，验证任务确实注册、启动后能自动投递，
 * 且停止调度后清理不会与仍在执行的轮询事务交叉（T1）。
 *
 * <p>本类显式开启轮询，必须 {@link DirtiesContext} 关闭上下文，否则缓存下来的上下文
 * 会在后续集成测试期间持续轮询同一套 schema。
 */
@SpringBootTest(properties = {
        "app.notice.outbox.scheduling-enabled=true",
        "app.notice.outbox.fixed-delay-ms=200"
})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class NoticeOutboxSchedulingIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final String OUTBOX_ID = "p66-outbox-scheduled";

    @Autowired
    private NoticeOutboxScheduler scheduler;

    @Autowired
    private ScheduledTaskHolder scheduledTaskHolder;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() throws InterruptedException {
        awaitSchedulerIdle();
        clearOutbox();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        awaitSchedulerIdle();
        clearOutbox();
    }

    @Test
    void backgroundPollingIsRegistered() {
        assertThat(scheduledTaskHolder.getScheduledTasks())
                .as("app.notice.outbox.scheduling-enabled=true 必须注册 NoticeOutboxScheduler 的调度任务")
                .anyMatch(task -> String.valueOf(task.getTask().getRunnable())
                        .contains("NoticeOutboxScheduler"));
    }

    @Test
    void backgroundPollingDrainsPendingRowsOnItsOwn() throws InterruptedException {
        jdbc.update("""
                INSERT INTO notification_outbox(id, project_id, event_type, payload_json, created_at)
                VALUES (?, NULL, 'P66_TEST_EVENT', '{}'::jsonb, now())
                """, OUTBOX_ID);

        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            Boolean processed = jdbc.queryForObject(
                    "SELECT processed_at IS NOT NULL FROM notification_outbox WHERE id = ?",
                    Boolean.class, OUTBOX_ID);
            if (Boolean.TRUE.equals(processed)) {
                return;
            }
            Thread.sleep(20);
        }
        fail("后台轮询没有在 " + TIMEOUT.toSeconds() + " 秒内处理 " + OUTBOX_ID);
    }

    /**
     * 只关闭“下一次调度”不等于当前轮询已结束：清理前等正在执行的投递事务收尾。
     */
    private void awaitSchedulerIdle() throws InterruptedException {
        assertThat(scheduler.awaitIdle(TIMEOUT))
                .as("后台轮询未在 " + TIMEOUT.toSeconds() + " 秒内结束当前批次")
                .isTrue();
    }

    private void clearOutbox() {
        TestSchemaCleaner.deleteAll(jdbc, List.of("notification_outbox"));
    }
}
