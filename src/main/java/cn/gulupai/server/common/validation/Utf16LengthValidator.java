package cn.gulupai.server.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class Utf16LengthValidator implements ConstraintValidator<Utf16Length, CharSequence> {

    private int min;
    private int max;

    @Override
    public void initialize(Utf16Length annotation) {
        this.min = annotation.min();
        this.max = annotation.max();
        if (min < 0 || max < min) {
            throw new IllegalStateException("@Utf16Length 的 min/max 配置无效");
        }
    }

    @Override
    public boolean isValid(CharSequence value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        int length = value.length();
        return length >= min && length <= max;
    }
}
