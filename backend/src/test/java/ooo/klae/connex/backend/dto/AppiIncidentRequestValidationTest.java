package ooo.klae.connex.backend.dto;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

class AppiIncidentRequestValidationTest {
    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();

    @AfterAll
    static void closeValidatorFactory() {
        FACTORY.close();
    }

    @Test
    void acceptsSupportedOrOmittedMysqlDatetimeYears() {
        assertTrue(violations(null).isEmpty());
        assertTrue(violations(LocalDateTime.of(1000, 1, 1, 0, 0)).isEmpty());
        assertTrue(violations(LocalDateTime.of(9999, 12, 31, 23, 59)).isEmpty());
    }

    @Test
    void rejectsDatetimeYearsOutsideMysqlRange() {
        assertFalse(violations(LocalDateTime.of(999, 12, 31, 23, 59)).isEmpty());
        assertFalse(violations(LocalDateTime.of(10000, 1, 1, 0, 0)).isEmpty());
    }

    private static Set<ConstraintViolation<AppiIncidentRequest>> violations(LocalDateTime timestamp) {
        AppiIncidentRequest request = new AppiIncidentRequest();
        request.setTitle("Incident");
        request.setOccurredFrom(timestamp);
        return VALIDATOR.validate(request);
    }
}
