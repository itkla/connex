package ooo.klae.connex.backend.connectedaccounts;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * Static provider catalog for connected accounts: endpoints, scopes, and authorize-URL
 * construction. Endpoints are hard-coded provider hosts — never derived from request input —
 * so the connect flow has no server-side request-forgery surface. Scopes are the minimum the
 * epic's capture workstreams need today (read-only mail + calendar plus an OpenID identity for
 * display); widening them is a deliberate later change.
 */
@Component
@RequiredArgsConstructor
public class ConnectedAccountProviders {

    /** Provider id for Google connected accounts. */
    public static final String GOOGLE = "google";
    /** Provider id for Microsoft connected accounts. */
    public static final String MICROSOFT = "microsoft";

    private static final String GOOGLE_AUTHORIZE_URI = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String GOOGLE_TOKEN_URI = "https://oauth2.googleapis.com/token";
    private static final String GOOGLE_REVOKE_URI = "https://oauth2.googleapis.com/revoke";
    private static final String GOOGLE_SCOPES =
        "openid email https://www.googleapis.com/auth/gmail.readonly https://www.googleapis.com/auth/calendar.readonly";

    private static final String MICROSOFT_AUTHORIZE_URI = "https://login.microsoftonline.com/common/oauth2/v2.0/authorize";
    private static final String MICROSOFT_TOKEN_URI = "https://login.microsoftonline.com/common/oauth2/v2.0/token";
    private static final String MICROSOFT_SCOPES =
        "openid email offline_access https://graph.microsoft.com/Mail.Read https://graph.microsoft.com/Calendars.Read";
    private static final List<String> EGRESS_HOSTS = List.of(
        URI.create(GOOGLE_AUTHORIZE_URI).getHost(),
        URI.create(GOOGLE_TOKEN_URI).getHost(),
        "www.googleapis.com",
        "gmail.googleapis.com",
        URI.create(MICROSOFT_AUTHORIZE_URI).getHost(),
        "graph.microsoft.com");

    private final ConnectedAccountProperties properties;

    /**
     * Whether the id names a supported provider.
     * @param provider candidate id
     * @return true for {@code google} or {@code microsoft}
     */
    public boolean isSupported(String provider) {
        return GOOGLE.equals(provider) || MICROSOFT.equals(provider);
    }

    /**
     * Whether the provider is enabled and fully configured with an OAuth client.
     * @param provider provider id
     * @return true when available; unknown providers are never available
     */
    public boolean isEnabled(String provider) {
        return switch (provider) {
            case GOOGLE -> isConfigured(
                properties.getGoogle(), properties.getManaged().getGoogle());
            case MICROSOFT -> isConfigured(
                properties.getMicrosoft(), properties.getManaged().getMicrosoft());
            default -> false;
        };
    }

    /** Whether Google connected accounts are enabled and configured. */
    public boolean isGoogleEnabled() {
        return isEnabled(GOOGLE);
    }

    /** Whether Microsoft connected accounts are enabled and configured. */
    public boolean isMicrosoftEnabled() {
        return isEnabled(MICROSOFT);
    }

    /**
     * The configured OAuth client for a provider.
     * @param provider provider id
     * @return the client config; never null for a supported provider
     */
    public ConnectedAccountProperties.Provider client(String provider) {
        return switch (provider) {
            case GOOGLE -> properties.getGoogle();
            case MICROSOFT -> properties.getMicrosoft();
            default -> throw new IllegalArgumentException("Unsupported provider: " + provider);
        };
    }

    /** The configured ownership mode for a supported provider. */
    public ConnectedAccountMode mode(String provider) {
        return client(provider).getMode();
    }

    /** The OAuth client id effective for the provider's configured ownership mode. */
    public String effectiveClientId(String provider) {
        return mode(provider) == ConnectedAccountMode.MANAGED
            ? managedClient(provider).getClientId()
            : client(provider).getClientId();
    }

    /** The OAuth client secret effective for the provider's configured ownership mode. */
    public String effectiveClientSecret(String provider) {
        return mode(provider) == ConnectedAccountMode.MANAGED
            ? managedClient(provider).getClientSecret()
            : client(provider).getClientSecret();
    }

    /** The provider's token endpoint (fixed host). */
    public String tokenUri(String provider) {
        return switch (provider) {
            case GOOGLE -> GOOGLE_TOKEN_URI;
            case MICROSOFT -> MICROSOFT_TOKEN_URI;
            default -> throw new IllegalArgumentException("Unsupported provider: " + provider);
        };
    }

    /** The provider's token-revocation endpoint, or null when the provider has none. */
    public String revokeUri(String provider) {
        return GOOGLE.equals(provider) ? GOOGLE_REVOKE_URI : null;
    }

