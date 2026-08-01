package ooo.klae.connex.backend.ai.introrationale;

import java.util.List;
import java.util.Optional;
import java.util.Set;

final class IntroRationaleValidator {
    private IntroRationaleValidator() {
    }

    static Optional<IntroRationaleContent> validate(
            IntroRationaleContent content, Set<String> reasonCodes) {
        if (content == null || isBlank(content.rationale())
                || reasonCodes == null || reasonCodes.isEmpty()
                || content.reasonCodes() == null || content.reasonCodes().isEmpty()) {
            return Optional.empty();
        }
        for (String reasonCode : content.reasonCodes()) {
            if (reasonCode == null || !reasonCodes.contains(reasonCode)) {
                return Optional.empty();
            }
        }
        return Optional.of(new IntroRationaleContent(
                content.rationale(), List.copyOf(content.reasonCodes())));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
