package ooo.klae.connex.backend.dto;

import java.time.Instant;

/** Non-sensitive public view of a token-addressed immutable document delivery. */
public record DocumentAcceptancePreviewDto(
        DocumentContent content,
        String dealName,
        String workspaceName,
        String recipientEmail,
        String deliveryStatus,
        String recipientStatus,
        boolean actionable,
        String documentType,
        String documentTitle,
        int documentVersion,
        String documentLocale,
        Instant expiresAt) {
}
