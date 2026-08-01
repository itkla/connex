package ooo.klae.connex.backend.ai.riskrationale;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

final class DealRiskRationaleValidator {
    private DealRiskRationaleValidator() {
    }

    static Optional<DealRiskRationaleContent> validate(
            DealRiskRationaleContent content, Set<String> factorCodes) {
        if (content == null || isBlank(content.narrative())
                || factorCodes == null || factorCodes.isEmpty()
                || !validCodes(content.narrativeFactorCodes(), factorCodes)
                || content.recommendedActions() == null
                || content.recommendedActions().size() > DealRiskRationaleService.MAX_ACTIONS) {
            return Optional.empty();
        }
        List<DealRiskRationaleContent.RecommendedAction> recommendedActions = new ArrayList<>();
        List<String> actions = new ArrayList<>();
        for (DealRiskRationaleContent.RecommendedAction action : content.recommendedActions()) {
            if (action == null || isBlank(action.text())
                    || !validCodes(action.factorCodes(), factorCodes)) {
                return Optional.empty();
            }
            recommendedActions.add(new DealRiskRationaleContent.RecommendedAction(
                    action.text(), List.copyOf(action.factorCodes())));
            actions.add(action.text());
        }
        if (content.actions() != null && !content.actions().isEmpty()
                && !content.actions().equals(actions)) {
            return Optional.empty();
        }
        return Optional.of(new DealRiskRationaleContent(
                content.narrative(),
                List.copyOf(content.narrativeFactorCodes()),
                List.copyOf(recommendedActions),
                List.copyOf(actions)));
    }

    private static boolean validCodes(List<String> citedCodes, Set<String> factorCodes) {
        if (citedCodes == null || citedCodes.isEmpty()) {
            return false;
        }
        for (String code : citedCodes) {
            if (code == null || !factorCodes.contains(code)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
