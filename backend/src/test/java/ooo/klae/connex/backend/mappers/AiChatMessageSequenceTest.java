package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import ooo.klae.connex.backend.exceptions.ConflictException;

class AiChatMessageSequenceTest {
    private final AiChatMapper mapper = mock(AiChatMapper.class, CALLS_REAL_METHODS);

    @AfterEach
    void clearTransactionContext() {
        TransactionSynchronizationManager.clear();
    }

    @Test
    void allocationRefusesOutsideCallerContentTransaction() {
        assertThrows(IllegalStateException.class, () -> mapper.nextMessageSequence(7, 13));
        verify(mapper, never()).lockMessageSequence(7, 13);
    }

    @Test
    void eachAllocationRereadsAndAdvancesLockedHighWater() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        when(mapper.lockMessageSequence(7, 13)).thenReturn(41, 42);
        when(mapper.advanceMessageSequence(7, 13, 41)).thenReturn(1);
        when(mapper.advanceMessageSequence(7, 13, 42)).thenReturn(1);

        assertEquals(42, mapper.nextMessageSequence(7, 13));
        assertEquals(43, mapper.nextMessageSequence(7, 13));

        InOrder order = inOrder(mapper);
        order.verify(mapper).lockMessageSequence(7, 13);
        order.verify(mapper).advanceMessageSequence(7, 13, 41);
        order.verify(mapper).lockMessageSequence(7, 13);
        order.verify(mapper).advanceMessageSequence(7, 13, 42);
    }

    @Test
    void missingSessionAndOverflowDoNotAdvanceCounter() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        when(mapper.lockMessageSequence(7, 13)).thenReturn(null, -1, Integer.MAX_VALUE);

        assertThrows(ConflictException.class, () -> mapper.nextMessageSequence(7, 13));
        assertThrows(ConflictException.class, () -> mapper.nextMessageSequence(7, 13));
        assertThrows(ConflictException.class, () -> mapper.nextMessageSequence(7, 13));
        verify(mapper, never()).advanceMessageSequence(7, 13, Integer.MAX_VALUE);
        verify(mapper, never()).advanceMessageSequence(7, 13, -1);
    }

    @Test
    void compareAndSetFailureCannotReturnReusableSequence() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        when(mapper.lockMessageSequence(7, 13)).thenReturn(41);
        when(mapper.advanceMessageSequence(7, 13, 41)).thenReturn(0);

        assertThrows(IllegalStateException.class, () -> mapper.nextMessageSequence(7, 13));
    }
}
