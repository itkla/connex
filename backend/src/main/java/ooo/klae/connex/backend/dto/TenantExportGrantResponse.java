package ooo.klae.connex.backend.dto;

import java.time.Instant;

/** Browser download instructions for a short-lived tenant export grant. */
public record TenantExportGrantResponse(
    Instant expiresAt,
    String downloadPath) {
}
