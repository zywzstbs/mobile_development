package cn.gulupai.server.common.idempotency;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyCipherTest {

    private static final String KEY = "dGVzdC1vbmx5LWtleS0wMTIzNDU2Nzg5YWJjZGVmMTI=";

    @Test
    void encryptsWithRandomIvAndDecrypts() {
        IdempotencyProperties properties = new IdempotencyProperties();
        properties.setEncryptionKey(KEY);
        properties.setKeyId("test-v1");
        IdempotencyCipher cipher = new IdempotencyCipher(properties);

        String first = cipher.encrypt("{\"revision\":1}");
        String second = cipher.encrypt("{\"revision\":1}");

        assertThat(first).isNotEqualTo(second);
        assertThat(cipher.decrypt(first)).isEqualTo("{\"revision\":1}");
        assertThat(cipher.keyId()).isEqualTo("test-v1");
    }

    @Test
    void rejectsMissingOrWrongSizedKey() {
        IdempotencyProperties missing = new IdempotencyProperties();
        assertThatThrownBy(() -> new IdempotencyCipher(missing))
                .isInstanceOf(IllegalStateException.class);

        IdempotencyProperties shortKey = new IdempotencyProperties();
        shortKey.setEncryptionKey("c2hvcnQ=");
        assertThatThrownBy(() -> new IdempotencyCipher(shortKey))
                .isInstanceOf(IllegalStateException.class);
    }
}
