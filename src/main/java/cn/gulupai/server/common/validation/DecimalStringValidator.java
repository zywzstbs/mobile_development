package cn.gulupai.server.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.math.BigDecimal;
import java.util.regex.Pattern;

public class DecimalStringValidator implements ConstraintValidator<DecimalString, CharSequence> {

    private static final int MAX_INPUT_LENGTH = 32;

    private Pattern pattern;
    private BigDecimal min;
    private BigDecimal max;

    @Override
    public void initialize(DecimalString annotation) {
        if (annotation.maxScale() < 0 || annotation.maxScale() > 2) {
            throw new IllegalStateException("@DecimalString 当前只支持 0～2 位小数");
        }
        String sign = annotation.allowNegative() ? "-?" : "";
        String fraction = annotation.maxScale() == 0
                ? ""
                : "(\\.\\d{1," + annotation.maxScale() + "})?";
        this.pattern = Pattern.compile("^" + sign + "(0|[1-9]\\d*)" + fraction + "$");
        this.min = parseBound(annotation.min());
        this.max = parseBound(annotation.max());
        if (min != null && max != null && min.compareTo(max) > 0) {
            throw new IllegalStateException("@DecimalString 的 min 不能大于 max");
        }
    }

    @Override
    public boolean isValid(CharSequence value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        if (value.length() == 0 || value.length() > MAX_INPUT_LENGTH) {
            return false;
        }
        String text = value.toString();
        if (!pattern.matcher(text).matches()) {
            return false;
        }
        BigDecimal decimal;
        try {
            decimal = new BigDecimal(text);
        } catch (NumberFormatException ex) {
            return false;
        }
        if (decimal.scale() > 2) {
            return false;
        }
        if (min != null && decimal.compareTo(min) < 0) {
            return false;
        }
        return max == null || decimal.compareTo(max) <= 0;
    }

    private static BigDecimal parseBound(String bound) {
        if (bound == null || bound.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(bound);
        } catch (NumberFormatException ex) {
            throw new IllegalStateException("@DecimalString 的 min/max 不是合法十进制数", ex);
        }
    }
}
