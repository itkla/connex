package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Assertions shared by the legacy-versus-canonical workflow parity evidence. */
final class WorkflowParityTestSupport {

    private WorkflowParityTestSupport() { }

    static void assertParity(EffectSnapshot legacy, EffectSnapshot canonical) {
        assertEffectsParity(legacy, canonical);
        assertAll(
            () -> assertEquals(
                legacy.runOutcome(), canonical.runOutcome(), "normalized run outcome"),
            () -> assertEquals(
                legacy.actionInvocationCount(),
                canonical.actionInvocationCount(),
                "action invocation count"));
    }

    static void assertEffectsParity(EffectSnapshot legacy, EffectSnapshot canonical) {
        assertAll(
            () -> assertEquals(legacy.tagIds(), canonical.tagIds(), "tag effects"),
            () -> assertEquals(legacy.tasks(), canonical.tasks(), "task effects"),
            () -> assertEquals(legacy.activities(), canonical.activities(), "activity effects"),
            () -> assertEquals(legacy.notes(), canonical.notes(), "note effects"),
            () -> assertEquals(
                legacy.notifications(), canonical.notifications(), "notification effects"),
            () -> assertEquals(
                legacy.dealOwnerId(), canonical.dealOwnerId(), "deal owner effect"),
            () -> assertEquals(
                legacy.dealStageId(), canonical.dealStageId(), "deal stage effect"),
            () -> assertEquals(
                legacy.responseDueSet(),
                canonical.responseDueSet(),
                "first-response due effect"));
    }
}
