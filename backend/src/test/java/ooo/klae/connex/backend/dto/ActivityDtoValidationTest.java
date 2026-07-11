package ooo.klae.connex.backend.dto;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

class ActivityDtoValidationTest {
    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();

    @AfterAll
    static void closeValidatorFactory() {
        FACTORY.close();
    }

    @Test
    void acceptsValidOrOmittedTimestamps() {
        assertTrue(timestampViolations("2024-02-29 23:59:59").isEmpty());
        assertTrue(timestampViolations(null).isEmpty());
        assertTrue(timestampViolations("").isEmpty());
    }

    @Test
    void rejectsMalformedOrImpossibleTimestamps() {
        assertFalse(timestampViolations("2024-02-30 10:00:00").isEmpty());
        assertFalse(timestampViolations("2024-01-01T10:00:00").isEmpty());
        assertFalse(timestampViolations("2024-01-01 10:00").isEmpty());
        assertFalse(timestampViolations(" 2024-01-01 10:00:00").isEmpty());
        assertFalse(timestampViolations("0999-12-31 23:59:59").isEmpty());
        assertFalse(timestampViolations("+10000-01-01 00:00:00").isEmpty());
    }

    private Set<ConstraintViolation<ActivityDto>> timestampViolations(String timestamp) {
        ActivityDto dto = new ActivityDto();
        dto.setType("call");
        dto.setSubject("Follow up");
        dto.setTimestamp(timestamp);
        return VALIDATOR.validate(dto);
    }
}
