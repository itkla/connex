package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request to append an immutable revision to a campaign message.
 * @param locale the content locale (en or ja)
 * @param subject the subject line
 * @param bodyHtml the HTML body
 * @param bodyText the optional plain-text body
 */
public record CampaignMessageRevisionRequest(
        @Size(max = 8) String locale,
        @NotBlank @Size(max = 255) String subject,
        @NotBlank String bodyHtml,
        String bodyText) {
}
