package ooo.klae.connex.backend.dto.sequence;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Non-persisted rendering of a published sequence version.
 *
 * @param version published version number
 * @param steps rendered steps
 * @param unresolvedMergeFields sorted unresolved fields referenced by content
 */
public record SequencePreviewDto(
        int version,
        List<RenderedStep> steps,
        List<String> unresolvedMergeFields) {

    /**
     * Rendered content for one ordered step.
     *
     * @param position zero-based order
     * @param type step behavior
     * @param locale locale actually rendered after per-step fallback
     * @param subject optional rendered subject
     * @param bodyText optional rendered plain-text body
     * @param bodyHtml optional rendered HTML body
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RenderedStep(
            int position,
            SequenceStepType type,
            String locale,
            String subject,
            String bodyText,
            String bodyHtml) {
    }
}
