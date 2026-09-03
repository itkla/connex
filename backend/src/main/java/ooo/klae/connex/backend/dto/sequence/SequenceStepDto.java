package ooo.klae.connex.backend.dto.sequence;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Canonical semantic sequence step used by draft responses and immutable versions.
 *
 * @param position zero-based order
 * @param type closed step behavior
 * @param delayValue relative delay magnitude
 * @param delayUnit relative delay unit
 * @param advancePolicy advancement rule
 * @param contents localized content ordered by locale
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SequenceStepDto(
        int position,
        SequenceStepType type,
        int delayValue,
        String delayUnit,
        String advancePolicy,
        List<ContentDto> contents) {

    /**
     * Canonical localized content.
     *
     * @param locale locale token
     * @param subject optional subject
     * @param bodyText optional plain-text body
     * @param bodyHtml optional HTML body
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ContentDto(
            String locale,
            String subject,
            String bodyText,
            String bodyHtml) {
    }
}
