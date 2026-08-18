package ooo.klae.connex.backend.services;

import java.util.List;

/** Normalized, workspace-scoped automation effects for one parity-test subject. */
record EffectSnapshot(
    List<Integer> tagIds,
    List<TaskEffect> tasks,
    List<ActivityEffect> activities,
    List<NoteEffect> notes,
    List<NotificationEffect> notifications,
    Integer dealOwnerId,
    Integer dealStageId,
    Long responseDueDurationSeconds,
    List<LedgerIdentity> ledgerIdentities,
    RunOutcome runOutcome,
    int actionInvocationCount
) {

    EffectSnapshot {
        tagIds = List.copyOf(tagIds);
        tasks = List.copyOf(tasks);
        activities = List.copyOf(activities);
        notes = List.copyOf(notes);
        notifications = List.copyOf(notifications);
        ledgerIdentities = List.copyOf(ledgerIdentities);
    }

    record TaskEffect(
        String description,
        Integer assigneeId,
        long dueDateOffsetDays,
        Integer personId,
        Integer dealId
    ) { }

    record ActivityEffect(
        String type,
        String subject,
        String notes,
        Integer personId,
        Integer dealId
    ) { }

    record NoteEffect(
        String content,
        Integer personId,
        Integer dealId
    ) { }

    record NotificationEffect(
        int recipientId,
        String type,
        String category,
        String severity,
        String title,
        String body,
        String actorLabel,
        String sourceType,
        Integer sourceId
    ) { }

    /** Trigger and dedupe fields validated exactly before phase-specific values are normalized. */
    record LedgerIdentity(
        String triggerType,
        String triggerEvent,
        String triggerKey,
        String dedupeKey
    ) { }

    record RunOutcome(List<String> statuses, int rowCount) {

        RunOutcome {
            statuses = List.copyOf(statuses);
        }
    }
}
