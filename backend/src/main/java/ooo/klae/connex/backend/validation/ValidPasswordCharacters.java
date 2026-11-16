package ooo.klae.connex.backend.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/** Enforces the shared new-password character requirements; presence and length are separate. */
@Documented
@Constraint(validatedBy = PasswordCharactersValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidPasswordCharacters {
    String message() default "Password does not meet the character requirements";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
