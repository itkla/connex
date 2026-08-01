package ooo.klae.connex.backend.ai.brief;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class DealBriefValidator {
    private DealBriefValidator() {
    }

    static Optional<DealBriefContent> validate(
            DealBriefContent content, Map<String, DealBriefSource> sourceRegistry) {
        if (content == null || content.sections() == null
                || content.sections().size() < DealBriefService.MIN_SECTIONS
                || content.sections().size() > DealBriefService.MAX_SECTIONS
                || sourceRegistry == null) {
            return Optional.empty();
        }
        List<DealBriefContent.Section> sections = new ArrayList<>();
        for (DealBriefContent.Section section : content.sections()) {
            if (!valid(section, sourceRegistry)) {
                return Optional.empty();
            }
            sections.add(new DealBriefContent.Section(
                    section.title().strip(),
                    section.body().strip(),
                    List.copyOf(section.sourceIds())));
        }
        return Optional.of(new DealBriefContent(List.copyOf(sections)));
    }

    private static boolean valid(
            DealBriefContent.Section section, Map<String, DealBriefSource> sourceRegistry) {
        if (section == null
                || isBlank(section.title())
                || isBlank(section.body())
                || codePoints(section.title()) > DealBriefService.MAX_TITLE_CHARS
                || codePoints(section.body()) > DealBriefService.MAX_BODY_CHARS
                || section.title().contains("{{")
                || section.body().contains("{{")
                || section.sourceIds() == null
                || section.sourceIds().isEmpty()) {
            return false;
        }
        for (String sourceId : section.sourceIds()) {
            if (sourceId == null || !sourceRegistry.containsKey(sourceId)) {
                return false;
            }
        }
        return true;
    }

    private static int codePoints(String value) {
        return value.codePointCount(0, value.length());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
