package cn.gulupai.server.notice.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 通知 outbox 的投递实现。后台轮询在 {@link NoticeOutboxScheduler} 里单独注册，
 * 这里只保留一次可重入的批量投递，供调度与需要即时一致的请求路径显式调用。
 *
 * <p>整批投递沿用单个事务：载荷解析/校验失败只跳过当前事件且不写任何通知；
 * SQL 失败不在这里吞掉，向外传播让 Spring 回滚本批已写入的通知与 processed_at。
 */
@Component
public class NoticeOutboxWorker {

    private static final Logger log = LoggerFactory.getLogger(NoticeOutboxWorker.class);

    /** 源事件版本上限：与通知 source_version 及聊天消息 revision 的整数范围一致。 */
    private static final int MAX_SOURCE_VERSION = 999_999_999;
    /** 与聊天生产者对单条消息提及数量的限制保持一致。 */
    private static final int MAX_MENTIONS = 20;
    /** 现有 ID 规则：非空、限 120 字符、只含 URL 安全字符。 */
    private static final Pattern SOURCE_ID = Pattern.compile("[A-Za-z0-9_-]{1,120}");
    private static final String TITLE = "有人在团队聊天中提到了你";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public NoticeOutboxWorker(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional
    public int drain() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, project_id, event_type, payload_json::text AS payload
                FROM notification_outbox WHERE processed_at IS NULL
                ORDER BY created_at LIMIT 50 FOR UPDATE SKIP LOCKED
                """);
        int count = 0;
        for (Map<String, Object> row : rows) {
            Object outboxId = row.get("id");
            String type = (String) row.get("event_type");
            if ("CHAT_MESSAGE_SENT".equals(type)) {
                ChatMentionEvent event;
                try {
                    event = chatMention((String) row.get("payload"));
                } catch (JsonProcessingException ex) {
                    // 解析阶段失败：本事件零通知写入，processed_at 保持 null 待人工核对。
                    keepPending(outboxId, type, "INVALID_JSON");
                    continue;
                } catch (InvalidOutboxPayloadException ex) {
                    keepPending(outboxId, type, ex.reason());
                    continue;
                }
                // 只有整条载荷（版本与全部接收人）都通过校验后才开始写通知，
                // 且每条通知保存的是事件冻结的源版本，不是消息当前 revision。
                for (String userId : event.mentions()) {
                    jdbc.update("""
                            INSERT INTO notification(id, user_id, source_type, source_id, category,
                                title, body, event_version, source_version, visible_at, created_at)
                            VALUES (?, ?, 'CHAT_MESSAGE', ?, 'TEAM_MENTION', ?, '', 1, ?, ?, ?)
                            ON CONFLICT (user_id, source_type, source_id, event_version) DO NOTHING
                            """, UUID.randomUUID().toString(), userId, event.messageId(),
                            TITLE, event.sourceVersion(), now(), now());
                }
            }
            jdbc.update("UPDATE notification_outbox SET processed_at = ? WHERE id = ?", now(), outboxId);
            count++;
        }
        return count;
    }

    /**
     * 精确解析 CHAT_MESSAGE_SENT 载荷。版本号必须是 JSON 整数节点、可无损转 int 且在业务范围内；
     * {@code 1.75}/{@code 1.0} 这类小数一律拒绝，不做截断。
     */
    private ChatMentionEvent chatMention(String payload) throws JsonProcessingException {
        JsonNode node = mapper.readTree(payload);
        if (node == null || !node.isObject()) {
            throw new InvalidOutboxPayloadException("PAYLOAD_NOT_OBJECT");
        }
        String messageId = sourceId(node.get("messageId"), "MESSAGE_ID");
        int sourceVersion = sourceVersion(node.get("sourceVersion"));
        List<String> mentions = mentions(node.get("mentions"));
        return new ChatMentionEvent(messageId, sourceVersion, mentions);
    }

    private static String sourceId(JsonNode node, String field) {
        if (node == null || node.isNull()) {
            throw new InvalidOutboxPayloadException(field + "_MISSING");
        }
        if (!node.isTextual() || !SOURCE_ID.matcher(node.textValue()).matches()) {
            throw new InvalidOutboxPayloadException(field + "_INVALID");
        }
        return node.textValue();
    }

    private static int sourceVersion(JsonNode node) {
        if (node == null || node.isNull()) {
            throw new InvalidOutboxPayloadException("SOURCE_VERSION_MISSING");
        }
        if (!node.isIntegralNumber()) {
            throw new InvalidOutboxPayloadException("SOURCE_VERSION_NOT_INTEGER");
        }
        if (!node.canConvertToInt()) {
            throw new InvalidOutboxPayloadException("SOURCE_VERSION_OUT_OF_RANGE");
        }
        int value = node.intValue();
        if (value < 1 || value > MAX_SOURCE_VERSION) {
            throw new InvalidOutboxPayloadException("SOURCE_VERSION_OUT_OF_RANGE");
        }
        return value;
    }

    private static List<String> mentions(JsonNode node) {
        if (node == null || node.isNull()) {
            throw new InvalidOutboxPayloadException("MENTIONS_MISSING");
        }
        if (!node.isArray()) {
            throw new InvalidOutboxPayloadException("MENTIONS_NOT_ARRAY");
        }
        // 合法空数组表示没有接收人，属于正常完成的事件；只有缺失/类型错误才走坏载荷路径。
        Set<String> unique = new LinkedHashSet<>();
        for (JsonNode mention : node) {
            unique.add(sourceId(mention, "MENTION"));
        }
        if (unique.size() > MAX_MENTIONS) {
            throw new InvalidOutboxPayloadException("MENTIONS_TOO_MANY");
        }
        return new ArrayList<>(unique);
    }

    private void keepPending(Object outboxId, String type, String reason) {
        // 只记录定位与原因码：不打印原载荷、聊天正文、接收人列表或凭据。
        log.warn("通知 outbox 载荷不可投递，保留待核对 outboxId={} eventType={} reason={}",
                outboxId, type, reason);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    private record ChatMentionEvent(String messageId, int sourceVersion, List<String> mentions) {
    }

    /** 坏载荷的定点原因码，随日志输出便于受控运维核对。 */
    private static final class InvalidOutboxPayloadException extends RuntimeException {
        private final String reason;

        private InvalidOutboxPayloadException(String reason) {
            super(reason);
            this.reason = reason;
        }

        private String reason() {
            return reason;
        }
    }
}
