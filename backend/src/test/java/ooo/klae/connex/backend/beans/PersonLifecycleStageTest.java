package ooo.klae.connex.backend.beans;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

/** The lead-lifecycle transition graph documented in {@code docs/LEAD_LIFECYCLE.md} (#559). */
class PersonLifecycleStageTest {

    @Test
    void aContactEntersTheLifecycleOnlyAtNew() {
        assertTrue(PersonLifecycleStage.isTransitionAllowed(null, PersonLifecycleStage.NEW));
        for (PersonLifecycleStage stage : PersonLifecycleStage.values()) {
            if (stage != PersonLifecycleStage.NEW) {
                assertFalse(PersonLifecycleStage.isTransitionAllowed(null, stage),
                    "A contact must not enter the lifecycle directly at " + stage);
            }
        }
    }

    @Test
    void withdrawingFromTheLifecycleIsAlwaysAllowed() {
        for (PersonLifecycleStage stage : PersonLifecycleStage.values()) {
            assertTrue(PersonLifecycleStage.isTransitionAllowed(stage, null),
                "A contact in " + stage + " must be able to leave the lifecycle");
        }
    }

    @Test
    void aStageIsNeverATransitionToItself() {
        assertFalse(PersonLifecycleStage.isTransitionAllowed(null, null));
        for (PersonLifecycleStage stage : PersonLifecycleStage.values()) {
            assertFalse(PersonLifecycleStage.isTransitionAllowed(stage, stage));
        }
    }

    @Test
    void convertedIsReachableOnlyFromQualified() {
        for (PersonLifecycleStage stage : PersonLifecycleStage.values()) {
            boolean allowed =
                PersonLifecycleStage.isTransitionAllowed(stage, PersonLifecycleStage.CONVERTED);
            assertEquals(stage == PersonLifecycleStage.QUALIFIED, allowed,
                stage + " must not lead straight to CONVERTED");
        }
        assertFalse(PersonLifecycleStage.isTransitionAllowed(null, PersonLifecycleStage.CONVERTED));
    }

    @Test
    void terminalStagesReopenOnlyThroughRecycled() {
        assertEquals(Set.of(PersonLifecycleStage.RECYCLED),
            PersonLifecycleStage.allowedTransitionsFrom(PersonLifecycleStage.DISQUALIFIED));
        assertEquals(Set.of(PersonLifecycleStage.RECYCLED),
            PersonLifecycleStage.allowedTransitionsFrom(PersonLifecycleStage.CONVERTED));
        assertTrue(PersonLifecycleStage.isTransitionAllowed(
            PersonLifecycleStage.RECYCLED, PersonLifecycleStage.NEW));
    }

    @Test
    void everyStageOffersAtLeastOneOnwardMove() {
        for (PersonLifecycleStage stage : PersonLifecycleStage.values()) {
            assertFalse(PersonLifecycleStage.allowedTransitionsFrom(stage).isEmpty(),
                stage + " is a dead end");
        }
        assertEquals(Set.of(PersonLifecycleStage.NEW),
            PersonLifecycleStage.allowedTransitionsFrom(null));
    }
}
