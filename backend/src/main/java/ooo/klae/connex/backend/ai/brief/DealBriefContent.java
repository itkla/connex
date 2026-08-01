package ooo.klae.connex.backend.ai.brief;

import java.util.List;

/**
 * JSON content shape the model returns for a deal brief. Bound from provider output at the
 * invocation boundary before any feature-level validation.
 * @param sections ordered brief sections
 */
public record DealBriefContent(List<Section> sections) {

    /**
     * A single titled brief section.
     * @param title short section heading
     * @param body plain-text section body
     * @param sourceIds positional prompt sources that informed the section
     */
    public record Section(String title, String body, List<String> sourceIds) {
    }
}
