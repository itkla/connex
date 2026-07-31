package ooo.klae.connex.backend.dto;

import java.util.List;

/**
 * Bounded held-participant review page.
 */
public record ProviderCaptureReviewPage(
    List<ProviderCaptureReviewDto> items,
    long total,
    int page,
    int size
) {
    /** Defensively copies page items. */
    public ProviderCaptureReviewPage {
        items = List.copyOf(items);
    }
}
