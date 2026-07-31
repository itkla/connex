package ooo.klae.connex.backend.connectedaccounts.capture;

import java.time.Instant;

/**
 * Bounded stream read request supplied to a provider adapter.
 */
public record ProviderCaptureRequest(
    String accessToken,
    String stream,
    String stableCursor,
    String pageCursor,
    Instant from,
    Instant to,
    boolean includeBodies,
    ProviderCaptureBodyAccess bodyAccess,
    int pageSize,
    ProviderCaptureLease lease
) {
}
