package ooo.klae.connex.backend.dto;

import ooo.klae.connex.backend.beans.ProviderConnection;

/**
 * Client-facing view of a user's provider connection. Never carries the secret reference or any
 * token material — only presence ({@code hasCredential}) and display metadata.
 *
 * @param provider             google | microsoft
 * @param status               connected, paused, error, revoked, disconnecting, or purge_failed
 * @param providerAccountEmail account identity reported by the provider (display only)
 * @param grantedScopes        space-delimited scopes granted at consent
 * @param hasCredential        whether a token bundle is stored
 * @param errorCode            machine-readable connection or purge failure reason
 * @param createdAt            when first connected
 * @param updatedAt            last state change
 */
public record ProviderConnectionDto(
    String provider,
    String status,
    String providerAccountEmail,
    String grantedScopes,
    boolean hasCredential,
    String errorCode,
    String createdAt,
    String updatedAt
) {
    public static ProviderConnectionDto from(ProviderConnection c) {
        if (c == null) return null;
        String status = switch (c.getStatus()) {
            case "revoking" -> "disconnecting";
            case "disconnected" -> "revoked";
            default -> c.getStatus();
        };
        return new ProviderConnectionDto(c.getProvider(), status, c.getProviderAccountEmail(),
            c.getGrantedScopes(), c.getCredentialRef() != null && !c.getCredentialRef().isBlank(),
            c.getErrorCode(), c.getCreatedAt(), c.getUpdatedAt());
    }
}
