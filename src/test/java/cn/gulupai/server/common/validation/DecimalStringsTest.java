package cn.gulupai.server.common.validation;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DecimalStringsTest {

    @Test
    void canonicalAlwaysHasTwoDecimals() {
        assertThat(DecimalStrings.canonical("12.3")).isEqualTo("12.30");
        assertThat(DecimalStrings.canonical("0")).isEqualTo("0.00");
        assertThat(DecimalStrings.canonical("1000000")).isEqualTo("1000000.00");
    }

    @Test
    void rejectsAmbiguousInput() {
        for (String value : new String[]{"01", "1.", ".5", "1e2", "+1", "1.234", "-", ""}) {
            assertThatThrownBy(() -> DecimalStrings.parse(value))
                    .as(value)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void exactCanonicalizationRejectsHiddenPrecision() {
        assertThatThrownBy(() -> DecimalStrings.canonical(new BigDecimal("1.005")))
                .isInstanceOf(ArithmeticException.class);
        assertThat(DecimalStrings.canonical(new BigDecimal("1.005"), RoundingMode.HALF_UP))
                .isEqualTo("1.01");
    }
}
