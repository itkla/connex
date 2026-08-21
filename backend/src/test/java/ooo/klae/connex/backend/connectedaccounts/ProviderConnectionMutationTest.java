package ooo.klae.connex.backend.connectedaccounts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import ooo.klae.connex.backend.beans.ProviderConnection;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.mappers.ProviderConnectionMapper;
import ooo.klae.connex.backend.mappers.UserMapper;

class ProviderConnectionMutationTest {

    @Test
    void pauseAdvancesGenerationAndResetsDurableReconciliation() {
        ProviderConnectionMapper mapper =
            mock(ProviderConnectionMapper.class);
        UserMapper userMapper = mock(UserMapper.class);
        ProviderConnection connection = new ProviderConnection();
        connection.setId(31);
        connection.setUserId(9);
        connection.setProvider("google");
        connection.setProviderAccountId("account-31");
        connection.setStatus("connected");
        connection.setCredentialGeneration(4);
        when(mapper.getByUserAndProviderForUpdate(9, "google"))
            .thenReturn(connection);
        when(userMapper.lockByIdForShare(9)).thenReturn(9);
        when(mapper.getByUserAndProvider(9, "google"))
            .thenReturn(connection);
        ProviderConnectionMutation mutation =
            new ProviderConnectionMutation(userMapper, mapper);

        mutation.transition(
            9, "google", "connected", "paused");

        assertEquals(5, connection.getCredentialGeneration());
        assertEquals("paused", connection.getStatus());
        assertEquals(0, connection.getCaptureReconcileAfterWorkspaceId());
        verify(mapper).update(connection);
    }

    @Test
    void ordinaryDisconnectLocksUserBeforeConnectionAndStartsRevocation() {
        ProviderConnectionMapper mapper = mock(ProviderConnectionMapper.class);
        UserMapper userMapper = mock(UserMapper.class);
        ProviderConnection connection = new ProviderConnection();
        connection.setId(31);
        connection.setUserId(9);
        connection.setProvider("google");
        connection.setStatus("connected");
        connection.setCredentialGeneration(4);
        ProviderConnection revoking = new ProviderConnection();
        revoking.setId(31);
        revoking.setUserId(9);
        revoking.setProvider("google");
        revoking.setProviderAccountId("account-31");
        revoking.setStatus("revoking");
        revoking.setCredentialGeneration(5);
        when(userMapper.lockByIdForShare(9)).thenReturn(9);
        when(mapper.getByUserAndProviderForUpdate(9, "google"))
            .thenReturn(connection);
        when(mapper.beginRevocation(9, "google")).thenReturn(1);
        when(mapper.getByUserAndProvider(9, "google")).thenReturn(revoking);
        ProviderConnectionMutation mutation =
            new ProviderConnectionMutation(userMapper, mapper);

        ProviderConnection result = mutation.beginRevocation(9, "google");

        assertEquals("revoking", result.getStatus());
        assertEquals(5, result.getCredentialGeneration());
        InOrder order = inOrder(userMapper, mapper);
        order.verify(userMapper).lockByIdForShare(9);
        order.verify(mapper).getByUserAndProviderForUpdate(9, "google");
        order.verify(mapper).beginRevocation(9, "google");
    }

    @Test
    void rejectedTransitionDoesNotExposeInternalLifecycleStatus() {
        ProviderConnectionMapper mapper = mock(ProviderConnectionMapper.class);
        UserMapper userMapper = mock(UserMapper.class);
        ProviderConnection connection = new ProviderConnection();
        connection.setStatus("revoking");
        when(userMapper.lockByIdForShare(9)).thenReturn(9);
        when(mapper.getByUserAndProviderForUpdate(9, "google"))
            .thenReturn(connection);
        ProviderConnectionMutation mutation =
            new ProviderConnectionMutation(userMapper, mapper);

        BadRequestException exception = assertThrows(
            BadRequestException.class,
            () -> mutation.transition(9, "google", "connected", "paused"));

        assertEquals(
            "Connection cannot transition from its current state",
            exception.getMessage());
        assertFalse(exception.getMessage().contains("revoking"));
    }
}
