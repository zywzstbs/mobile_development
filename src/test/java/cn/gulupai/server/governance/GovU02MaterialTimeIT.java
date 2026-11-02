package cn.gulupai.server.governance;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * U02：新提交是服务器真实时间 + EXACT；升级后无法恢复的历史版本必须显式 null + UNKNOWN，
 * 详情、case 内嵌、本人列表和幂等回放使用同一投影。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(GovMaterialScenarioSupport.TestClockConfig.class)
class GovU02MaterialTimeIT extends GovMaterialScenarioSupport {

    private static final DateTimeFormatter UTC_SECONDS =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    @Autowired private MaterialResponseTimeRecoveryService recoveryService;

    @Test
    void u02NewSubmissionsUseServerClockAndExactConfidenceEverywhere() throws Exception {
        clock.setInstant(BASE.plus(Duration.ofHours(10)));
        Scenario s = materialScenario("u02-f");
        respondOk(s.member(), s.materialId(), "v1 正文", 1, "u02-f-v1");

        JsonNode first = materialData(s.member(), s.materialId());
        assertExact(first.get("responses").get(0), "2026-10-31T10:00:00Z");
        assertExact(first.get("response"), "2026-10-31T10:00:00Z");

        clock.setInstant(BASE.plus(Duration.ofHours(10)).plus(Duration.ofMinutes(10)));
        respondOk(s.member(), s.materialId(), "v2 正文", materialRevision(s.materialId()), "u02-f-v2");

        JsonNode teacher = materialData(s.teacher(), s.materialId());
        assertExact(teacher.get("responses").get(0), "2026-10-31T10:00:00Z");
        assertExact(teacher.get("responses").get(1), "2026-10-31T10:10:00Z");
        assertExact(teacher.get("response"), "2026-10-31T10:10:00Z");
        assertThat(teacher.get("responseVersion").asInt()).isEqualTo(2);

        JsonNode caseView = json(mockMvc.perform(get("/api/v1/personnel-cases/{id}", s.caseId())
                .header("Authorization", bearer(s.teacher()))).andExpect(status().isOk()).andReturn())
                .get("materials").get(0);
        assertExact(caseView.get("responses").get(0), "2026-10-31T10:00:00Z");
        assertExact(caseView.get("responses").get(1), "2026-10-31T10:10:00Z");

        JsonNode mine = json(mockMvc.perform(get("/api/v1/me/material-requests")
                .header("Authorization", bearer(s.member()))).andExpect(status().isOk()).andReturn())
                .get("items").get(0);
        assertExact(mine.get("responses").get(1), "2026-10-31T10:10:00Z");
        assertExact(mine.get("response"), "2026-10-31T10:10:00Z");

        // 同键回放仍从当前数据库投影取时间，而不是旧回执里的正文/时间对象。
        MvcResult replay = respond(s.member(), s.materialId(), "v2 正文",
                materialRevision(s.materialId()) - 1, "u02-f-v2");
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        JsonNode replayed = objectMapper.readTree(replay.getResponse().getContentAsString()).get("data");
        assertExact(replayed.get("response"), "2026-10-31T10:10:00Z");
        assertThat(replayed.get("response").get("content").asText()).isEqualTo("v2 正文");
    }

