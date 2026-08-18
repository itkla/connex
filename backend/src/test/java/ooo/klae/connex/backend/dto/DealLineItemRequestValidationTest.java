package ooo.klae.connex.backend.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

class DealLineItemRequestValidationTest {
    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();

    @AfterAll
    static void closeValidatorFactory() {
        FACTORY.close();
    }

    @Test
    void invalidDiscountTypeUsesHumanValidationMessage() {
        DealLineItemRequest request = new DealLineItemRequest();
        request.setQuantity(BigDecimal.ONE);
        request.setDiscountType("fixed");

        Set<ConstraintViolation<DealLineItemRequest>> violations = VALIDATOR.validate(request);

        assertEquals(1, violations.size());
        ConstraintViolation<DealLineItemRequest> violation = violations.iterator().next();
        assertEquals("discountType", violation.getPropertyPath().toString());
        assertEquals(
            "Choose either an amount or a percentage discount.", violation.getMessage());
    }
}
