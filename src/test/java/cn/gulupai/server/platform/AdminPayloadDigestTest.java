package cn.gulupai.server.platform;

import cn.gulupai.server.common.idempotency.CanonicalJson;
import cn.gulupai.server.common.security.Hashing;
import cn.gulupai.server.platform.application.AdminPayloadDigest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AdminPayloadDigestTest {

    private static final String VECTOR = """
            {"action":"TEACHER_APPLICATION_DECIDE","decision":"APPROVE","expectedRevision":3,"note":"核验通过","precondition":null,"reason":null,"status":null,"targetId":"application_1","targetType":"TEACHER_APPLICATION","targetUserId":null}""";

    @Test
    void digestMatchesCanonicalJsonAndOmitsCredentials() {
        AdminPayloadDigest digest = new AdminPayloadDigest(new CanonicalJson(new ObjectMapper()));
        String hash = digest.digest("TEACHER_APPLICATION_DECIDE", "TEACHER_APPLICATION", "application_1",
                3, null, "APPROVE", null, "  ", "  核验通过  ", null);
        assertThat(hash).isEqualTo(Hashing.sha256Hex(VECTOR.strip()));
        assertThat(digest.digest("TEACHER_APPLICATION_DECIDE", "TEACHER_APPLICATION", "application_1",
                3, null, "APPROVE", null, null, "核验通过", null)).isEqualTo(hash);
        assertThat(hash).hasSize(64).matches("[0-9a-f]+");
    }
}
