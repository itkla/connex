package ooo.klae.connex.backend.dto;

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
        String lastAttemptAt,
        String lastSuccessAt,
        String nextAttemptAt) {
}
