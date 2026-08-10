package ooo.klae.connex.backend.beans;

import java.time.Instant;

/** Internal client-error row before support-disclosure sanitization. */
public record ClientErrorMetadataRow(
        Long id,
        Integer workspaceId,
        Integer orgId,
        String storedCorrelationValue,
        String pagePath,
        Instant reportedAt) {
}
