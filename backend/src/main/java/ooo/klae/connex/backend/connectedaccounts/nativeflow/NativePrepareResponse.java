package ooo.klae.connex.backend.connectedaccounts.nativeflow;

import java.time.Instant;

/** Provider authorize URL and single-use completion credential returned to the helper. */
public record NativePrepareResponse(
    String authorizeUrl,
    String handoffTicket,
    Instant expiresAt
) {
}
