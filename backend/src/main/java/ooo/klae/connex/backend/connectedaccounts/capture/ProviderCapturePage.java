package ooo.klae.connex.backend.connectedaccounts.capture;

import java.util.List;

/**
 * One bounded provider page and its resumable cursors.
 */
public record ProviderCapturePage(
    List<ProviderCaptureItem> items,
    String nextPageCursor,
    String stableCursor,
    Long estimatedItems
) {
    /** Defensively copies page items. */
    public ProviderCapturePage {
        items = List.copyOf(items);
    }
}
