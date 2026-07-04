package ooo.klae.connex.backend.sso;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.SsoConnection;
import ooo.klae.connex.backend.mappers.SsoConnectionMapper;

/**
 * Resolves a Spring Security {@link ClientRegistration} for a per-organization OIDC
 * connection at login time. The registration id encodes the organization as
 * {@code org-<id>}; the connection is looked up by that org, and a registration is
 * built from the stored issuer (blocking OIDC discovery), client id, and decrypted
 * client secret. Only enabled OIDC connections resolve — anything else returns null
 * so the OAuth2 machinery treats the id as unknown. Built registrations are cached by
 * registration id and evicted when an organization's connection changes.
 */
@Component
@RequiredArgsConstructor
public class DbClientRegistrationRepository implements ClientRegistrationRepository {

    private static final String REGISTRATION_PREFIX = "org-";
    private static final String[] DEFAULT_SCOPES = { "openid", "email", "profile" };

    private final SsoConnectionMapper ssoConnectionMapper;
    private final SsoSecretCipher ssoSecretCipher;

    private final ConcurrentHashMap<String, ClientRegistration> cache = new ConcurrentHashMap<>();

    @Override
    public ClientRegistration findByRegistrationId(String registrationId) {
        Integer orgId = parseOrgId(registrationId);
        if (orgId == null) {
            return null;
        }
        ClientRegistration cached = cache.get(registrationId);
        if (cached != null) {
            return cached;
        }
        SsoConnection connection = ssoConnectionMapper.findByOrg(orgId);
        if (connection == null || !connection.isEnabled() || !"oidc".equals(connection.getProtocol())) {
            return null;
        }
        ClientRegistration registration = build(registrationId, connection);
        cache.put(registrationId, registration);
        return registration;
    }

    /**
     * Drops the cached registration for an organization so the next login rebuilds it
     * from the current connection. Called when an organization's SSO config changes.
     * @param orgId the organization whose cached registration is stale
     */
    public void evict(int orgId) {
        cache.remove(REGISTRATION_PREFIX + orgId);
    }

    private ClientRegistration build(String registrationId, SsoConnection connection) {
        return ClientRegistrations.fromIssuerLocation(connection.getOidcIssuer())
                .registrationId(registrationId)
                .clientId(connection.getOidcClientId())
                .clientSecret(ssoSecretCipher.decrypt(connection.getOidcClientSecretEnc()))
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/api/login/oauth2/code/{registrationId}")
                .scope(scopes(connection.getOidcScopes()))
                .build();
    }

    private static String[] scopes(String csv) {
        if (csv == null || csv.isBlank()) {
            return DEFAULT_SCOPES.clone();
        }
        String[] parsed = Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(scope -> !scope.isEmpty())
                .toArray(String[]::new);
        return parsed.length == 0 ? DEFAULT_SCOPES.clone() : parsed;
    }

    private static Integer parseOrgId(String registrationId) {
        if (registrationId == null || !registrationId.startsWith(REGISTRATION_PREFIX)) {
            return null;
        }
        try {
            return Integer.valueOf(registrationId.substring(REGISTRATION_PREFIX.length()));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
