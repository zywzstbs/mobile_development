package cn.gulupai.server.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 十进制字符串（金额/点数/权重）：不使用 float/double，最多 2 位小数。
 * 输入不接受指数形式、前导零、正号或超过 scale 的小数；null 交由 @NotNull 处理。
 */
@Documented
@Constraint(validatedBy = DecimalStringValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT,
        ElementType.METHOD, ElementType.TYPE_USE, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface DecimalString {

    String message() default "十进制数值格式无效";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    /** 最小值的十进制字符串，空表示不限制。 */
    String min() default "";

    /** 最大值的十进制字符串，空表示不限制。 */
    String max() default "";

    int maxScale() default 2;

    boolean allowNegative() default true;
}
