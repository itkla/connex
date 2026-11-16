package ooo.klae.connex.backend.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import ooo.klae.connex.backend.password.PasswordPolicy;

/** Reports the first missing character class using the canonical password policy. */
public final class PasswordCharactersValidator
        implements ConstraintValidator<ValidPasswordCharacters, String> {
    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isEmpty()) {
            return true;
        }
        String error = PasswordPolicy.characterError(value);
        if (error == null) {
            return true;
        }
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(error).addConstraintViolation();
        return false;
    }
}
