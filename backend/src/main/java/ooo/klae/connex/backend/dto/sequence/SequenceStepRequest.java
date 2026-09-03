package ooo.klae.connex.backend.dto.sequence;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Mutable draft step submitted with a sequence aggregate.
 *
 * @param type closed step behavior
 * @param delayValue relative delay magnitude
 * @param delayUnit relative delay unit
 * @param advancePolicy advancement rule
 * @param contents localized content variants
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SequenceStepRequest(
        @NotNull SequenceStepType type,
        @Min(0) @Max(8760) Integer delayValue,
        @Pattern(regexp = "hours|business_days") String delayUnit,
        @Pattern(regexp = "automatic|manual_completion|manual_completion_or_skip") String advancePolicy,
        @Valid @Size(max = 2) List<Content> contents) {

    /**
     * Localized draft content.
     *
     * @param locale supported locale token
     * @param subject optional email subject
     * @param bodyText optional plain-text body
     * @param bodyHtml optional HTML body
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Content(
            @NotBlank @Pattern(regexp = "en|ja") String locale,
            @Size(max = 255) String subject,
            @Size(max = 65535) String bodyText,
            @Size(max = 262144) String bodyHtml) {
    }
}
