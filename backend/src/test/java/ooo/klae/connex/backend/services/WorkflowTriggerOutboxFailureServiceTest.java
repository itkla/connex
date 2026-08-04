package ooo.klae.connex.backend.services;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.WorkflowTriggerOutbox;
import ooo.klae.connex.backend.mappers.WorkflowTriggerOutboxMapper;

@ExtendWith(MockitoExtension.class)
class WorkflowTriggerOutboxFailureServiceTest {

    @Mock private WorkflowTriggerOutboxMapper outboxMapper;
    @Mock private WorkflowRuntimeProperties properties;

    @InjectMocks private WorkflowTriggerOutboxFailureService service;

    @Test
    void actorFailureIsDeadLetteredWithItsFixedDiagnosticCode() {
        WorkflowTriggerOutbox outbox = new WorkflowTriggerOutbox();
        outbox.setTriggerType("schedule");
        outbox.setDeliveryAttemptCount(1);
        when(outboxMapper.getOwnedForUpdate(7, 31L, "lease"))
            .thenReturn(outbox);
        when(outboxMapper.deadLetter(7, 31L, "lease", "actor_unavailable"))
            .thenReturn(1);

        service.record(
            7,
            31L,
            "lease",
            new WorkflowExecutionException(
                "actor_unavailable", "Actor unavailable", true));

        verify(outboxMapper).deadLetter(7, 31L, "lease", "actor_unavailable");
    }
}
