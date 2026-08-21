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
    void migratedConnectionRequiresRetainedDataResetBeforeReauthorization() {
        UserMapper userMapper = mock(UserMapper.class);
        ProviderConnectionMapper connectionMapper =
            mock(ProviderConnectionMapper.class);
        UserProviderSecretCipher secretCipher =
            mock(UserProviderSecretCipher.class);
        ProviderConnection legacy = new ProviderConnection();
        legacy.setId(1);
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
                new ProviderConnectionExpectation(true, 1, 1),
                new ProviderTokenResponse(
                    "access", "refresh", 3600L, "scope", null),
                "new-account-id",
                "new@example.test",
                "scope"));

        verify(secretCipher, never()).encryptTokenBundle(
            eq("google"), eq(9), anyString());
    }

    @Test
    void staleAuthorizationGenerationIsRejectedBeforeSecretEncryption() {
        UserMapper userMapper = mock(UserMapper.class);
        ProviderConnectionMapper connectionMapper =
            mock(ProviderConnectionMapper.class);
        UserProviderSecretCipher secretCipher =
            mock(UserProviderSecretCipher.class);
        ProviderConnection current = connection(31, 5, "account-31", "connected");
        when(userMapper.lockByIdForShare(9)).thenReturn(9);
        when(connectionMapper.getByUserAndProviderForUpdate(9, "google"))
            .thenReturn(current);
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
                new ProviderConnectionExpectation(true, 31, 4),
                tokens(),
                "account-31",
                "owner@example.test",
                "scope"));

        verify(secretCipher, never()).encryptTokenBundle(
            eq("google"), eq(9), anyString());
    }

    @Test
    void retainedIdentityRejectsDifferentProviderAccountBeforeSecretEncryption() {
        UserMapper userMapper = mock(UserMapper.class);
        ProviderConnectionMapper connectionMapper =
            mock(ProviderConnectionMapper.class);
        UserProviderSecretCipher secretCipher =
            mock(UserProviderSecretCipher.class);
        ProviderConnection tombstone =
            connection(31, 5, "account-old", "disconnected");
        when(userMapper.lockByIdForShare(9)).thenReturn(9);
        when(connectionMapper.getByUserAndProviderForUpdate(9, "google"))
            .thenReturn(tombstone);
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
                new ProviderConnectionExpectation(true, 31, 5),
                tokens(),
                "account-new",
                "new@example.test",
                "scope"));

        verify(secretCipher, never()).encryptTokenBundle(
            eq("google"), eq(9), anyString());
    }

    private static ProviderConnection connection(
            int id, long generation, String accountId, String status) {
        ProviderConnection connection = new ProviderConnection();
        connection.setId(id);
        connection.setUserId(9);
        connection.setProvider("google");
        connection.setStatus(status);
        connection.setProviderAccountId(accountId);
        connection.setCredentialGeneration(generation);
        return connection;
    }

    private static ProviderTokenResponse tokens() {
        return new ProviderTokenResponse(
            "access", "refresh", 3600L, "scope", null);
    }
}
