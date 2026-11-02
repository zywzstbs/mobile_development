package cn.gulupai.server.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.regex.Pattern;

public class OpaqueIdValidator implements ConstraintValidator<OpaqueId, CharSequence> {

    private static final Pattern ALLOWED = Pattern.compile("[A-Za-z0-9_-]+");

    private int min;
    private int max;

    @Override
    public void initialize(OpaqueId annotation) {
        this.min = annotation.min();
        this.max = annotation.max();
        if (min < 0 || max < min) {
            throw new IllegalStateException("@OpaqueId 的 min/max 配置无效");
        }
    }

    @Override
    public boolean isValid(CharSequence value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        int length = value.length();
        return length >= min && length <= max && ALLOWED.matcher(value).matches();
    }
}
