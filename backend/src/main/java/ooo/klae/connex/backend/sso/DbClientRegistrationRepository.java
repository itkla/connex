package ooo.klae.connex.backend.sso;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * so the OAuth2 machinery treats the id as unknown. Issuer metadata is cached only
 * as a secret-free template; every returned registration receives a fresh decrypted
 * client secret.
 */
@Component
@RequiredArgsConstructor
public class DbClientRegistrationRepository implements ClientRegistrationRepository {

    private static final Logger log = LoggerFactory.getLogger(DbClientRegistrationRepository.class);

    private static final String REGISTRATION_PREFIX = "org-";
    private static final String[] DEFAULT_SCOPES = { "openid", "email", "profile" };
    private static final Duration DISCOVERY_CACHE_TTL = Duration.ofMinutes(10);
    private static final String TEMPLATE_SECRET = "<redacted>";

    private final SsoConnectionMapper ssoConnectionMapper;
    private final SsoSecretCipher ssoSecretCipher;
    private final SsoProperties ssoProperties;

    private final ConcurrentHashMap<String, CachedTemplate> cache = new ConcurrentHashMap<>();

    @Override
    public ClientRegistration findByRegistrationId(String registrationId) {
        Integer orgId = parseOrgId(registrationId);
        if (orgId == null) {
            return null;
        }
        SsoConnection connection = ssoConnectionMapper.findByOrg(orgId);
        if (connection == null || !connection.isEnabled() || !"oidc".equals(connection.getProtocol())) {
            return null;
        }
        if (!SsoUrlSafety.isFetchableHttpUrl(connection.getOidcIssuer(), ssoProperties.isAllowPrivateIssuerHosts())) {
            log.warn("OIDC issuer for org {} is not a permitted public URL; skipping", orgId);
            return null;
        }
        Instant now = Instant.now();
        CachedTemplate cached = cache.get(registrationId);
        if (cached != null && !cached.expired(now)) {
            return cached.template() == null ? null : withSecret(cached.template(), connection);
        }
        try {
            ClientRegistration template = buildTemplate(registrationId, connection);
            cache.put(registrationId, CachedTemplate.success(template, now));
            return withSecret(template, connection);
        } catch (RuntimeException e) {
            log.warn("Failed to build OIDC client registration for org {}: {}", orgId, e.getMessage());
            cache.put(registrationId, CachedTemplate.failure(now));
            return null;
        }
    }

    /**
     * Kept as a compatibility hook for callers that save SSO settings.
     * @param orgId the organization whose cached registration is stale
     */
    public void evict(int orgId) {
        cache.remove(REGISTRATION_PREFIX + orgId);
    }

    private ClientRegistration buildTemplate(String registrationId, SsoConnection connection) {
        return ClientRegistrations.fromIssuerLocation(connection.getOidcIssuer())
                .registrationId(registrationId)
                .clientId(connection.getOidcClientId())
                .clientSecret(TEMPLATE_SECRET)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/api/login/oauth2/code/{registrationId}")
                .scope(scopes(connection.getOidcScopes()))
                .build();
    }

    private ClientRegistration withSecret(ClientRegistration template, SsoConnection connection) {
        return ClientRegistration.withClientRegistration(template)
                .clientId(connection.getOidcClientId())
                .clientSecret(ssoSecretCipher.decryptOidcClientSecret(connection.getOrgId(),
                        connection.getOidcClientSecretEnc()))
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

    private record CachedTemplate(ClientRegistration template, Instant cachedAt) {
        static CachedTemplate success(ClientRegistration template, Instant cachedAt) {
            return new CachedTemplate(template, cachedAt);
        }

        static CachedTemplate failure(Instant cachedAt) {
            return new CachedTemplate(null, cachedAt);
        }

        boolean expired(Instant now) {
            return cachedAt.plus(DISCOVERY_CACHE_TTL).isBefore(now);
        }
    }
}
