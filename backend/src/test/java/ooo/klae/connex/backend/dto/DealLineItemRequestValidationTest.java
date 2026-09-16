package ooo.klae.connex.backend.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

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

    @Test
    void rejectsOperandsBeyondColumnScaleMagnitudeAndExponentBounds() {
        for (String value : List.of("1.0001", "10000000000000", "1E100000000", "1E-100000000",
                "1E2147483647", "12E2147483647", "1E-2147483647")) {
            DealLineItemRequest request = new DealLineItemRequest();
            BigDecimal operand = new BigDecimal(value);
            request.setUnitPrice(operand);
            request.setQuantity(operand);
            request.setDiscountValue(operand);
            request.setTaxRate(operand);
            Set<String> invalidFields = VALIDATOR.validate(request).stream()
                .map(violation -> violation.getPropertyPath().toString())
                .collect(Collectors.toSet());
            assertEquals(Set.of("unitPrice", "quantity", "discountValue", "taxRate"), invalidFields, value);
        }
    }

    @Test
    void acceptsExactPersistedOperandBounds() {
        DealLineItemRequest request = new DealLineItemRequest();
        request.setUnitPrice(new BigDecimal("9999999999999.99"));
        request.setQuantity(new BigDecimal("999999999999.999"));
        request.setDiscountValue(new BigDecimal("9999999999999.99"));
        request.setTaxRate(new BigDecimal("999.999"));
        assertTrue(VALIDATOR.validate(request).isEmpty());
    }
}
