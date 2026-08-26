package ooo.klae.connex.backend.connectedaccounts.nativeflow;

import java.time.Instant;

/** Provider URL, destination account label, and single-use credential returned to the helper. */
public record NativePrepareResponse(
    String authorizeUrl,
    String handoffTicket,
    String accountLabel,
    Instant expiresAt
) {
}
