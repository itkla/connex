package ooo.klae.connex.backend.ai.lease;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Unit coverage of what a rolled-back claim leaves behind in the JVM-local token registry. */
class AiRunLeaseRegistryTest {

    private static final AiRunLeaseKey KEY =
            new AiRunLeaseKey(11, AiRunLeaseSubject.AGENT_RUN, 77L);

    private final AiRunLeaseRegistry registry = new AiRunLeaseRegistry();

    @Test
    void aRolledBackReclaimPutsBackTheTokenItDisplaced() {
        AiRunLease held = new AiRunLease(KEY, "owner-a", 4L);
        registry.register(held);

        Runnable undo = registry.register(new AiRunLease(KEY, "owner-a", 5L));
        undo.run();

        assertSame(held, registry.find(KEY).orElseThrow());
    }

    @Test
    void aRolledBackFirstClaimLeavesNoToken() {
        Runnable undo = registry.register(new AiRunLease(KEY, "owner-a", 1L));

        undo.run();

        assertTrue(registry.find(KEY).isEmpty());
    }

    @Test
    void aLateUndoNeverRemovesAnEqualTokenACommittedClaimRegistered() {
        AiRunLease rolledBack = new AiRunLease(KEY, "owner-a", 5L);
        Runnable undo = registry.register(rolledBack);
        AiRunLease committed = new AiRunLease(KEY, "owner-a", 5L);
        assertEquals(rolledBack, committed);
        assertNotSame(rolledBack, committed);
        registry.register(committed);

        undo.run();

        assertSame(committed, registry.find(KEY).orElseThrow());
    }
}
