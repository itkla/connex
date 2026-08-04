package ooo.klae.connex.backend.services;

import java.time.Instant;

/** Stable trigger envelope persisted by durable workflow intake. */
public interface WorkflowTriggerDispatch {

    int workspaceId();

    /** One committed CRM mutation delivered with a replay-stable event key. */
    record EntityChange(
        int workspaceId,
        String recordType,
        int recordId,
        String event,
        String triggerKey,
        Instant occurredAt
    ) implements WorkflowTriggerDispatch {

        public EntityChange {
            requirePositive(workspaceId, "Workspace id");
            requirePositive(recordId, "Record id");
            requireBounded(recordType, 16, "Record type");
            requireBounded(event, 64, "Trigger event");
            requireBounded(triggerKey, 96, "Trigger key");
            if (occurredAt == null) {
                throw new IllegalArgumentException("Trigger occurrence time is required");
            }
        }
    }

    /** One deterministic cadence bucket for a workspace schedule sweep. */
    record ScheduleTick(
        int workspaceId,
        String cadence,
        String bucketKey
    ) implements WorkflowTriggerDispatch {

        public ScheduleTick {
            requirePositive(workspaceId, "Workspace id");
            requireBounded(cadence, 16, "Schedule cadence");
            requireBounded(bucketKey, 96, "Schedule bucket");
        }
    }

    private static void requirePositive(int value, String label) {
        if (value <= 0) {
            throw new IllegalArgumentException(label + " must be positive");
        }
    }

    private static void requireBounded(String value, int maximum, String label) {
        if (value == null || value.isBlank() || value.length() > maximum) {
            throw new IllegalArgumentException(
                label + " must contain between 1 and " + maximum + " characters");
        }
    }
}
