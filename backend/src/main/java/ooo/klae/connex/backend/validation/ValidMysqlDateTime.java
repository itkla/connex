package ooo.klae.connex.backend.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * Validates optional MySQL datetime strings in {@code yyyy-MM-dd HH:mm:ss} form.
 */
@Documented
@Constraint(validatedBy = MysqlDateTimeValidator.class)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidMysqlDateTime {
    String message() default "must use a valid YYYY-MM-DD HH:mm:ss datetime";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
