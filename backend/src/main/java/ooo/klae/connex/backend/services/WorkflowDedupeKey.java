package ooo.klae.connex.backend.services;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

import org.springframework.stereotype.Component;

/** Derives bounded deterministic run keys shared by legacy and canonical claims. */
@Component
public class WorkflowDedupeKey {

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
