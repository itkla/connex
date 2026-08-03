package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;

import org.junit.jupiter.api.Test;

/** Verifies replay-stable, workflow-specific canonical and legacy dedupe material. */
class WorkflowDedupeKeyTest {

    private final WorkflowDedupeKey keys = new WorkflowDedupeKey();

    @Test
    void entityRedeliveryCollidesOnlyWithinTheSameWorkflowAndEvent() {
        Instant occurredAt = Instant.parse("2026-08-02T12:34:56Z");
        String first = keys.entityChange(
            11, "deal", 41, "deal.won", "event-7", occurredAt, null);
        String replay = keys.entityChange(
            11, "deal", 41, "deal.won", "event-7", occurredAt, null);

        assertEquals(first, replay);
        assertEquals(64, first.length());
        assertNotEquals(first, keys.entityChange(
            12, "deal", 41, "deal.won", "event-7", occurredAt, null));
        assertNotEquals(first, keys.entityChange(
            11, "deal", 41, "deal.won", "event-8", occurredAt, null));
    }

    @Test
    void throttleAndScheduleUseDeterministicBuckets() {
        Instant firstMinute = Instant.parse("2026-08-02T12:10:00Z");
        Instant sameHour = Instant.parse("2026-08-02T12:59:59Z");
        Instant nextHour = Instant.parse("2026-08-02T13:00:00Z");

        String throttled = keys.entityChange(
            11, "company", 8, "company.updated", "event-a", firstMinute, 60);
        assertEquals(throttled, keys.entityChange(
            11, "company", 8, "company.updated", "event-b", sameHour, 60));
        assertNotEquals(throttled, keys.entityChange(
            11, "company", 8, "company.updated", "event-c", nextHour, 60));

        String scheduled = keys.schedule(11, "company", 8, "daily", "20260802");
        assertEquals(scheduled, keys.schedule(
            11, "company", 8, "daily", "20260802"));
        assertNotEquals(scheduled, keys.schedule(
            11, "company", 8, "daily", "20260803"));
    }

    @Test
    void triggerEnvelopeRejectsUnboundedOrMissingIdentity() {
        assertThrows(IllegalArgumentException.class, () ->
            new WorkflowTriggerDispatch.EntityChange(
                7, "deal", 9, "deal.won", "", Instant.now()));
        assertThrows(IllegalArgumentException.class, () ->
            new WorkflowTriggerDispatch.ScheduleTick(7, "daily", "x".repeat(97)));
    }
}
