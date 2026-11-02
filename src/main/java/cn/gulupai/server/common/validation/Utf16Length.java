package cn.gulupai.server.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 文本长度按 Java String.length()（UTF-16 code unit）计算，与接口文档一致。
 * 一个基本汉字计 1，一个 emoji/代理对计 2。null 交由 @NotNull 处理。
 */
@Documented
@Constraint(validatedBy = Utf16LengthValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT,
        ElementType.METHOD, ElementType.TYPE_USE, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface Utf16Length {

    String message() default "文本长度不符合要求";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    int min() default 0;

    int max() default 1_000_000;
}
