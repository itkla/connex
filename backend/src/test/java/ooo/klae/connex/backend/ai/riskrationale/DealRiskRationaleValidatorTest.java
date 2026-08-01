package ooo.klae.connex.backend.ai.riskrationale;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
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
    void validate_rejectsZeroRecommendedActions() {
        DealRiskRationaleContent content = new DealRiskRationaleContent(
                "The deal is stalled.",
                List.of("stalled"),
                List.of(),
                null);

        assertTrue(DealRiskRationaleValidator.validate(content, FACTOR_CODES).isEmpty());
    }

    @Test
    void validate_rejectsFourRecommendedActions() {
        assertTrue(DealRiskRationaleValidator.validate(
                contentWithActions(4), FACTOR_CODES).isEmpty());
    }

    @Test
    void validate_acceptsOneToThreeRecommendedActions() {
        assertTrue(DealRiskRationaleValidator.validate(
                contentWithActions(1), FACTOR_CODES).isPresent());
        assertTrue(DealRiskRationaleValidator.validate(
                contentWithActions(2), FACTOR_CODES).isPresent());
        assertTrue(DealRiskRationaleValidator.validate(
                contentWithActions(3), FACTOR_CODES).isPresent());
    }

    private static DealRiskRationaleContent contentWithActions(int count) {
        List<DealRiskRationaleContent.RecommendedAction> actions = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            actions.add(new DealRiskRationaleContent.RecommendedAction(
                    "Action " + index, List.of("stalled", "close_overdue")));
        }
        return new DealRiskRationaleContent(
                "The deal is stalled.", List.of("stalled"), List.copyOf(actions), null);
    }
}
