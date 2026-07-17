package ooo.klae.connex.backend.ai.report;

import java.util.List;

/**
 * Structured model output for a grounded report narrative. The model only SELECTS deterministic
 * facts by id and kind and chooses a section title; it never authors prose. A resolver fills the
 * canonical claim text, so grounding cannot fail on wording, whitespace, casing, or masked tokens.
 * @param sections ordered sections, each a chosen title plus ordered fact selections
 * @param findings ordered fact selections for the findings column
 */
public record AiReportNarrativeSelection(List<Section> sections, List<Item> findings) {

    /**
     * A chosen section: an allowed title and the deterministic facts it presents.
     * @param title chosen title (matched to the allowed set, case- and space-insensitive)
     * @param items ordered fact selections
     */
    public record Section(String title, List<Item> items) {
    }

    /**
     * A selected deterministic fact: which source and which of its two supported claims.
     * @param sourceId cited source registry id
     * @param kind {@code fact} or {@code recommendation}
     */
    public record Item(String sourceId, String kind) {
    }
}
