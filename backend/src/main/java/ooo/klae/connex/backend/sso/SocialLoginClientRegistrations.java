package ooo.klae.connex.backend.sso;

import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * Builds the static, instance-wide {@link ClientRegistration}s for consumer social login
 * (Sign in with Google / Microsoft) from {@link SocialLoginProperties}. Each registration is
 * a single OAuth client for the whole instance (not per-organization). Google uses Spring's
 * built-in provider metadata; Microsoft uses the multi-tenant {@code common} endpoints, whose
 * per-tenant token issuer is handled by a lenient id-token validator in {@code SecurityConfig}.
 */
@Component
@RequiredArgsConstructor
public class SocialLoginClientRegistrations {

    /** Registration id for Google social login. */
    public static final String GOOGLE = "google";
    /** Registration id for Microsoft social login. */
    public static final String MICROSOFT = "microsoft";

    private static final String REDIRECT_URI = "{baseUrl}/api/login/oauth2/code/{registrationId}";

    private final SocialLoginProperties properties;

    /**
     * Whether a registration id names a social provider (as opposed to an {@code org-<id>} SSO one).
     * @param registrationId the registration id
     * @return true for {@code google} or {@code microsoft}
     */
    public boolean isSocialRegistration(String registrationId) {
        return GOOGLE.equals(registrationId) || MICROSOFT.equals(registrationId);
    }

    /**
     * Resolves a social provider's client registration, or null when the provider is disabled
     * or unconfigured.
     * @param registrationId {@code google} or {@code microsoft}
     * @return the registration, or null
     */
    public ClientRegistration find(String registrationId) {
        return switch (registrationId) {
            case GOOGLE -> google();
            case MICROSOFT -> microsoft();
            default -> null;
        };
    }

    /**
     * Whether Google social login is enabled and fully configured.
     * @return true when Google is available
     */
    public boolean isGoogleEnabled() {
        return isConfigured(properties.getGoogle());
    }

    /**
     * Whether Microsoft social login is enabled and fully configured.
     * @return true when Microsoft is available
     */
    public boolean isMicrosoftEnabled() {
        return isConfigured(properties.getMicrosoft());
    }

    /**
     * Whether any social provider is enabled and configured.
     * @return true when at least one social provider is available
     */
    public boolean anyEnabled() {
        return isGoogleEnabled() || isMicrosoftEnabled();
    }

    private static boolean isConfigured(SocialLoginProperties.Provider provider) {
        return provider.isEnabled()
                && provider.getClientId() != null && !provider.getClientId().isBlank()
                && provider.getClientSecret() != null && !provider.getClientSecret().isBlank();
    }

    private ClientRegistration google() {
        SocialLoginProperties.Provider provider = properties.getGoogle();
        if (!isConfigured(provider)) {
            return null;
        }
        return ClientRegistration.withRegistrationId(GOOGLE)
                .clientId(provider.getClientId())
                .clientSecret(provider.getClientSecret())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(REDIRECT_URI)
                .scope("openid", "email", "profile")
                .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                .tokenUri("https://oauth2.googleapis.com/token")
                .jwkSetUri("https://www.googleapis.com/oauth2/v3/certs")
                .issuerUri("https://accounts.google.com")
                .userInfoUri("https://openidconnect.googleapis.com/v1/userinfo")
                .userNameAttributeName("sub")
                .clientName("Google")
                .build();
    }

    private ClientRegistration microsoft() {
        SocialLoginProperties.Provider provider = properties.getMicrosoft();
        if (!isConfigured(provider)) {
            return null;
        }
        return ClientRegistration.withRegistrationId(MICROSOFT)
                .clientId(provider.getClientId())
                .clientSecret(provider.getClientSecret())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(REDIRECT_URI)
                .scope("openid", "email", "profile")
                .authorizationUri("https://login.microsoftonline.com/common/oauth2/v2.0/authorize")
                .tokenUri("https://login.microsoftonline.com/common/oauth2/v2.0/token")
                .jwkSetUri("https://login.microsoftonline.com/common/discovery/v2.0/keys")
                .issuerUri("https://login.microsoftonline.com/common/v2.0")
                .userInfoUri("https://graph.microsoft.com/oidc/userinfo")
                .userNameAttributeName("sub")
                .clientName("Microsoft")
                .build();
    }
}
