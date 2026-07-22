package ooo.klae.connex.backend.dto;

import ooo.klae.connex.backend.beans.ProviderConnection;

/**
 * Client-facing view of a user's provider connection. Never carries the secret reference or any
 * token material — only presence ({@code hasCredential}) and display metadata.
 *
 * @param provider             google | microsoft
 * @param status               connected | paused | error | revoked
 * @param providerAccountEmail account identity reported by the provider (display only)
 * @param grantedScopes        space-delimited scopes granted at consent
 * @param hasCredential        whether a token bundle is stored
 * @param lastSyncAt           last successful sync; null until sync workstreams ship
 * @param errorCode            machine-readable reason when status = error
 * @param createdAt            when first connected
 * @param updatedAt            last state change
 */
public record ProviderConnectionDto(
    String provider,
    String status,
    String providerAccountEmail,
    String grantedScopes,
    boolean hasCredential,
    String lastSyncAt,
    String errorCode,
    String createdAt,
    String updatedAt
) {
    public static ProviderConnectionDto from(ProviderConnection c) {
        if (c == null) return null;
        return new ProviderConnectionDto(c.getProvider(), c.getStatus(), c.getProviderAccountEmail(),
            c.getGrantedScopes(), c.getCredentialRef() != null && !c.getCredentialRef().isBlank(),
            c.getLastSyncAt(), c.getErrorCode(), c.getCreatedAt(), c.getUpdatedAt());
    }
}
