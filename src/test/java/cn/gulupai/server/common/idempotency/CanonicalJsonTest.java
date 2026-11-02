package cn.gulupai.server.common.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CanonicalJsonTest {

    private final CanonicalJson canonical = new CanonicalJson(new ObjectMapper());

    @Test
    void objectKeyOrderDoesNotChangeDigest() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("b", 2);
        first.put("a", Map.of("d", 4, "c", 3));

        Map<String, Object> second = new LinkedHashMap<>();
        second.put("a", Map.of("c", 3, "d", 4));
        second.put("b", 2);

        assertThat(canonical.hash(first)).isEqualTo(canonical.hash(second));
    }

    @Test
    void arrayOrderAndStringWhitespaceArePreserved() {
        assertThat(canonical.hash(Map.of("a", List.of(1, 2))))
                .isNotEqualTo(canonical.hash(Map.of("a", List.of(2, 1))));
        assertThat(canonical.hash(Map.of("a", "x")))
                .isNotEqualTo(canonical.hash(Map.of("a", "x ")));
        assertThat(canonical.hash(Map.of("a", 1)))
                .isNotEqualTo(canonical.hash(Map.of("a", "1")));
    }
}
