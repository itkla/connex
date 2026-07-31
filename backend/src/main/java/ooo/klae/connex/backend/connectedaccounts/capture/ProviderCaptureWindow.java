package ooo.klae.connex.backend.connectedaccounts.capture;

import java.time.Instant;
import java.util.List;

/**
 * Converts provider changes outside the bounded capture interval into withdrawal tombstones.
 */
final class ProviderCaptureWindow {
    private ProviderCaptureWindow() {
    }

    /** Retains in-window evidence and withdraws a source that moved beyond the active window. */
    static ProviderCaptureItem enforce(
            ProviderCaptureItem item, Instant from, Instant to) {
        if (item.tombstone() || within(item, from, to)) {
            return item;
        }
        return new ProviderCaptureItem(
            item.sourceId(),
            item.sourceVersion(),
            item.conversationId(),
            item.interactionType(),
            null,
            null,
            Instant.EPOCH,
            null,
            false,
            true,
            List.of());
    }

    private static boolean within(
            ProviderCaptureItem item, Instant from, Instant to) {
        if ("meeting".equals(item.interactionType())
                && item.endedAt() != null) {
            return item.occurredAt().isBefore(to)
                && item.endedAt().isAfter(from);
        }
        return !item.occurredAt().isBefore(from)
            && item.occurredAt().isBefore(to);
    }
}
