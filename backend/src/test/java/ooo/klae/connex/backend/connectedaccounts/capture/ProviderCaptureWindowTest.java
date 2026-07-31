package ooo.klae.connex.backend.connectedaccounts.capture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

class ProviderCaptureWindowTest {
    private static final Instant FROM =
        Instant.parse("2026-07-01T00:00:00Z");
    private static final Instant TO =
        Instant.parse("2026-08-01T00:00:00Z");

    @Test
    void eventMovedPastTheUpperBoundBecomesAWithdrawal() {
        ProviderCaptureItem event = item(
            "meeting",
            Instant.parse("2026-08-02T09:00:00Z"),
            Instant.parse("2026-08-02T10:00:00Z"));

        ProviderCaptureItem bounded =
            ProviderCaptureWindow.enforce(event, FROM, TO);

        assertTrue(bounded.tombstone());
    }

    @Test
    void meetingOverlappingTheLowerBoundRemainsEligible() {
        ProviderCaptureItem event = item(
            "meeting",
            Instant.parse("2026-06-30T23:30:00Z"),
            Instant.parse("2026-07-01T00:30:00Z"));

        ProviderCaptureItem bounded =
            ProviderCaptureWindow.enforce(event, FROM, TO);

        assertFalse(bounded.tombstone());
    }

    private static ProviderCaptureItem item(
            String type, Instant occurredAt, Instant endedAt) {
        return new ProviderCaptureItem(
            "source-1",
            "version-1",
            "conversation-1",
            type,
            "Subject",
            null,
            occurredAt,
            endedAt,
            false,
            false,
            List.of());
    }
}
