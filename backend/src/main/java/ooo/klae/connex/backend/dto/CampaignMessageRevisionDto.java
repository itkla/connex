package ooo.klae.connex.backend.dto;

import java.time.LocalDateTime;

/**
 * API representation of an immutable campaign message revision.
 * @param version the revision version
 * @param locale the content locale
 * @param subject the subject line
 * @param bodyHtml the HTML body
 * @param bodyText the optional plain-text body
 * @param createdAt the creation timestamp
 */
public record CampaignMessageRevisionDto(
        int version,
        String locale,
        String subject,
        String bodyHtml,
        String bodyText,
        LocalDateTime createdAt) {
}
