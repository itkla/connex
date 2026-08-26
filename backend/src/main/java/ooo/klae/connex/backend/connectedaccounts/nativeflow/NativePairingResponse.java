package ooo.klae.connex.backend.connectedaccounts.nativeflow;

import java.time.Instant;

/** Browser response containing one copy-paste pairing credential and helper invocation. */
public record NativePairingResponse(
    String pairingCode,
    Instant expiresAt,
    String instanceBaseUrl,
    String helperCommand
) {
}
