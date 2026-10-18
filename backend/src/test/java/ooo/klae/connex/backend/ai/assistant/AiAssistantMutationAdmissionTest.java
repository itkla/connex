package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import ooo.klae.connex.backend.beans.AiChatToolCall;
import ooo.klae.connex.backend.exceptions.ConflictException;

class AiAssistantMutationAdmissionTest {
    private static final Instant DEADLINE = Instant.parse("2026-10-04T10:11:12.123456Z");
    private Clock clock;

    @BeforeEach
    void startTransaction() {
        AiAssistantAdmissionTestSupport.beginTransaction();
        clock = org.mockito.Mockito.mock(Clock.class);
        org.mockito.Mockito.when(clock.instant()).thenReturn(DEADLINE.minusNanos(1_000));
    }

    @AfterEach
    void finishTransaction() {
        AiAssistantAdmissionTestSupport.endTransaction();
    }

    @Test
    void successfulResultRequiresAnActualDelegateAdmission() {
        var admission = admission(clock);
        assertThrows(IllegalStateException.class, admission::requireAdmitted);
        admission.beforeMutation();
        assertDoesNotThrow(admission::requireAdmitted);
    }

    @Test
    void finalCheckpointReadsClockAgainAfterTheWait() {
        var admission = admission(clock);
        org.mockito.Mockito.when(clock.instant()).thenReturn(DEADLINE);
        assertThrows(AiAssistantProposalExpiredException.class, admission::beforeMutation);
        assertThrows(IllegalStateException.class, admission::requireAdmitted);
    }

    @Test
    void admittedActionMayCommitAfterDeadline() {
        var admission = admission(clock);
        admission.beforeMutation();
        org.mockito.Mockito.when(clock.instant()).thenReturn(DEADLINE.plusSeconds(1));
        assertDoesNotThrow(admission::beforeMutation);
        assertDoesNotThrow(admission::requireAdmitted);
        admission.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
        assertThrows(IllegalStateException.class, admission::beforeMutation);
    }

    @Test
    void callbackCannotEscapeItsOriginatingTransactionOrThread() throws InterruptedException {
        var admission = admission(clock);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                admission.beforeMutation();
            } catch (RuntimeException exception) {
                failure.set(exception);
            }
        });
        thread.start();
        thread.join();
        org.junit.jupiter.api.Assertions.assertInstanceOf(IllegalStateException.class, failure.get());
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.initSynchronization();
        assertThrows(IllegalStateException.class, admission::beforeMutation);
    }

    @Test
    void absentOrInvalidCreationRefusesWithoutAdmission() {
        for (String createdAt : new String[] {null, "invalid"}) {
            AiChatToolCall call = call();
            call.setCreatedAt(createdAt);
            var admission = new AiAssistantWriteToolService.ProposalAdmission(call, clock);
            assertThrows(ConflictException.class, admission::beforeMutation);
            assertThrows(IllegalStateException.class, admission::requireAdmitted);
        }
    }

    @Test
    void constructingAdmissionOutsideATransactionFailsClosed() {
        AiAssistantAdmissionTestSupport.endTransaction();
        assertThrows(IllegalStateException.class,
                () -> admission(Clock.fixed(DEADLINE.minusSeconds(1), ZoneOffset.UTC)));
    }

    private static AiAssistantWriteToolService.ProposalAdmission admission(Clock clock) {
        return new AiAssistantWriteToolService.ProposalAdmission(call(), clock);
    }

    private static AiChatToolCall call() {
        AiChatToolCall call = new AiChatToolCall();
        call.setId(29);
        call.setCreatedAt("2026-10-01 10:11:12.123456");
        return call;
    }
}
