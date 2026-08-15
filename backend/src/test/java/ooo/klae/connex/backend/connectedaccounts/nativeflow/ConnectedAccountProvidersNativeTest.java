package ooo.klae.connex.backend.connectedaccounts.nativeflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.connectedaccounts.ConnectedAccountMode;
import ooo.klae.connex.backend.connectedaccounts.ConnectedAccountProperties;
import ooo.klae.connex.backend.connectedaccounts.ConnectedAccountProviders;

class ConnectedAccountProvidersNativeTest {

    @Test
    void egressAllowlistIsExactAndCoversEveryNativeProviderUrl() {
        List<String> expectedHosts = List.of(
            "accounts.google.com",
            "oauth2.googleapis.com",
            "www.googleapis.com",
            "gmail.googleapis.com",
            "login.microsoftonline.com",
            "graph.microsoft.com");
        assertEquals(expectedHosts, ConnectedAccountProviders.egressHosts());

        ConnectedAccountProperties properties = managedProperties();
        ConnectedAccountProviders providers = new ConnectedAccountProviders(properties);
        List<String> emittedUrls = new ArrayList<>();
        for (String provider : List.of(
                ConnectedAccountProviders.GOOGLE,
                ConnectedAccountProviders.MICROSOFT)) {
            emittedUrls.add(providers.nativeAuthorizeUrl(
                provider,
                "http://127.0.0.1:49152/callback",
                "state",
                "challenge"));
            emittedUrls.add(providers.tokenUri(provider));
            String revokeUri = providers.revokeUri(provider);
            if (revokeUri != null) {
                emittedUrls.add(revokeUri);
            }
        }

        for (String emittedUrl : emittedUrls) {
            String host = URI.create(emittedUrl).getHost();
            assertTrue(expectedHosts.contains(host), emittedUrl);
            assertFalse(host.contains("connexcrm"), emittedUrl);
            assertFalse(host.equals("connex") || host.startsWith("connex."), emittedUrl);
        }
    }

    @Test
    void managedModeUsesManagedClientIdentityAndOptionalSecret() {
        ConnectedAccountProperties properties = managedProperties();
        properties.getGoogle().setClientId("custom-google-id");
        properties.getGoogle().setClientSecret("custom-google-secret");
        properties.getManaged().getGoogle().setClientSecret(null);
        ConnectedAccountProviders providers = new ConnectedAccountProviders(properties);

        assertTrue(providers.isEnabled(ConnectedAccountProviders.GOOGLE));
        assertEquals(ConnectedAccountMode.MANAGED,
            providers.mode(ConnectedAccountProviders.GOOGLE));
        assertEquals("managed-google-id",
            providers.effectiveClientId(ConnectedAccountProviders.GOOGLE));
        assertNull(providers.effectiveClientSecret(ConnectedAccountProviders.GOOGLE));
    }

    private static ConnectedAccountProperties managedProperties() {
        ConnectedAccountProperties properties = new ConnectedAccountProperties();
        properties.getGoogle().setEnabled(true);
        properties.getGoogle().setMode(ConnectedAccountMode.MANAGED);
        properties.getManaged().getGoogle().setClientId("managed-google-id");
        properties.getMicrosoft().setEnabled(true);
        properties.getMicrosoft().setMode(ConnectedAccountMode.MANAGED);
        properties.getManaged().getMicrosoft().setClientId("managed-microsoft-id");
        properties.getManaged().getMicrosoft().setClientSecret("managed-microsoft-secret");
        return properties;
    }
}
