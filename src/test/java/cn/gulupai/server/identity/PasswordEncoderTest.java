package cn.gulupai.server.identity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordEncoderTest {

    private final GulupaiPasswordEncoder encoder = new GulupaiPasswordEncoder(4);

    @Test
    void supportsFullContractLengthWithoutBcrypt72ByteTruncation() {
        String first = "a".repeat(256);
        String second = "a".repeat(72) + "b" + "c".repeat(183);
        String hash = encoder.encode(first);

        assertThat(hash).startsWith("$2");
        assertThat(encoder.matches(first, hash)).isTrue();
        assertThat(encoder.matches(second, hash)).isFalse();
        assertThat(encoder.matches("a".repeat(72), hash)).isFalse();
    }
}
