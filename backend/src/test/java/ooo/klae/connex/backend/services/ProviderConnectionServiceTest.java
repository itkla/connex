package ooo.klae.connex.backend.services;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import ooo.klae.connex.backend.beans.ProviderConnection;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.connectedaccounts.ConnectedAccountProperties;
import ooo.klae.connex.backend.connectedaccounts.ProviderConnectionService;
import ooo.klae.connex.backend.connectedaccounts.ProviderTokenClient;
import ooo.klae.connex.backend.connectedaccounts.ProviderTokenResponse;
import ooo.klae.connex.backend.connectedaccounts.UserProviderSecretCipher;
import ooo.klae.connex.backend.dto.ProviderConnectionDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.ProviderConnectionMapper;

class ProviderConnectionServiceTest extends AbstractServiceTest {

    @Autowired ProviderConnectionService connectionService;
    @Autowired ConnectedAccountProperties properties;
    @Autowired UserProviderSecretCipher secretCipher;
    @Autowired ProviderConnectionMapper providerConnectionMapper;
    @MockitoBean ProviderTokenClient tokenClient;

    @BeforeEach
    void enableGoogle() {
        properties.getGoogle().setEnabled(true);
        properties.getGoogle().setClientId("client-id");
        properties.getGoogle().setClientSecret("client-secret");
    }

    @AfterEach
    void resetProviders() {
        properties.getGoogle().setEnabled(false);
        properties.getGoogle().setClientId(null);
        properties.getGoogle().setClientSecret(null);
        properties.getMicrosoft().setEnabled(false);
        properties.getMicrosoft().setClientId(null);
        properties.getMicrosoft().setClientSecret(null);
    }

    private static String fakeIdToken(String email) {
        String payload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(("{\"email\":\"" + email + "\"}").getBytes(StandardCharsets.UTF_8));
        return "header." + payload + ".signature";
    }

