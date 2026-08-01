package ooo.klae.connex.backend.ai.introrationale;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class IntroRationaleValidatorTest {
    private static final Set<String> REASON_CODES = Set.of(
            "mutual_connections", "shared_company");

    @Test
    void validate_rejectsUnboundContent() {
        IntroRationaleContent content = new IntroRationaleContent(
                "Alice should meet Bob.", List.of());

        assertTrue(IntroRationaleValidator.validate(content, REASON_CODES).isEmpty());
    }

    @Test
    void validate_rejectsUnknownCode() {
        IntroRationaleContent content = new IntroRationaleContent(
                "Alice should meet Bob.", List.of("invented"));

        assertTrue(IntroRationaleValidator.validate(content, REASON_CODES).isEmpty());
    }

    @Test
    void validate_acceptsBoundContent() {
        IntroRationaleContent content = new IntroRationaleContent(
                "Alice should meet Bob.", List.of("mutual_connections"));

        assertTrue(IntroRationaleValidator.validate(content, REASON_CODES).isPresent());
    }
}
