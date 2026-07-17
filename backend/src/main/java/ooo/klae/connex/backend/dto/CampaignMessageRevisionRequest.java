package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.Size;

/**
 * Request to append an immutable revision to a campaign message. Which content fields are required is
 * channel-specific — an email revision needs a subject and HTML body, an SMS revision needs the text
 * body — so the presence rules are enforced per channel in {@code CampaignSendService} rather than
 * structurally here.
 * @param locale the content locale (en or ja)
 * @param subject the subject line, required for email
 * @param bodyHtml the HTML body, required for email
 * @param bodyText the plain-text body, required for sms
 */
public record CampaignMessageRevisionRequest(
        @Size(max = 8) String locale,
        @Size(max = 255) String subject,
        String bodyHtml,
        String bodyText) {
}
