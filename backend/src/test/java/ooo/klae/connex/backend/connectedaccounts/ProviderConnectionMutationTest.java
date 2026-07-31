package ooo.klae.connex.backend.connectedaccounts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.beans.ProviderConnection;
import ooo.klae.connex.backend.mappers.ProviderConnectionMapper;

class ProviderConnectionMutationTest {

    @Test
    void pauseAdvancesGenerationAndResetsDurableReconciliation() {
        ProviderConnectionMapper mapper =
            mock(ProviderConnectionMapper.class);
        ProviderConnection connection = new ProviderConnection();
        connection.setId(31);
        connection.setUserId(9);
        connection.setProvider("google");
        connection.setStatus("connected");
        connection.setCredentialGeneration(4);
        when(mapper.getByUserAndProviderForUpdate(9, "google"))
            .thenReturn(connection);
        when(mapper.getByUserAndProvider(9, "google"))
            .thenReturn(connection);
        ProviderConnectionMutation mutation =
            new ProviderConnectionMutation(mapper);

        mutation.transition(
            9, "google", "connected", "paused");

        assertEquals(5, connection.getCredentialGeneration());
        assertEquals("paused", connection.getStatus());
        assertEquals(0, connection.getCaptureReconcileAfterWorkspaceId());
        verify(mapper).update(connection);
    }
}
