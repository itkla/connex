package ooo.klae.connex.backend.connectedaccounts;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import ooo.klae.connex.backend.beans.ProviderConnection;
import ooo.klae.connex.backend.mappers.ProviderConnectionMapper;
import ooo.klae.connex.backend.mappers.UserMapper;

class ProviderConnectionLifecyclePersistenceTest {

    @Test
    void revocationLocksUserBeforeConnectionAndClearsCredentialIntoTombstone() {
        UserMapper userMapper = mock(UserMapper.class);
        ProviderConnectionMapper connectionMapper =
            mock(ProviderConnectionMapper.class);
        UserProviderSecretCipher secretCipher =
            mock(UserProviderSecretCipher.class);
        ProviderConnection connection = new ProviderConnection();
        connection.setId(31);
        connection.setUserId(9);
        connection.setProvider("google");
        connection.setStatus("revoking");
        connection.setCredentialGeneration(4);
        connection.setCredentialRef("credential-ref");
        when(userMapper.lockByIdForShare(9)).thenReturn(9);
        when(connectionMapper.getByIdForUpdate(31)).thenReturn(connection);
        when(connectionMapper.completeRevocation(31, 4)).thenReturn(1);
        ProviderConnectionLifecyclePersistence persistence =
            new ProviderConnectionLifecyclePersistence(
                userMapper, connectionMapper, secretCipher);

        assertTrue(persistence.finishRevocation(connection));

        InOrder order = inOrder(userMapper, connectionMapper, secretCipher);
        order.verify(userMapper).lockByIdForShare(9);
        order.verify(connectionMapper).getByIdForUpdate(31);
        order.verify(secretCipher).deleteTokenBundleReference(
            "google", 9, "credential-ref");
        order.verify(connectionMapper).completeRevocation(31, 4);
    }
}