    /** The scopes requested at consent, space-delimited. */
    public String scopes(String provider) {
        return switch (provider) {
            case GOOGLE -> GOOGLE_SCOPES;
            case MICROSOFT -> MICROSOFT_SCOPES;
            default -> throw new IllegalArgumentException("Unsupported provider: " + provider);
        };
    }

    /** Whether the provider reported the least-privilege grant required for a capture stream. */
    public boolean hasCaptureScope(
            String provider, String grantedScopes, String stream) {
        if (grantedScopes == null || grantedScopes.isBlank()) {
            return false;
        }
        Set<String> granted = java.util.Arrays.stream(
                grantedScopes.trim().split("\\s+"))
            .map(value -> value.toLowerCase(Locale.ROOT))
            .collect(Collectors.toUnmodifiableSet());
        String required = switch (provider + ":" + stream) {
            case GOOGLE + ":calendar" ->
                "https://www.googleapis.com/auth/calendar.readonly";
            case GOOGLE + ":mail_inbox", GOOGLE + ":mail_sent" ->
                "https://www.googleapis.com/auth/gmail.readonly";
            case MICROSOFT + ":calendar" -> "calendars.read";
            case MICROSOFT + ":mail_inbox", MICROSOFT + ":mail_sent" -> "mail.read";
            default -> null;
        };
        if (required == null) {
            return false;
        }
        return granted.contains(required)
            || granted.stream().anyMatch(
                value -> value.endsWith("/" + required));
    }

    /**
     * Builds the provider consent URL the browser navigates to. {@code state} is the
     * session-bound single-use value the callback validates; {@code redirectUri} is derived
     * from the trusted configured app base URL, never from the request.
     *
     * @param provider provider id
     * @param redirectUri the callback URI registered with the provider
     * @param state session-bound anti-forgery value
     * @return the fully encoded authorize URL
     */
    public String authorizeUrl(String provider, String redirectUri, String state) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("client_id", effectiveClientId(provider));
        params.put("redirect_uri", redirectUri);
        params.put("response_type", "code");
        params.put("scope", scopes(provider));
        params.put("state", state);
        if (GOOGLE.equals(provider)) {
            params.put("access_type", "offline");
            params.put("prompt", "consent");
        } else {
            params.put("response_mode", "query");
        }
        StringBuilder url = new StringBuilder(GOOGLE.equals(provider) ? GOOGLE_AUTHORIZE_URI : MICROSOFT_AUTHORIZE_URI);
        char separator = '?';
        for (Map.Entry<String, String> entry : params.entrySet()) {
            url.append(separator)
                .append(entry.getKey())
                .append('=')
                .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
            separator = '&';
        }
        return url.toString();
    }

    /** Builds an installed-application authorize URL carrying an S256 PKCE challenge. */
    public String nativeAuthorizeUrl(
            String provider,
            String redirectUri,
            String state,
            String codeChallenge) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("client_id", effectiveClientId(provider));
        params.put("redirect_uri", redirectUri);
        params.put("response_type", "code");
        params.put("scope", scopes(provider));
        params.put("state", state);
        params.put("code_challenge", codeChallenge);
        params.put("code_challenge_method", "S256");
        if (GOOGLE.equals(provider)) {
            params.put("access_type", "offline");
            params.put("prompt", "consent");
        } else {
            params.put("response_mode", "query");
        }
        return authorizeUrl(provider, params);
    }

    /** Exact operator egress allowlist for connected-account authorization and capture. */
    public static List<String> egressHosts() {
        return EGRESS_HOSTS;
    }

    private String authorizeUrl(String provider, Map<String, String> params) {
        StringBuilder url = new StringBuilder(
            GOOGLE.equals(provider) ? GOOGLE_AUTHORIZE_URI : MICROSOFT_AUTHORIZE_URI);
        char separator = '?';
        for (Map.Entry<String, String> entry : params.entrySet()) {
            url.append(separator)
                .append(entry.getKey())
                .append('=')
                .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
            separator = '&';
        }
        return url.toString();
    }

    private ConnectedAccountProperties.ManagedClient managedClient(String provider) {
        return switch (provider) {
            case GOOGLE -> properties.getManaged().getGoogle();
            case MICROSOFT -> properties.getManaged().getMicrosoft();
            default -> throw new IllegalArgumentException("Unsupported provider: " + provider);
        };
    }

    private static boolean isConfigured(
            ConnectedAccountProperties.Provider provider,
            ConnectedAccountProperties.ManagedClient managedClient) {
        if (!provider.isEnabled() || provider.getMode() == null) {
            return false;
        }
        if (provider.getMode() == ConnectedAccountMode.MANAGED) {
            return managedClient.getClientId() != null
                && !managedClient.getClientId().isBlank();
        }
        return provider.getClientId() != null && !provider.getClientId().isBlank()
            && provider.getClientSecret() != null && !provider.getClientSecret().isBlank();
    }
}
