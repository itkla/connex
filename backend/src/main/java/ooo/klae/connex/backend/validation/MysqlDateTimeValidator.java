package ooo.klae.connex.backend.validation;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Locale;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Strict validator for optional MySQL datetime strings.
 */
public final class MysqlDateTimeValidator implements ConstraintValidator<ValidMysqlDateTime, String> {
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter
        .ofPattern("uuuu-MM-dd HH:mm:ss", Locale.ROOT)
        .withResolverStyle(ResolverStyle.STRICT);

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank()) {
            return true;
        }
        if (!value.equals(value.trim())) {
            return false;
        }
        try {
            LocalDateTime timestamp = LocalDateTime.parse(value, FORMATTER);
            return timestamp.getYear() >= 1000 && timestamp.getYear() <= 9999;
        } catch (DateTimeParseException exception) {
            return false;
        }
    }
}
