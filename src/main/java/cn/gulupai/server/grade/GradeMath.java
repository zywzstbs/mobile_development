package cn.gulupai.server.grade;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 履约分先按比例乘 100 一次 HALF_UP 成整数。课程分用这个整数再与权重、成果分和调整相加，只在最后舍入并限制到 0～100。
 */
public final class GradeMath {

    private GradeMath() {
    }

    public static int performance(BigDecimal accepted, BigDecimal responsibility) {
        if (responsibility.signum() <= 0) {
            throw new IllegalArgumentException("责任分母必须大于 0");
        }
        return accepted.multiply(BigDecimal.valueOf(100))
                .divide(responsibility, 0, RoundingMode.HALF_UP)
                .intValueExact();
    }

    public static int courseGrade(BigDecimal weight, int performance, int quality, int delta) {
        BigDecimal score = weight.multiply(BigDecimal.valueOf(performance))
                .add(BigDecimal.ONE.subtract(weight).multiply(BigDecimal.valueOf(quality)))
                .add(BigDecimal.valueOf(delta));
        int rounded = score.setScale(0, RoundingMode.HALF_UP).intValueExact();
        return Math.max(0, Math.min(100, rounded));
    }
}
