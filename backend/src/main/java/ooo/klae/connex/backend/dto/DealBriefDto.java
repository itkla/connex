package ooo.klae.connex.backend.dto;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Presentation-only AI brief for a deal, or a graceful unavailability result. The structured
 * {@link #sections} are the source of truth; {@link #brief} is a plain-text flattening retained for
 * one release so older clients keep rendering.
 */
@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class DealBriefDto {
    private static final Set<String> UNAVAILABLE_REASONS = Set.of(
            "not_configured", "provider_error", "rate_limited", "insufficient_data");

    private final int dealId;
    private final boolean available;
    private final List<Section> sections;
    private final String brief;
    private final String generatedAt;
    private final int warnings;
    private final boolean degraded;
    private final String reason;

    /**
     * Creates an available brief response.
     * @param dealId summarized deal
     * @param sections demasked, ordered brief sections
     * @param generatedAt ISO generation instant
     * @param warnings demasking warning count
     * @param degraded whether optional deterministic context was unavailable
     * @return available response
     */
    public static DealBriefDto of(
            int dealId,
            List<Section> sections,
            String generatedAt,
            int warnings,
            boolean degraded) {
        List<Section> safe = List.copyOf(Objects.requireNonNull(sections, "sections"));
        return new DealBriefDto(
                dealId,
                true,
                safe,
                flatten(safe),
                Objects.requireNonNull(generatedAt, "generatedAt"),
                warnings,
                degraded,
                null);
    }

    /**
     * Creates a graceful unavailability response.
     * @param dealId requested deal
     * @param reason stable unavailability reason
     * @return unavailable response
     */
    public static DealBriefDto unavailable(int dealId, String reason) {
        if (!UNAVAILABLE_REASONS.contains(reason)) {
            throw new IllegalArgumentException("Unsupported deal brief unavailability reason");
        }
        return new DealBriefDto(dealId, false, null, null, null, 0, false, reason);
    }

    private static String flatten(List<Section> sections) {
        StringBuilder flattened = new StringBuilder();
        for (Section section : sections) {
            if (!flattened.isEmpty()) {
                flattened.append("\n\n");
            }
            if (!section.title().isBlank()) {
                flattened.append(section.title()).append('\n');
            }
            flattened.append(section.body());
        }
        return flattened.toString();
    }

    /**
     * A single titled brief section.
     * @param title short section heading
     * @param body plain-text section body
     * @param citations real, in-context records that informed the section — structurally grounded
     *     (each id is a current workspace record that was in the prompt), not a semantic claim that
     *     any single sentence is proven by a given record
     */
    public record Section(String title, String body, List<Citation> citations) {

        public Section {
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(body, "body");
            citations = List.copyOf(Objects.requireNonNull(citations, "citations"));
        }
    }

    /**
     * One translated deal-brief citation.
     * @param sourceId positional prompt id (e.g. {@code note.0}) that may appear in section prose
     * @param kind stable record kind
     * @param id real workspace-scoped record id
     */
    public record Citation(String sourceId, String kind, int id) {

        public Citation {
            Objects.requireNonNull(sourceId, "sourceId");
            if (sourceId.isBlank()) {
                throw new IllegalArgumentException("Citation sourceId must be non-blank");
            }
            Objects.requireNonNull(kind, "kind");
            if (id <= 0) {
                throw new IllegalArgumentException("Citation id must be positive");
            }
        }
    }
}
