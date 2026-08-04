package ooo.klae.connex.backend.services;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;

import org.springframework.stereotype.Component;

/**
 * Derives bounded deterministic run keys shared by legacy and canonical claims.
 * Legacy plaintext schedule and throttle keys are dual-read until 2026-09-14T00:00:00Z,
 * six weeks after the canonical runtime rollout. This exceeds the seven-day maximum
 * throttle and weekly schedule windows. The compatibility methods stop returning keys
 * automatically at that instant and can be removed after the first release beyond it.
 */
@Component
public class WorkflowDedupeKey {

    private static final Instant LEGACY_COMPATIBILITY_END =
        Instant.parse("2026-09-14T00:00:00Z");

    private final Clock clock;

    public WorkflowDedupeKey() {
        this(Clock.systemUTC());
    }

    WorkflowDedupeKey(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public String entityChange(
            int workflowId,
            String recordType,
            int recordId,
            String event,
            String triggerKey,
            Instant occurredAt,
            Integer throttleMinutes) {
        String bucket = throttleMinutes == null || throttleMinutes <= 0
            ? "event:" + triggerKey
            : "throttle:" + throttleMinutes + ":" + throttleBucket(occurredAt, throttleMinutes);
        return hash(workflowId + "|entity_change|" + recordType + "|" + recordId
            + "|" + event + "|" + bucket);
    }

    public String schedule(
            int workflowId,
            String recordType,
            int recordId,
            String cadence,
            String bucketKey) {
        return hash(workflowId + "|schedule|" + recordType + "|" + recordId
            + "|" + cadence + "|" + bucketKey);
    }

    public String legacyEntityChange(
            int recordId,
            String event,
            Instant occurredAt,
            Integer throttleMinutes) {
        if (!legacyCompatibilityActive()
                || throttleMinutes == null
                || throttleMinutes <= 0) {
            return null;
        }
        return recordId + ":" + event + ":t" + throttleMinutes + ":"
            + throttleBucket(occurredAt, throttleMinutes);
    }

    public String legacySchedule(int recordId, String bucketKey) {
        return legacyCompatibilityActive() ? recordId + ":" + bucketKey : null;
    }

    private boolean legacyCompatibilityActive() {
        return clock.instant().isBefore(LEGACY_COMPATIBILITY_END);
    }

    private static long throttleBucket(Instant occurredAt, int throttleMinutes) {
        if (occurredAt == null) {
            throw new IllegalArgumentException("Trigger occurrence time is required");
        }
        return occurredAt.getEpochSecond() / (throttleMinutes * 60L);
    }

    private static String hash(String material) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
