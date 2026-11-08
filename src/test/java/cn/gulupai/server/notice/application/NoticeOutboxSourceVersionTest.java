package cn.gulupai.server.notice.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * R3 类型组回归：{@code NoticeOutboxWorker} 在写通知前必须把 sourceVersion 当成精确整数，
 * 小数、溢出、非正数和缺失一律不得落成伪版本，坏载荷也不能被标记成已处理。
 *
 * <p>这里只验证真实 worker 的转换分支与 JDBC 调用形状；PostgreSQL 存储、行锁与事务回滚
 * 由 {@code NoticeOutboxSourceVersionIT} 用真实数据库证明。
 */
class NoticeOutboxSourceVersionTest {

    private static final String INSERT = "INSERT INTO notification(";
    private static final String MARK_PROCESSED = "UPDATE notification_outbox";

    private JdbcTemplate jdbc;
    private NoticeOutboxWorker worker;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        worker = new NoticeOutboxWorker(jdbc, new ObjectMapper());
    }

    /** 合法整数：原值落库、事件完成。 */
    @ParameterizedTest
    @ValueSource(strings = {"1", "2", "999999999"})
    void integralVersionsArePersistedExactly(String versionJson) {
        pending("outbox_ok", payload("message_ok", versionJson, "[\"user_a\"]"));

        assertThat(worker.drain()).isEqualTo(1);

        int expected = Integer.parseInt(versionJson);
        verify(jdbc).update(contains(INSERT), anyString(), eq("user_a"), eq("message_ok"), anyString(),
                eq(expected), any(), any());
        verify(jdbc).update(startsWith(MARK_PROCESSED), any(), eq("outbox_ok"));
    }

    /** 小数（含 1.0）、溢出、超出业务上限、非正数：一律零新增且保持待处理，不做截断。 */
    @ParameterizedTest
    @ValueSource(strings = {"1.75", "1.0", "999999999.5", "2147483648", "4294967297",
            "100000000000000000000", "1000000000", "0", "-1"})
    void nonIntegralOrOutOfRangeVersionsAreNeverConverted(String versionJson) {
        assertRejected(payload("message_bad", versionJson, "[\"user_a\"]"));
    }

    /** 缺失、null、字符串、布尔都不是整数节点。 */
    @ParameterizedTest
    @ValueSource(strings = {"MISSING", "null", "\"1\"", "true", "false", "[]", "{}"})
    void nonNumericVersionNodesAreRejected(String versionJson) {
        String payload = "MISSING".equals(versionJson)
                ? "{\"messageId\":\"message_bad\",\"mentions\":[\"user_a\"]}"
                : payload("message_bad", versionJson, "[\"user_a\"]");
        assertRejected(payload);
    }

    @Test
    void twoRecipientsOfOneEventShareTheSameFrozenVersion() {
        pending("outbox_two", payload("message_two", "7", "[\"user_a\",\"user_b\"]"));

        assertThat(worker.drain()).isEqualTo(1);

        verify(jdbc).update(contains(INSERT), anyString(), eq("user_a"), eq("message_two"), anyString(),
                eq(7), any(), any());
        verify(jdbc).update(contains(INSERT), anyString(), eq("user_b"), eq("message_two"), anyString(),
                eq(7), any(), any());
        verify(jdbc).update(startsWith(MARK_PROCESSED), any(), eq("outbox_two"));
    }

    /** 重复接收人按生产者口径归一化，只写一条通知，避免同一事件重复投递。 */
    @Test
    void duplicateRecipientsAreNormalisedToASingleNotification() {
        pending("outbox_dup", payload("message_dup", "3", "[\"user_a\",\"user_a\"]"));

        assertThat(worker.drain()).isEqualTo(1);

        verify(jdbc, times(1)).update(contains(INSERT), anyString(), eq("user_a"), eq("message_dup"),
                anyString(), eq(3), any(), any());
    }

    /** 合法空 mentions 表示没有接收人，事件可以正常完成而不写通知。 */
    @Test
    void emptyMentionsCompleteTheEventWithoutNotifications() {
        pending("outbox_empty", payload("message_empty", "5", "[]"));

        assertThat(worker.drain()).isEqualTo(1);

        verify(jdbc, never()).update(contains(INSERT), any(), any(), any(), any(), any(), any(), any());
        verify(jdbc).update(startsWith(MARK_PROCESSED), any(), eq("outbox_empty"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"messageId\":null,\"sourceVersion\":1,\"mentions\":[\"user_a\"]}",
            "{\"messageId\":\"\",\"sourceVersion\":1,\"mentions\":[\"user_a\"]}",
            "{\"messageId\":\"has space\",\"sourceVersion\":1,\"mentions\":[\"user_a\"]}",
            "{\"sourceVersion\":1,\"mentions\":[\"user_a\"]}",
            "{\"messageId\":\"message_x\",\"sourceVersion\":1}",
            "{\"messageId\":\"message_x\",\"sourceVersion\":1,\"mentions\":null}",
            "{\"messageId\":\"message_x\",\"sourceVersion\":1,\"mentions\":\"user_a\"}",
            "{\"messageId\":\"message_x\",\"sourceVersion\":1,\"mentions\":[\"\"]}",
            "{\"messageId\":\"message_x\",\"sourceVersion\":1,\"mentions\":[1]}",
            "[1,2,3]",
            "not-json"
    })
    void malformedPayloadsStayPendingWithoutAnyWrite(String payload) {
        assertRejected(payload);
    }

    /** 超过生产者允许的提及数量同样属于无法投递的载荷。 */
    @Test
    void tooManyMentionsStayPending() {
        List<String> mentions = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            mentions.add("\"user_" + i + "\"");
        }
        assertRejected(payload("message_many", "2", "[" + String.join(",", mentions) + "]"));
    }

    /** 一个批次里的坏事件不影响合法事件，但坏事件必须保持 pending。 */
    @Test
    void aBadEventDoesNotBlockOrPolluteALegalEventInTheSameBatch() {
        when(jdbc.queryForList(anyString())).thenReturn(List.of(
                row("outbox_bad", "CHAT_MESSAGE_SENT", payload("message_bad", "1.75", "[\"user_a\"]")),
                row("outbox_ok", "CHAT_MESSAGE_SENT", payload("message_ok", "2", "[\"user_b\"]"))));

        assertThat(worker.drain()).isEqualTo(1);

        verify(jdbc, never()).update(contains(INSERT), anyString(), eq("user_a"), any(),
                any(), any(), any(), any());
        verify(jdbc).update(contains(INSERT), anyString(), eq("user_b"), eq("message_ok"), anyString(),
                eq(2), any(), any());
        verify(jdbc).update(startsWith(MARK_PROCESSED), any(), eq("outbox_ok"));
        verify(jdbc, never()).update(startsWith(MARK_PROCESSED), any(), eq("outbox_bad"));
    }

    /** 非聊天事件保持原有行为：不改动版本处理，只把事件标记完成。 */
    @Test
    void nonChatEventTypesKeepTheirExistingBehaviour() {
        when(jdbc.queryForList(anyString())).thenReturn(List.of(row("outbox_test", "P66_TEST_EVENT", "{}")));

        assertThat(worker.drain()).isEqualTo(1);

        verify(jdbc, never()).update(contains(INSERT), any(), any(), any(), any(), any(), any(), any());
        verify(jdbc).update(startsWith(MARK_PROCESSED), any(), eq("outbox_test"));
    }

    private void assertRejected(String payload) {
        pending("outbox_rejected", payload);

        assertThat(worker.drain()).isZero();

        verify(jdbc, never()).update(contains(INSERT), any(), any(), any(), any(), any(), any(), any());
        verify(jdbc, never()).update(startsWith(MARK_PROCESSED), any(), any());
    }

    private void pending(String id, String payload) {
        when(jdbc.queryForList(anyString()))
                .thenReturn(List.of(row(id, "CHAT_MESSAGE_SENT", payload)));
    }

    private static Map<String, Object> row(String id, String eventType, String payload) {
        return Map.of("id", id, "project_id", "project_review", "event_type", eventType, "payload", payload);
    }

    private static String payload(String messageId, String versionJson, String mentionsJson) {
        return "{\"messageId\":\"" + messageId + "\",\"sequence\":1,\"sourceVersion\":" + versionJson
                + ",\"mentions\":" + mentionsJson + "}";
    }
}
