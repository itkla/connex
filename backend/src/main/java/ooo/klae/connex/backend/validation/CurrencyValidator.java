package ooo.klae.connex.backend.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** Adapts the shared monetary currency contract to request Bean Validation. */
public final class CurrencyValidator implements ConstraintValidator<ValidCurrency, String> {
    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return value == null || CurrencyCodes.isSupported(value);
    }
}
