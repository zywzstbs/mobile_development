package cn.gulupai.server.grade;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GradeMathTest {

    @Test
    void documentedNumbersUseOneHalfUpAndThenClamp() {
        assertThat(GradeMath.performance(new BigDecimal("20.00"), new BigDecimal("30.00"))).isEqualTo(67);
        assertThat(GradeMath.courseGrade(new BigDecimal("0.60"), 67, 80, 2)).isEqualTo(74);
        assertThat(GradeMath.performance(new BigDecimal("1.00"), new BigDecimal("8.00"))).isEqualTo(13);
        assertThat(GradeMath.courseGrade(new BigDecimal("0.50"), 50, 51, 0)).isEqualTo(51);
        assertThat(GradeMath.courseGrade(new BigDecimal("0.60"), 1, 2, 0)).isEqualTo(1);
        assertThat(GradeMath.courseGrade(new BigDecimal("0.60"), 100, 100, 50)).isEqualTo(100);
        assertThat(GradeMath.courseGrade(new BigDecimal("0.60"), 0, 0, -50)).isZero();
    }

    @Test
    void zeroDenominatorIsRejected() {
        assertThatThrownBy(() -> GradeMath.performance(new BigDecimal("1.00"), new BigDecimal("0.00")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
