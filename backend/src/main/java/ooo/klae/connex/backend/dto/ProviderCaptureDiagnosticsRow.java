package ooo.klae.connex.backend.dto;

import java.time.LocalDateTime;

/**
 * Metadata-only grouped connected-capture state used by tenant diagnostics.
 */
public record ProviderCaptureDiagnosticsRow(
        int workspaceId,
        String provider,
        String stream,
        String status,
        String errorCode,
        long stateCount,
        long stableCursorCount,
        long pageCursorCount,
        long processedItems,
        long estimatedItems,
        LocalDateTime lastAttemptAt,
        LocalDateTime lastSuccessAt,
        LocalDateTime nextAttemptAt) {
}
