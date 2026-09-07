package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WorkflowTriggeredSendGateTest {

    @Test
    void validatesRecipientAndWorkerBoundsAtStartup() {
        WorkflowTriggeredSendGate gate = new WorkflowTriggeredSendGate(true, 200, 100);

        assertEquals(200, gate.recipientLimit());
        assertEquals(100, gate.dispatchPageSize());
        assertThrows(
            IllegalArgumentException.class,
            () -> new WorkflowTriggeredSendGate(true, 0, 100));
        assertThrows(
            IllegalArgumentException.class,
            () -> new WorkflowTriggeredSendGate(true, 200, 0));
    }

    @Test
    void exposesTheFenceValueCapturedAtConstruction() {
        assertTrue(new WorkflowTriggeredSendGate(true).enabled());
        assertFalse(new WorkflowTriggeredSendGate(false).enabled());
    }
}
