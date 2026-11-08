package cn.gulupai.server.notice;

import cn.gulupai.server.it.TestSchemaCleaner;
import cn.gulupai.server.notice.application.NoticeOutboxScheduler;
import cn.gulupai.server.notice.application.NoticeOutboxWorker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 默认测试配置下不应注册 outbox 后台轮询：轮询事务会与用例清库交叉（T1）。
 * 投递本身仍可被用例显式驱动。
 */
@SpringBootTest
@ActiveProfiles("test")
class NoticeOutboxBackgroundOffIT {

    private static final String OUTBOX_ID = "p66-outbox-background-off";

    @Autowired(required = false)
    private NoticeOutboxScheduler scheduler;

    @Autowired
    private ScheduledTaskHolder scheduledTaskHolder;

    @Autowired
    private NoticeOutboxWorker worker;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        deleteOutboxRows();
    }

    @AfterEach
    void tearDown() {
        deleteOutboxRows();
    }

    @Test
    void testProfileDoesNotRegisterOutboxPolling() {
        assertThat(scheduler)
                .as("测试配置 app.notice.outbox.scheduling-enabled=false 必须真的不注册调度组件")
                .isNull();
        assertThat(scheduledTaskHolder.getScheduledTasks())
                .as("不应存在 NoticeOutboxScheduler 的调度任务")
                .noneMatch(task -> String.valueOf(task.getTask().getRunnable())
                        .contains("NoticeOutboxScheduler"));
    }

    @Test
    void explicitDrainStillDeliversPendingRows() {
        insertPendingRow();

        assertThat(worker.drain()).isGreaterThanOrEqualTo(1);

        assertThat(jdbc.queryForObject(
                "SELECT processed_at IS NOT NULL FROM notification_outbox WHERE id = ?",
                Boolean.class, OUTBOX_ID))
                .isTrue();
    }

    private void insertPendingRow() {
        jdbc.update("""
                INSERT INTO notification_outbox(id, project_id, event_type, payload_json, created_at)
                VALUES (?, NULL, 'P66_TEST_EVENT', '{}'::jsonb, now())
                """, OUTBOX_ID);
    }

    private void deleteOutboxRows() {
        TestSchemaCleaner.deleteAll(jdbc, List.of("notification_outbox"));
    }
}
