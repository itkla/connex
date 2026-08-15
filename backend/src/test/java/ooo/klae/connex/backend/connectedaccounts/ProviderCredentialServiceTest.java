package ooo.klae.connex.backend.connectedaccounts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.beans.ProviderConnection;
import ooo.klae.connex.backend.mappers.ProviderConnectionMapper;

class ProviderCredentialServiceTest {

    @Test
    void refreshPersistsProviderRotationUnderTheClaimedGeneration() {
        ProviderConnectionMapper connectionMapper =
            mock(ProviderConnectionMapper.class);
        ConnectedAccountProperties accountProperties =
            new ConnectedAccountProperties();
        accountProperties.getMicrosoft().setMode(ConnectedAccountMode.MANAGED);
        accountProperties.getMicrosoft().setClientId("custom-client-id");
        accountProperties.getMicrosoft().setClientSecret("custom-client-secret");
        accountProperties.getManaged().getMicrosoft().setClientId("managed-client-id");
        accountProperties.getManaged().getMicrosoft().setClientSecret("managed-client-secret");
        ConnectedAccountProviders providers =
            new ConnectedAccountProviders(accountProperties);
        UserProviderSecretCipher cipher = mock(UserProviderSecretCipher.class);
        ProviderTokenClient tokenClient = mock(ProviderTokenClient.class);
        ProviderCredentialPersistence persistence =
            mock(ProviderCredentialPersistence.class);
        ProviderConnection connection = connection();
        when(cipher.decryptTokenBundle(
                "microsoft", 9, "credential-ref"))
            .thenReturn("""
                {
                  "accessToken":"expired-access",
                  "refreshToken":"old-refresh",
                  "accessTokenExpiresAt":"2026-01-01T00:00:00Z"
                }
                """);
        when(connectionMapper.claimRefreshLease(
                eq(31), eq(4L), anyString(), anyString(), anyString()))
            .thenReturn(1);
        when(connectionMapper.getById(31)).thenReturn(connection);
        ProviderTokenResponse rotated = new ProviderTokenResponse(
            "fresh-access",
            "rotated-refresh",
            3600L,
            "scope",
            null);
        when(tokenClient.exchange(
                eq(providers.tokenUri("microsoft")),
                org.mockito.ArgumentMatchers.<Map<String, String>>any()))
            .thenReturn(rotated);
        when(persistence.completeRefresh(
                eq(31), eq(4L), anyString(), eq(rotated), eq("old-refresh")))
            .thenReturn("fresh-access");
        ProviderCredentialService service = new ProviderCredentialService(
            connectionMapper,
            providers,
            cipher,
            tokenClient,
            persistence,
            new ObjectMapper());

        String accessToken = service.accessToken(connection);

        assertEquals("fresh-access", accessToken);
        verify(tokenClient).exchange(
            providers.tokenUri("microsoft"),
            Map.of(
                "grant_type", "refresh_token",
                "refresh_token", "old-refresh",
                "client_id", "managed-client-id",
                "client_secret", "managed-client-secret",
                "scope", providers.scopes("microsoft")));
        verify(persistence).completeRefresh(
            eq(31), eq(4L), anyString(), eq(rotated), eq("old-refresh"));
    }

    @Test
    void staleCallerReloadsRotatedBundleAfterClaimingTheLease() {
        ProviderConnectionMapper connectionMapper =
            mock(ProviderConnectionMapper.class);
        ConnectedAccountProperties accountProperties =
            new ConnectedAccountProperties();
        accountProperties.getMicrosoft().setClientId("client-id");
        accountProperties.getMicrosoft().setClientSecret("client-secret");
        ConnectedAccountProviders providers =
            new ConnectedAccountProviders(accountProperties);
        UserProviderSecretCipher cipher = mock(UserProviderSecretCipher.class);
        ProviderTokenClient tokenClient = mock(ProviderTokenClient.class);
        ProviderConnection stale = connection();
        ProviderConnection current = connection();
        current.setCredentialRef("rotated-ref");
        when(cipher.decryptTokenBundle(
                "microsoft", 9, "credential-ref"))
            .thenReturn("""
                {
                  "accessToken":"expired-access",
                  "refreshToken":"old-refresh",
                  "accessTokenExpiresAt":"2026-01-01T00:00:00Z"
                }
                """);
        when(connectionMapper.claimRefreshLease(
                eq(31), eq(4L), anyString(), anyString(), anyString()))
            .thenReturn(1);
        when(connectionMapper.getById(31)).thenReturn(current);
        when(cipher.decryptTokenBundle(
                "microsoft", 9, "rotated-ref"))
            .thenReturn("""
                {
                  "accessToken":"fresh-access",
                  "refreshToken":"rotated-refresh",
                  "accessTokenExpiresAt":"%s"
                }
                """.formatted(Instant.now().plusSeconds(600)));
        when(connectionMapper.releaseRefreshLease(
                eq(31), eq(4L), anyString(), eq(null)))
            .thenReturn(1);
        ProviderCredentialService service = new ProviderCredentialService(
            connectionMapper,
            providers,
            cipher,
            tokenClient,
            mock(ProviderCredentialPersistence.class),
            new ObjectMapper());

        assertEquals("fresh-access", service.accessToken(stale));

        verify(tokenClient, org.mockito.Mockito.never()).exchange(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
        verify(connectionMapper).releaseRefreshLease(
            eq(31), eq(4L), anyString(), eq(null));
    }

    @Test
    void unexpiredAccessTokenAvoidsRefreshAndLeaseWork() {
        ProviderConnectionMapper connectionMapper =
            mock(ProviderConnectionMapper.class);
        ConnectedAccountProviders providers =
            new ConnectedAccountProviders(new ConnectedAccountProperties());
        UserProviderSecretCipher cipher = mock(UserProviderSecretCipher.class);
        ProviderConnection connection = connection();
        when(cipher.decryptTokenBundle(
                "microsoft", 9, "credential-ref"))
            .thenReturn("""
                {
                  "accessToken":"current-access",
                  "refreshToken":"refresh",
                  "accessTokenExpiresAt":"%s"
                }
                """.formatted(Instant.now().plusSeconds(600)));
        ProviderCredentialService service = new ProviderCredentialService(
            connectionMapper,
            providers,
            cipher,
            mock(ProviderTokenClient.class),
            mock(ProviderCredentialPersistence.class),
            new ObjectMapper());

        assertEquals("current-access", service.accessToken(connection));
        verify(connectionMapper, org.mockito.Mockito.never()).claimRefreshLease(
            eq(31), eq(4L), anyString(), anyString(), anyString());
    }

    private static ProviderConnection connection() {
        ProviderConnection connection = new ProviderConnection();
        connection.setId(31);
        connection.setUserId(9);
        connection.setProvider("microsoft");
        connection.setStatus("connected");
        connection.setCredentialRef("credential-ref");
        connection.setCredentialGeneration(4);
        return connection;
    }
}
