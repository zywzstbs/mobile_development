package cn.gulupai.server.platform;

import cn.gulupai.server.common.security.Hashing;
import cn.gulupai.server.notice.application.NoticeOutboxWorker;
import cn.gulupai.server.platform.application.AdminCursorCodec;
import cn.gulupai.server.platform.application.PlatformSuperAdminService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Review probes assert observed defects. Keep outside src/test and do not use
 * these assertions as the expected behavior after a fix.
 * Temporarily compile in the same package and run P71FirstReviewIT#p71*.
 */
class P71FirstReviewIT extends PlatformReadPrivacyP66IT {

    @Autowired private MockMvc reviewMvc;
    @Autowired private ObjectMapper reviewMapper;
    @Autowired private JdbcTemplate reviewJdbc;
    @Autowired private PlatformSuperAdminService reviewAdmins;

    @Test
    void p71DirectoryLabelsAnUnusableGrantAsActive() throws Exception {
        String user = "user_demo_student_01";
        reviewAdmins.grantInitial("user_demo_teacher_01", user, "review isolated grant", false);
        reviewJdbc.update("DELETE FROM user_role WHERE user_id = ? AND role = 'SUPER_ADMIN'", user);
        String admin = reviewLogin("teacher_demo_01", "demo-password-03");
        JsonNode directory = reviewData(reviewMvc.perform(get("/api/v1/admin/super-admins")
                .header("Authorization", "Bearer " + admin)).andExpect(status().isOk()).andReturn());
        JsonNode entry = null;
        for (JsonNode row : directory.path("items")) {
            if (user.equals(row.path("userId").asText())) {
                entry = row;
            }
        }
        assertThat(entry).isNotNull();
        assertThat(entry.path("status").asText()).isEqualTo("ACTIVE");
        String target = reviewLogin("student_demo_01", "demo-password-01");
        reviewMvc.perform(get("/api/v1/admin/users")
                .header("Authorization", "Bearer " + target)).andExpect(status().isForbidden());
    }

    @Test
    void p71ContinuationExtendsTheOriginalCursorExpiry() throws Exception {
        String admin = reviewLogin("teacher_demo_01", "demo-password-03");
        JsonNode first = reviewData(reviewMvc.perform(get("/api/v1/admin/users").param("limit", "1")
                .header("Authorization", "Bearer " + admin)).andExpect(status().isOk()).andReturn());
        String cursor = first.path("nextCursor").asText();
        String hash = Hashing.sha256Hex(AdminCursorCodec.decode(cursor));
        reviewJdbc.update("UPDATE cursor_snapshot SET expires_at = CURRENT_TIMESTAMP + interval '60 seconds' "
                + "WHERE token_hash = ?", hash);
        OffsetDateTime original = reviewJdbc.queryForObject(
                "SELECT expires_at FROM cursor_snapshot WHERE token_hash = ?", OffsetDateTime.class, hash);
        JsonNode next = reviewData(reviewMvc.perform(get("/api/v1/admin/users").param("limit", "1")
                .param("cursor", cursor).header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk()).andReturn());
        String nextHash = Hashing.sha256Hex(AdminCursorCodec.decode(next.path("nextCursor").asText()));
        OffsetDateTime extended = reviewJdbc.queryForObject(
                "SELECT expires_at FROM cursor_snapshot WHERE token_hash = ?", OffsetDateTime.class, nextHash);
        assertThat(extended).isAfter(original.plusMinutes(10));
    }

    @Test
    void p71OutboxTruncatesAFractionalSourceVersion() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString())).thenReturn(List.of(Map.of(
                "id", "review_outbox",
                "project_id", "review_project",
                "event_type", "CHAT_MESSAGE_SENT",
                "payload", "{\"messageId\":\"review_message\",\"mentions\":[\"review_user\"],\"sourceVersion\":1.75}")));
        int drained = new NoticeOutboxWorker(jdbc, reviewMapper).drain();
        assertThat(drained).isEqualTo(1);
        verify(jdbc).update(contains("INSERT INTO notification("), anyString(), eq("review_user"),
                eq("review_message"), anyString(), eq(1), any(), any());
    }

    private String reviewLogin(String login, String password) throws Exception {
        return reviewData(reviewMvc.perform(post("/api/v1/auth/sessions").contentType(MediaType.APPLICATION_JSON)
                .content(reviewMapper.writeValueAsString(Map.of("loginId", login, "password", password))))
                .andExpect(status().isOk()).andReturn()).path("accessToken").asText();
    }

    private JsonNode reviewData(MvcResult result) throws Exception {
        return reviewMapper.readTree(result.getResponse().getContentAsString()).path("data");
    }
}
