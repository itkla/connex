package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import ooo.klae.connex.backend.mappers.SpringSessionMapper;
import ooo.klae.connex.backend.notifications.WebSocketSessionRegistry;
import ooo.klae.connex.backend.session.AccountSessionIndex;

class AccountSessionRevocationServiceTest {

    private static final int USER_ID = 17;

    private final SessionRegistry sessionRegistry = mock(SessionRegistry.class);
    private final SpringSessionMapper springSessionMapper = mock(SpringSessionMapper.class);
    private final WebSocketSessionRegistry webSocketSessions = mock(WebSocketSessionRegistry.class);
    private final AccountSessionRevocationService service = new AccountSessionRevocationService(
            sessionRegistry, springSessionMapper, webSocketSessions);

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void expireAllClosesEveryIndexedHttpSessionsSockets() {
        SessionInformation first = session("first-session");
        SessionInformation second = session("second-session");
        when(sessionRegistry.getAllSessions(new AccountSessionIndex(USER_ID), false))
                .thenReturn(List.of(first, second));

        service.expireAll(USER_ID);

        assertTrue(first.isExpired());
        assertTrue(second.isExpired());
        verify(webSocketSessions).closeByHttpSession(first.getSessionId());
        verify(webSocketSessions).closeByHttpSession(second.getSessionId());
        verifyNoMoreInteractions(webSocketSessions);
    }

    @Test
    void revocationClosesSocketsAfterTheAccountTransactionCommits() {
        SessionInformation revoked = session("revoked-session");
        when(sessionRegistry.getAllSessions(new AccountSessionIndex(USER_ID), false))
                .thenReturn(List.of(revoked));
        beginTransaction();

        service.expireAll(USER_ID);

        assertTrue(revoked.isExpired());
        verifyNoInteractions(webSocketSessions);
        onlySynchronization().afterCommit();
        verify(webSocketSessions).closeByHttpSession(revoked.getSessionId());
    }

    @Test
    void retainedHttpSessionKeepsItsSocketWhileOtherSessionsClose() {
        SessionInformation retained = session("retained-session");
        SessionInformation revoked = session("revoked-session");
        when(sessionRegistry.getAllSessions(new AccountSessionIndex(USER_ID), false))
                .thenReturn(List.of(retained, revoked));

        service.expireAllExcept(USER_ID, retained.getSessionId());

        assertFalse(retained.isExpired());
        assertTrue(revoked.isExpired());
        verify(webSocketSessions).closeByHttpSession(revoked.getSessionId());
        verifyNoMoreInteractions(webSocketSessions);
    }

    @Test
    void retainedStoreRowSurvivesRotationWhileOtherRowsSocketsClose() {
        SessionInformation retained = session("rotated-retained-session");
        SessionInformation revoked = session("revoked-session");
        SessionInformation rotating = session("mid-rotation-session");
        when(sessionRegistry.getAllSessions(new AccountSessionIndex(USER_ID), false))
                .thenReturn(List.of(retained, revoked, rotating));
        when(springSessionMapper.primaryIdBySessionId(retained.getSessionId())).thenReturn("retained-row");
        when(springSessionMapper.primaryIdBySessionId(revoked.getSessionId())).thenReturn("revoked-row");

        service.expireAllExceptSessionRow(USER_ID, "retained-row");

        assertFalse(retained.isExpired());
        assertTrue(revoked.isExpired());
        assertFalse(rotating.isExpired());
        verify(webSocketSessions).closeByHttpSession(revoked.getSessionId());
        verifyNoMoreInteractions(webSocketSessions);
    }

    @Test
    void renameClosesHistoricalSocketsOnlyAfterCommit() {
        beginTransaction();

        service.closeWebSocketsAfterRename(USER_ID);

        verifyNoInteractions(webSocketSessions);
        onlySynchronization().afterCommit();
        verify(webSocketSessions).closeByUser(USER_ID);
        verifyNoInteractions(sessionRegistry, springSessionMapper);
    }

    @Test
    void rolledBackRenameLeavesSocketsOpen() {
        beginTransaction();

        service.closeWebSocketsAfterRename(USER_ID);
        onlySynchronization().afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

        verifyNoInteractions(webSocketSessions, sessionRegistry, springSessionMapper);
    }

    @Test
    void renameOutsideTransactionClosesSocketsImmediately() {
        service.closeWebSocketsAfterRename(USER_ID);

        verify(webSocketSessions).closeByUser(USER_ID);
        verifyNoInteractions(sessionRegistry, springSessionMapper);
    }

    private static SessionInformation session(String id) {
        return new SessionInformation(new AccountSessionIndex(USER_ID), id,
                Date.from(Instant.parse("2026-09-13T12:00:00Z")));
    }

    private static void beginTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
    }

    private static TransactionSynchronization onlySynchronization() {
        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        assertEquals(1, synchronizations.size());
        return synchronizations.getFirst();
    }
}