    private String beginAndExtractState() {
        String url = connectionService.beginAuthorization("google");
        assertTrue(url.startsWith("https://accounts.google.com/o/oauth2/v2/auth?"));
        assertTrue(url.contains("access_type=offline"));
        for (String param : url.substring(url.indexOf('?') + 1).split("&")) {
            if (param.startsWith("state=")) {
                return URLDecoder.decode(param.substring("state=".length()), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("authorize URL carries no state: " + url);
    }

    private void stubExchange(String refreshToken, String email) {
        when(tokenClient.exchange(anyString(), any())).thenReturn(new ProviderTokenResponse(
            "access-token", refreshToken, 3600L, "openid email scope-a", fakeIdToken(email)));
    }

    private String storedReference() {
        ProviderConnection connection =
            providerConnectionMapper.getByUserAndProvider(currentUser.getId(), "google");
        assertNotNull(connection);
        return connection.getCredentialRef();
    }

    @Test
    void unconfiguredProviderFailsClosed() {
        assertThrows(BadRequestException.class, () -> connectionService.beginAuthorization("microsoft"));
        assertThrows(ResourceNotFoundException.class, () -> connectionService.beginAuthorization("slack"));
    }

    @Test
    void connectStoresEncryptedBundleAndConnectionRow() {
        stubExchange("refresh-token", "sales@example.com");
        String state = beginAndExtractState();

        String redirect = connectionService.completeCallback("google", "auth-code", state, null);
        assertTrue(redirect.endsWith("/account/connections?connected=google"));

        List<ProviderConnectionDto> connections = connectionService.getForCurrentUser();
        assertEquals(1, connections.size());
        ProviderConnectionDto connection = connections.getFirst();
        assertEquals("google", connection.provider());
        assertEquals("connected", connection.status());
        assertEquals("sales@example.com", connection.providerAccountEmail());
        assertTrue(connection.hasCredential());
        assertTrue(secretCipher.decryptTokenBundle("google", currentUser.getId(), storedReference())
            .contains("refresh-token"));
    }

    @Test
    void callbackRejectsMissingWrongAndReplayedState() {
        stubExchange("refresh-token", "sales@example.com");

        assertTrue(connectionService.completeCallback("google", "code", null, null).contains("error=state"));
        assertTrue(connectionService.completeCallback("google", "code", "forged", null).contains("error=state"));

        String state = beginAndExtractState();
        assertTrue(connectionService.completeCallback("google", "code", state, null).contains("connected=google"));
        assertTrue(connectionService.completeCallback("google", "code", state, null).contains("error=state"));
    }

    @Test
    void callbackRequiresMatchingProviderState() {
        properties.getMicrosoft().setEnabled(true);
        properties.getMicrosoft().setClientId("ms-id");
        properties.getMicrosoft().setClientSecret("ms-secret");
        String state = beginAndExtractState();

        assertTrue(connectionService.completeCallback("microsoft", "code", state, null).contains("error=state"));
    }

    @Test
    void deniedConsentRedirectsWithoutStoringAnything() {
        String state = beginAndExtractState();
        assertTrue(connectionService.completeCallback("google", null, state, "access_denied")
            .contains("error=denied"));
        assertTrue(connectionService.getForCurrentUser().isEmpty());
    }

    @Test
    void withheldRefreshTokenIsRejected() {
        stubExchange(null, "sales@example.com");
        String state = beginAndExtractState();
        assertTrue(connectionService.completeCallback("google", "code", state, null)
            .contains("error=no_offline_access"));
        assertTrue(connectionService.getForCurrentUser().isEmpty());
    }

    @Test
    void lifecyclePauseResumeDisconnect() {
        stubExchange("refresh-token", "sales@example.com");
        connectionService.completeCallback("google", "code", beginAndExtractState(), null);

        assertEquals("paused", connectionService.pause("google").status());
        assertThrows(BadRequestException.class, () -> connectionService.pause("google"));
        assertEquals("connected", connectionService.resume("google").status());

        connectionService.disconnect("google");
        assertTrue(connectionService.getForCurrentUser().isEmpty());
    }

    @Test
    void reconnectReplacesBundleForSameUserAndProvider() {
        stubExchange("refresh-token-1", "old@example.com");
        connectionService.completeCallback("google", "code", beginAndExtractState(), null);

        stubExchange("refresh-token-2", "new@example.com");
        connectionService.completeCallback("google", "code", beginAndExtractState(), null);

        List<ProviderConnectionDto> connections = connectionService.getForCurrentUser();
        assertEquals(1, connections.size());
        assertEquals("new@example.com", connections.getFirst().providerAccountEmail());

        String bundle = secretCipher.decryptTokenBundle("google", currentUser.getId(), storedReference());
        assertTrue(bundle.contains("refresh-token-2"));
        assertFalse(bundle.contains("refresh-token-1"));
    }

    @Test
    void connectionsAreSelfScoped() {
        stubExchange("refresh-token", "sales@example.com");
        connectionService.completeCallback("google", "code", beginAndExtractState(), null);

        User other = newUser();
        authenticateAs(other, workspace.getId());
        assertTrue(connectionService.getForCurrentUser().isEmpty());
        assertThrows(ResourceNotFoundException.class, () -> connectionService.pause("google"));
        assertThrows(ResourceNotFoundException.class, () -> connectionService.disconnect("google"));

        authenticateAs(currentUser, workspace.getId());
        assertEquals(1, connectionService.getForCurrentUser().size());
    }

    @Test
    void disconnectSurvivesADanglingSecretReference() {
        ProviderConnection dangling = new ProviderConnection();
        dangling.setUserId(currentUser.getId());
        dangling.setProvider("google");
        dangling.setStatus("connected");
        dangling.setCredentialRef("secret:v1:999999999");
        providerConnectionMapper.insert(dangling);

        connectionService.disconnect("google");
        assertTrue(connectionService.getForCurrentUser().isEmpty());
    }

    @Test
    void tokenBundleIsScopedToItsOwner() {
        stubExchange("refresh-token", "sales@example.com");
        connectionService.completeCallback("google", "code", beginAndExtractState(), null);
        String reference = storedReference();

        assertNotNull(secretCipher.decryptTokenBundle("google", currentUser.getId(), reference));
        User other = newUser();
        assertThrows(RuntimeException.class,
            () -> secretCipher.decryptTokenBundle("google", other.getId(), reference));
    }
}
