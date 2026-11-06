package ooo.klae.connex.backend.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/** Optional ISO 4217 monetary currency; creation may opt into defaulting blank values. */
@Documented
@Constraint(validatedBy = CurrencyValidator.class)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidCurrency {
    /** Creation validates new codes; updates defer unchanged-value checks to the scoped service. */
    interface OnCreate extends jakarta.validation.groups.Default {
    }

    /** Whole-record edits require currency without changing creation's defaulting contract. */
    interface OnUpdate extends jakarta.validation.groups.Default {
    }

    String message() default "must be a supported ISO 4217 monetary code";
    boolean allowBlank() default false;
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