    @Test
    void u02UnknownLegacyTimeIsExplicitNullWithConfidenceInEveryView() throws Exception {
        clock.setInstant(BASE.plus(Duration.ofHours(10)));
        Scenario s = materialScenario("u02-h");
        respondOk(s.member(), s.materialId(), "历史正文", 1, "u02-h-v1");
        String legacyRecordedAt = jdbc.queryForObject(
                "SELECT created_at::text FROM material_response WHERE material_id = ? AND version = 1",
                String.class, s.materialId());
        makeLegacyUnknown(s.materialId(), 1);

        JsonNode member = materialData(s.member(), s.materialId());
        assertUnknown(member.get("responses").get(0), "历史正文");
        assertUnknown(member.get("response"), "历史正文");
        assertThat(member.get("responses").get(0).get("createdAt").isNull()).isTrue();

        JsonNode teacher = materialData(s.teacher(), s.materialId());
        assertUnknown(teacher.get("responses").get(0), "历史正文");
        assertUnknown(teacher.get("response"), "历史正文");

        JsonNode caseView = json(mockMvc.perform(get("/api/v1/personnel-cases/{id}", s.caseId())
                .header("Authorization", bearer(s.teacher()))).andExpect(status().isOk()).andReturn())
                .get("materials").get(0);
        assertUnknown(caseView.get("responses").get(0), "历史正文");
        assertUnknown(caseView.get("response"), "历史正文");

        JsonNode mine = json(mockMvc.perform(get("/api/v1/me/material-requests")
                .header("Authorization", bearer(s.member()))).andExpect(status().isOk()).andReturn())
                .get("items").get(0);
        assertUnknown(mine.get("responses").get(0), "历史正文");
        assertUnknown(mine.get("response"), "历史正文");

        // 旧回执可能保存了非空 createdAt；回放必须重新投影当前 UNKNOWN 状态。
        MvcResult replay = respond(s.member(), s.materialId(), "历史正文", 1, "u02-h-v1");
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        JsonNode replayed = objectMapper.readTree(replay.getResponse().getContentAsString()).get("data");
        assertUnknown(replayed.get("responses").get(0), "历史正文");
        assertUnknown(replayed.get("response"), "历史正文");

        // 未知时间只影响时间语义，正文、版本、作者和诊断值都保留。
        assertThat(mine.get("responses").get(0).get("version").asInt()).isEqualTo(1);
        assertThat(mine.get("responses").get(0).get("authorId").asText()).isEqualTo(MEMBER);
        String storedLegacy = jdbc.queryForObject(
                "SELECT legacy_recorded_at::text FROM material_response WHERE material_id = ? AND version = 1",
                String.class, s.materialId());
        assertThat(storedLegacy).isNotNull();
        assertThat(legacyRecordedAt).isNotNull();
    }

