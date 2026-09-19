package ooo.klae.connex.backend.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * Request DTOs whose decimals persist into bounded MySQL columns must refuse extreme exponents at
 * the boundary. Hibernate Validator computes {@code @Digits} integer digits in {@code int}, so an
 * exponent such as {@code 1E2147483647} overflows that check; {@code @DecimalMax} compares by
 * magnitude and is the backstop.
 */
class ExtremeExponentRequestValidationTest {
    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();
    private static final List<String> EXTREMES = List.of(
        "1E2147483647", "123456789E2147483639", "1E300000000", "1E-300000000", "1E-2147483647");
    private static final BigDecimal MONEY_MAXIMUM = new BigDecimal("9999999999999.99");

    @AfterAll
    static void closeValidatorFactory() {
        FACTORY.close();
    }

    @Test
    void approvalPolicyMinTotalRejectsExtremeExponentsWithoutRejectingColumnBound() {
        for (String extreme : EXTREMES) {
            assertEquals(Set.of("minTotal"), invalidFields(approvalPolicy(new BigDecimal(extreme))), extreme);
        }
        assertTrue(VALIDATOR.validate(approvalPolicy(MONEY_MAXIMUM)).isEmpty());
    }

    @Test
    void campaignBudgetRejectsExtremeExponentsWithoutRejectingColumnBound() {
        for (String extreme : EXTREMES) {
            assertEquals(Set.of("budgetAmount"), invalidFields(campaign(new BigDecimal(extreme))), extreme);
        }
        assertTrue(VALIDATOR.validate(campaign(MONEY_MAXIMUM)).isEmpty());
    }

    @Test
    void reportGoalTargetRejectsExtremeExponentsWithoutRejectingColumnBound() {
        for (String extreme : EXTREMES) {
            assertEquals(Set.of("targetValue"), invalidFields(goal(new BigDecimal(extreme))), extreme);
        }
        assertTrue(VALIDATOR.validate(goal(MONEY_MAXIMUM)).isEmpty());
    }

    @Test
    void productPricingRejectsExtremeExponentsAndOutOfColumnValuesWithoutRejectingColumnBounds() {
        for (String invalid : List.of(
                "1E2147483647", "123456789E2147483639", "1E300000000", "1E-300000000", "1E-2147483647",
                "10000000000000", "1.0001")) {
            BigDecimal operand = new BigDecimal(invalid);
            assertEquals(Set.of("unitPrice", "taxRate"), invalidFields(product(operand, operand)), invalid);
        }
        assertTrue(VALIDATOR.validate(product(MONEY_MAXIMUM, new BigDecimal("999.999"))).isEmpty());
    }

    private static <T> Set<String> invalidFields(T request) {
        return VALIDATOR.validate(request).stream()
            .map(ConstraintViolation::getPropertyPath)
            .map(Object::toString)
            .collect(Collectors.toSet());
    }

    private static ApprovalPolicyDto approvalPolicy(BigDecimal minTotal) {
        ApprovalPolicyDto policy = new ApprovalPolicyDto();
        policy.setName("Large deals");
        policy.setCurrency("USD");
        policy.setMinTotal(minTotal);
        return policy;
    }

    private static CampaignRequest campaign(BigDecimal budgetAmount) {
        return new CampaignRequest(
            "Launch", null, "email", null, null, budgetAmount, "USD", null, null, null);
    }

    private static ReportGoalRequest goal(BigDecimal targetValue) {
        return new ReportGoalRequest(
            null, "won_revenue", "month", LocalDate.of(2026, 9, 1), targetValue, "USD");
    }

    private static ProductDto product(BigDecimal unitPrice, BigDecimal taxRate) {
        ProductDto product = new ProductDto();
        product.setName("Seat");
        product.setUnitPrice(unitPrice);
        product.setTaxRate(taxRate);
        return product;
    }
}
