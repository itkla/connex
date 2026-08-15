package ooo.klae.connex.backend.connectedaccounts.nativeflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import ooo.klae.connex.backend.beans.NativeConnectSession;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.connectedaccounts.ProviderCredentialPersistence;
import ooo.klae.connex.backend.mappers.NativeConnectSessionMapper;
import ooo.klae.connex.backend.mappers.UserMapper;

class NativeConnectSessionPersistenceTest {
    private static final int USER_ID = 41;
    private static final Instant NOW = Instant.parse("2026-08-14T12:00:00Z");

    private NativeConnectSessionMapper sessionMapper;
    private UserMapper userMapper;
    private NativeConnectPkceSecretCipher pkceSecretCipher;
    private NativeConnectSessionPersistence persistence;

    @BeforeEach
    void setUp() {
        sessionMapper = mock(NativeConnectSessionMapper.class);
        userMapper = mock(UserMapper.class);
        pkceSecretCipher = mock(NativeConnectPkceSecretCipher.class);
        persistence = new NativeConnectSessionPersistence(
            sessionMapper,
            userMapper,
            pkceSecretCipher,
            mock(ProviderCredentialPersistence.class),
            Clock.fixed(NOW, ZoneOffset.UTC));
        when(userMapper.getUserById(USER_ID)).thenReturn(user());
    }

    @Test
    void pollUsesOnlyPlainReadsForAnUnexpiredActiveSession() {
        NativeConnectSession session = session(
            7, "pending", LocalDateTime.ofInstant(NOW.plusSeconds(60), ZoneOffset.UTC));
        when(sessionMapper.getLatestByUserAndProvider(USER_ID, "google"))
            .thenReturn(session);

        NativeConnectPoll poll = persistence.poll(USER_ID, "google");

        assertSame(session, poll.session());
        assertFalse(poll.expiredTransition());
        verify(userMapper, never()).lockById(anyInt());
        verify(sessionMapper, never())
            .getLatestByUserAndProviderForUpdate(USER_ID, "google");
    }

    @Test
    void pollEscalatesToLocksOnlyToTransitionAnExpiredActiveSession() {
        NativeConnectSession session = session(
            8, "prepared", LocalDateTime.ofInstant(NOW.minusSeconds(1), ZoneOffset.UTC));
        session.setVerifierRef("secret:8");
        when(sessionMapper.getLatestByUserAndProvider(USER_ID, "google"))
            .thenReturn(session);
        when(userMapper.lockById(USER_ID)).thenReturn(USER_ID);
        when(sessionMapper.getLatestByUserAndProviderForUpdate(USER_ID, "google"))
            .thenReturn(session);
        when(sessionMapper.fail(8, "prepared", "expired")).thenReturn(1);

        NativeConnectPoll poll = persistence.poll(USER_ID, "google");

        assertSame(session, poll.session());
        assertTrue(poll.expiredTransition());
        assertEquals("failed", session.getStatus());
        verify(pkceSecretCipher).delete("google", USER_ID, "secret:8");
    }

    @Test
    void expiredCleanupLocksInRootOrderAndDeletesTheVerifierBeforeTheRow() {
        LocalDateTime cutoff = LocalDateTime.ofInstant(NOW.minusSeconds(60), ZoneOffset.UTC);
        NativeConnectSession session = session(9, "prepared", cutoff.minusSeconds(1));
        session.setVerifierRef("secret:9");
        when(userMapper.lockByIdForShare(USER_ID)).thenReturn(USER_ID);
        when(sessionMapper.getByIdForUpdate(9)).thenReturn(session);
        when(sessionMapper.deleteExpired(9, cutoff)).thenReturn(1);

        assertTrue(persistence.deleteExpired(9, USER_ID, cutoff));

        InOrder order = inOrder(userMapper, sessionMapper, pkceSecretCipher);
        order.verify(userMapper).lockByIdForShare(USER_ID);
        order.verify(sessionMapper).getByIdForUpdate(9);
        order.verify(pkceSecretCipher).delete("google", USER_ID, "secret:9");
        order.verify(sessionMapper).deleteExpired(9, cutoff);
    }

    private static NativeConnectSession session(
            int id, String status, LocalDateTime expiresAt) {
        NativeConnectSession session = new NativeConnectSession();
        session.setId(id);
        session.setUserId(USER_ID);
        session.setProvider("google");
        session.setStatus(status);
        session.setExpiresAt(expiresAt);
        return session;
    }

    private static User user() {
        User user = new User();
        user.setId(USER_ID);
        return user;
    }
}
