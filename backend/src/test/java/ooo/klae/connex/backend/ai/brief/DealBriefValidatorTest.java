package ooo.klae.connex.backend.ai.brief;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class DealBriefValidatorTest {
    private static final Map<String, DealBriefSource> SOURCES = Map.of(
            "deal.0", new DealBriefSource("deal", 29),
            "person.0", new DealBriefSource("person", 73));

    @Test
    void validate_rejectsUncitedSection() {
        DealBriefContent content = content(section("Next moves", "Call the champion.", List.of()));

        assertTrue(DealBriefValidator.validate(content, SOURCES).isEmpty());
    }

    @Test
    void validate_rejectsSourceOutsideRegistry() {
        DealBriefContent content = content(
                section("Next moves", "Call the champion.", List.of("task.9")));

        assertTrue(DealBriefValidator.validate(content, SOURCES).isEmpty());
    }

    @Test
    void validate_rejectsWrongSectionCount() {
        DealBriefContent content = new DealBriefContent(List.of(
                section("Status", "Discovery is active.", List.of("deal.0")),
                section("Next moves", "Call the champion.", List.of("person.0"))));

        assertTrue(DealBriefValidator.validate(content, SOURCES).isEmpty());
    }

    @Test
    void validate_rejectsLeftoverMaskToken() {
        DealBriefContent content = content(
                section("Relationship", "Contact {{P1}} soon.", List.of("person.0")));

        assertTrue(DealBriefValidator.validate(content, SOURCES).isEmpty());
    }

    @Test
    void validate_acceptsGroundedContent() {
        assertTrue(DealBriefValidator.validate(content(null), SOURCES).isPresent());
    }

    private static DealBriefContent content(DealBriefContent.Section replacement) {
        return new DealBriefContent(List.of(
                section("Account", "The account is strategic.", List.of("deal.0")),
                section("Status", "Discovery is active.", List.of("deal.0")),
                replacement == null
                        ? section("Next moves", "Call the champion.", List.of("person.0"))
                        : replacement));
    }

    private static DealBriefContent.Section section(
            String title, String body, List<String> sourceIds) {
        return new DealBriefContent.Section(title, body, sourceIds);
    }
}
