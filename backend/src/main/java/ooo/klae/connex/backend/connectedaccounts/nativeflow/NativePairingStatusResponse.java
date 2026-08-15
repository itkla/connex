package ooo.klae.connex.backend.connectedaccounts.nativeflow;

import java.time.Instant;

/** Current user's self-scoped native authorization status for one provider. */
public record NativePairingStatusResponse(
    String status,
    String errorCode,
    Instant expiresAt
) {
}
