package ooo.klae.connex.backend.ai.riskrationale;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class DealRiskRationaleValidatorTest {
    private static final Set<String> FACTOR_CODES = Set.of("stalled", "close_overdue");

    @Test
    void validate_rejectsUnboundContent() {
        DealRiskRationaleContent content = new DealRiskRationaleContent(
                "The deal is stalled.", List.of(), List.of(), null);

        assertTrue(DealRiskRationaleValidator.validate(content, FACTOR_CODES).isEmpty());
    }

    @Test
    void validate_rejectsUnknownCode() {
        DealRiskRationaleContent content = new DealRiskRationaleContent(
                "The deal is stalled.",
                List.of("invented"),
                List.of(new DealRiskRationaleContent.RecommendedAction(
                        "Call today.", List.of("stalled"))),
                null);

        assertTrue(DealRiskRationaleValidator.validate(content, FACTOR_CODES).isEmpty());
    }

    @Test
    void validate_acceptsBoundContent() {
        DealRiskRationaleContent content = new DealRiskRationaleContent(
                "The deal is stalled.",
                List.of("stalled"),
                List.of(new DealRiskRationaleContent.RecommendedAction(
                        "Call today.", List.of("stalled", "close_overdue"))),
                null);

        assertTrue(DealRiskRationaleValidator.validate(content, FACTOR_CODES).isPresent());
    }
}
