package ooo.klae.connex.backend.dto;

import java.util.List;

/**
 * Capture overview for every operator-authorized provider.
 */
public record ProviderCaptureOverviewResponse(List<ProviderCaptureOverviewDto> providers) {
    /** Defensively copies provider views. */
    public ProviderCaptureOverviewResponse {
        providers = List.copyOf(providers);
    }
}
