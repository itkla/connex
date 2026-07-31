package ooo.klae.connex.backend.connectedaccounts;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.beans.ProviderConnection;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.mappers.ProviderConnectionMapper;
import ooo.klae.connex.backend.mappers.UserMapper;

class ProviderCredentialPersistenceTest {

    @Test
    void migratedConnectionMustDisconnectBeforeAccountIdentityCanChange() {
        UserMapper userMapper = mock(UserMapper.class);
        ProviderConnectionMapper connectionMapper =
            mock(ProviderConnectionMapper.class);
        UserProviderSecretCipher secretCipher =
            mock(UserProviderSecretCipher.class);
        ProviderConnection legacy = new ProviderConnection();
        legacy.setUserId(9);
        legacy.setProvider("google");
        legacy.setStatus("revoked");
        legacy.setCredentialRef("legacy-ref");
        legacy.setCredentialGeneration(1);
        when(userMapper.lockByIdForShare(9)).thenReturn(9);
        when(connectionMapper.getByUserAndProviderForUpdate(9, "google"))
            .thenReturn(legacy);
        ProviderCredentialPersistence persistence =
            new ProviderCredentialPersistence(
                userMapper,
                connectionMapper,
                secretCipher,
                new ObjectMapper());

        assertThrows(
            ConflictException.class,
            () -> persistence.storeConnection(
                9,
                "google",
                new ProviderTokenResponse(
                    "access", "refresh", 3600L, "scope", null),
                "new-account-id",
                "new@example.test",
                "scope"));

        verify(secretCipher, never()).encryptTokenBundle(
            eq("google"), eq(9), anyString());
    }
}