    @Test
    void u02RecoveryOnlyAcceptsReceiptForTheSameSubmissionAndIsIdempotent() throws Exception {
        clock.setInstant(BASE.plus(Duration.ofHours(10)));
        Scenario s = materialScenario("u02-e");
        respondOk(s.member(), s.materialId(), "可恢复正文", 1, "u02-e-v1");
        makeLegacyUnknown(s.materialId(), 1);

        String responsePath = "/api/v1/material-requests/" + s.materialId() + "/responses";
        String receiptId = jdbc.queryForObject("""
                SELECT id FROM operation_receipt
                WHERE user_id = ? AND method = 'POST' AND path = ? AND key = 'u02-e-v1'
                """, String.class, MEMBER, responsePath);
        OffsetDateTime receiptTime = jdbc.queryForObject(
                "SELECT created_at FROM operation_receipt WHERE id = ?", OffsetDateTime.class, receiptId);
        // 已知 Clock 的真实提交结果才是恢复时间；回执创建时间故意改成不同值。
        Instant submittedAt = BASE.plus(Duration.ofHours(10));
        jdbc.update("UPDATE operation_receipt SET created_at = ? WHERE id = ?",
                OffsetDateTime.ofInstant(submittedAt.minus(Duration.ofHours(1)), ZoneOffset.UTC), receiptId);
        assertThat(receiptTime.toInstant()).isNotEqualTo(submittedAt);

        // 其他动作的回执（本 case 的建材料请求）不能冒充提交证据。
        String wrongReceipt = jdbc.queryForObject("""
                SELECT id FROM operation_receipt
                WHERE user_id = ? AND path = ? AND key = ?
                """, String.class, TEACHER_A, "/api/v1/personnel-cases/" + s.caseId() + "/material-requests",
                "material-" + s.caseId());
        assertThatThrownBy(() -> recoveryService.recover(s.materialId(), 1, wrongReceipt, false, "test-admin"))
                .hasMessageContaining("回执路径");
        assertUnknown(materialData(s.member(), s.materialId()).get("response"), "可恢复正文");

        MaterialResponseTimeRecoveryService.RecoveryResult dryRun =
                recoveryService.recover(s.materialId(), 1, receiptId, true, "test-admin");
        assertThat(dryRun.status()).isEqualTo("DRY_RUN");
        assertThat(dryRun.createdAt()).isEqualTo(submittedAt);
        assertUnknown(materialData(s.member(), s.materialId()).get("response"), "可恢复正文");

        MaterialResponseTimeRecoveryService.RecoveryResult recovered =
                recoveryService.recover(s.materialId(), 1, receiptId, false, "test-admin");
        assertThat(recovered.status()).isEqualTo("RECOVERED");
        assertThat(recovered.createdAt()).isEqualTo(submittedAt);
        String evidence = "operation_receipt:" + receiptId;
        assertThat(recovered.evidence()).isEqualTo(evidence);

        JsonNode view = materialData(s.member(), s.materialId());
        assertThat(view.get("response").get("createdAt").asText())
                .isEqualTo(UTC_SECONDS.format(submittedAt));
        assertThat(view.get("response").get("createdAtConfidence").asText()).isEqualTo("EXACT");
        assertThat(jdbc.queryForObject("""
                SELECT created_at_confidence FROM material_response WHERE material_id = ? AND version = 1
                """, String.class, s.materialId())).isEqualTo("EXACT");
        assertThat(jdbc.queryForObject("""
                SELECT time_evidence FROM material_response WHERE material_id = ? AND version = 1
                """, String.class, s.materialId())).isEqualTo(evidence);
        assertThat(jdbc.queryForObject("""
                SELECT time_source FROM material_response WHERE material_id = ? AND version = 1
                """, String.class, s.materialId())).isEqualTo("RECOVERED_EVIDENCE");
        assertThat(jdbc.queryForObject("""
                SELECT created_at FROM material_response WHERE material_id = ? AND version = 1
                """, OffsetDateTime.class, s.materialId()).toInstant()).isEqualTo(submittedAt);
        assertThat(jdbc.queryForObject("""
                SELECT legacy_recorded_at FROM material_response WHERE material_id = ? AND version = 1
                """, OffsetDateTime.class, s.materialId())).isNotNull();

        MaterialResponseTimeRecoveryService.RecoveryResult again =
                recoveryService.recover(s.materialId(), 1, receiptId, false, "test-admin");
        assertThat(again.status()).isEqualTo("ALREADY_RECOVERED");
        assertThat(jdbc.queryForObject("""
                SELECT time_recovered_at FROM material_response WHERE material_id = ? AND version = 1
                """, OffsetDateTime.class, s.materialId())).isNotNull();
    }

    @Test
    void u02ClientCannotForgeSubmissionTimeFields() throws Exception {
        Scenario s = materialScenario("u02-forge");
        mockMvc.perform(post("/api/v1/material-requests/{id}/responses", s.materialId())
                        .header("Authorization", bearer(s.member())).header("Idempotency-Key", "u02-forge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"伪造时间","expectedRevision":1,
                                 "createdAt":"2026-11-01T10:00:00Z","createdAtConfidence":"EXACT"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("FIELD_NOT_ALLOWED"));
        assertThat(responseCount(s.materialId())).isZero();
        assertThat(materialStatus(s.materialId())).isEqualTo("OPEN");
        assertThat(materialResponseVersion(s.materialId())).isZero();
    }

    private void makeLegacyUnknown(String materialId, int version) {
        jdbc.update("""
                UPDATE material_response
                SET legacy_recorded_at = created_at, created_at = NULL,
                    created_at_confidence = 'UNKNOWN', time_source = 'LEGACY_UNKNOWN'
                WHERE material_id = ? AND version = ?
                """, materialId, version);
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
    }

    private void assertExact(JsonNode item, String expected) {
        assertThat(item.get("createdAt").asText()).isEqualTo(expected);
        assertThat(item.get("createdAtConfidence").asText()).isEqualTo("EXACT");
    }

    private void assertUnknown(JsonNode item, String content) {
        assertThat(item.has("createdAt")).isTrue();
        assertThat(item.get("createdAt").isNull()).isTrue();
        assertThat(item.get("createdAtConfidence").asText()).isEqualTo("UNKNOWN");
        assertThat(item.get("content").asText()).isEqualTo(content);
    }
}
