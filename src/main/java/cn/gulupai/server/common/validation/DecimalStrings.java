package cn.gulupai.server.common.validation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.regex.Pattern;

/**
 * 十进制字符串解析与规范化。业务计算使用 BigDecimal；对外输出两位小数字符串。
 */
public final class DecimalStrings {

    public static final int SCALE = 2;
    private static final Pattern INPUT = Pattern.compile("^-?(0|[1-9]\\d*)(\\.\\d{1,2})?$");
    private static final int MAX_INPUT_LENGTH = 32;

    private DecimalStrings() {
    }

    public static boolean isWellFormed(String value) {
        return value != null
                && value.length() > 0
                && value.length() <= MAX_INPUT_LENGTH
                && INPUT.matcher(value).matches();
    }

    public static BigDecimal parse(String value) {
        if (!isWellFormed(value)) {
            throw new IllegalArgumentException("十进制数值格式无效");
        }
        return new BigDecimal(value);
    }

    public static String canonical(String value) {
        return canonical(parse(value));
    }

    public static String canonical(BigDecimal value) {
        return value.setScale(SCALE, RoundingMode.UNNECESSARY).toPlainString();
    }

    public static String canonical(BigDecimal value, RoundingMode roundingMode) {
        return value.setScale(SCALE, roundingMode).toPlainString();
    }
}
