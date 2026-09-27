package ooo.klae.connex.backend.dto;

import java.time.Instant;

/**
 * Non-sensitive public view of a token-addressed immutable document delivery. The flow identity is
 * the non-authorizing digest of the browser grant the preview was rendered from; decisions must echo
 * it so a stale tab cannot decide a document another tab exchanged later.
 */
public record DocumentAcceptancePreviewDto(
        String flowId,
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
