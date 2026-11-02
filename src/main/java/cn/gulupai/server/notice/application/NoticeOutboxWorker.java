package cn.gulupai.server.notice.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class NoticeOutboxWorker {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public NoticeOutboxWorker(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Scheduled(fixedDelay = 5000)
    public void scheduled() {
        drain();
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
            String type = (String) row.get("event_type");
            if ("CHAT_MESSAGE_SENT".equals(type)) {
                try {
                    JsonNode payload = mapper.readTree((String) row.get("payload"));
                    String messageId = payload.path("messageId").asText();
                    for (JsonNode mention : payload.path("mentions")) {
                        String userId = mention.asText();
                        if (userId.isBlank()) continue;
                        jdbc.update("""
                                INSERT INTO notification(id, user_id, source_type, source_id, category,
                                    title, body, event_version, visible_at, created_at)
                                VALUES (?, ?, 'CHAT_MESSAGE', ?, 'TEAM_MENTION', ?, '', 1, ?, ?)
                                ON CONFLICT (user_id, source_type, source_id, event_version) DO NOTHING
                                """, UUID.randomUUID().toString(), userId, messageId,
                                "有人在团队聊天中提到了你", now(), now());
                    }
                } catch (Exception ignored) {
                    // malformed outbox rows remain pending for inspection; no partial notification is written
                    continue;
                }
            }
            jdbc.update("UPDATE notification_outbox SET processed_at = ? WHERE id = ?",
                    now(), row.get("id"));
            count++;
        }
        return count;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }
}
