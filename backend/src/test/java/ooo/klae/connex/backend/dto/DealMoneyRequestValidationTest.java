package ooo.klae.connex.backend.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

class DealMoneyRequestValidationTest {
    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();
    private static final BigDecimal EXTREME = new BigDecimal("123456789E2147483639");
    private static final BigDecimal MAXIMUM = new BigDecimal("9999999999999.99");

    @AfterAll
    static void closeValidatorFactory() {
        FACTORY.close();
    }

    @Test
    void dealDtoRejectsExtremeExponentsWithoutRejectingColumnBounds() {
        DealDto request = new DealDto();
        request.setName("Deal");
        request.setCurrency("USD");
        request.setPipeline(1);
        request.setStage(1);
        request.setValue(EXTREME);
        request.setActualValue(EXTREME);

        assertEquals(Set.of("value", "actualValue"), VALIDATOR.validate(request).stream()
            .map(violation -> violation.getPropertyPath().toString()).collect(Collectors.toSet()));
        request.setValue(MAXIMUM);
        request.setActualValue(EXTREME.negate());
        assertEquals(Set.of("actualValue"), VALIDATOR.validate(request).stream()
            .map(violation -> violation.getPropertyPath().toString()).collect(Collectors.toSet()));
        request.setActualValue(MAXIMUM);
        assertTrue(VALIDATOR.validate(request).isEmpty());
        request.setActualValue(MAXIMUM.negate());
        assertTrue(VALIDATOR.validate(request).isEmpty());
    }

    @Test
    void valueUpdateRejectsExtremeExponentWithoutRejectingColumnBound() {
        DealValueUpdateRequest request = new DealValueUpdateRequest();
        request.setValue(EXTREME);

        assertEquals(Set.of("value"), VALIDATOR.validate(request).stream()
            .map(violation -> violation.getPropertyPath().toString()).collect(Collectors.toSet()));
        request.setValue(MAXIMUM);
        assertTrue(VALIDATOR.validate(request).isEmpty());
    }

    @Test
    void closeRequestRejectsExtremeExponentWithoutRejectingColumnBound() {
        CloseDealRequest request = new CloseDealRequest();
        request.setWon(true);
        request.setActualValue(EXTREME);

        assertEquals(Set.of("actualValue"), VALIDATOR.validate(request).stream()
            .map(violation -> violation.getPropertyPath().toString()).collect(Collectors.toSet()));
        request.setActualValue(MAXIMUM);
        assertTrue(VALIDATOR.validate(request).isEmpty());
    }
}
