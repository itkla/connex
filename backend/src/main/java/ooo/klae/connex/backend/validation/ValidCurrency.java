package ooo.klae.connex.backend.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/** Optional ISO 4217 monetary currency; null is absence, while blank values are invalid. */
@Documented
@Constraint(validatedBy = CurrencyValidator.class)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidCurrency {
    String message() default "must be a supported ISO 4217 monetary code";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
