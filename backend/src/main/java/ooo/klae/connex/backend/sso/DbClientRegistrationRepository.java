package ooo.klae.connex.backend.sso;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

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
 *
 * <p>Every successful resolution re-validates the issuer and all discovered destinations against the
 * outbound address policy, in a single bounded transport task, so a warm cache hit costs one
 * unit of transport capacity rather than one per endpoint. Provider failures are cached for the
 * discovery TTL against the connection identity; shared-capacity failures remain retryable.
 */
@Component
@RequiredArgsConstructor
public class DbClientRegistrationRepository implements ClientRegistrationRepository {

    private static final Logger log = LoggerFactory.getLogger(DbClientRegistrationRepository.class);

    private static final String REGISTRATION_PREFIX = "org-";
    private static final String[] DEFAULT_SCOPES = { "openid", "email", "profile" };
    private static final Duration DISCOVERY_CACHE_TTL = Duration.ofMinutes(10);
    private static final String TEMPLATE_SECRET = "<redacted>";
    private static final int FIRST_RFC8414_PATH_INDEX = 1;
    private static final int MAX_CAUSE_DEPTH = 8;
    private static final int RESOLUTIONS_PER_REGISTRATION = 2;

    private final SsoConnectionMapper ssoConnectionMapper;
    private final SsoSecretCipher ssoSecretCipher;
    private final SsoProperties ssoProperties;
    private final SsoHttpClient ssoHttpClient;
    private final Clock clock = Clock.systemUTC();

    private final ConcurrentHashMap<String, CachedTemplate> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, CachedFailure> failures = new ConcurrentHashMap<>();
    private final SsoTransportSlots registrationSlots = new SsoTransportSlots(RESOLUTIONS_PER_REGISTRATION);

    @Override
    public ClientRegistration findByRegistrationId(String registrationId) {
        Integer orgId = parseOrgId(registrationId);
        if (orgId == null) {
            return null;
        }
        if (!ssoProperties.isEnabled()) {
            log.debug("Enterprise OIDC resolution refused for org {}: the instance SSO kill switch is off", orgId);
            return null;
        }
        SsoConnection connection = ssoConnectionMapper.findByOrg(orgId);
        if (!admitted(connection)) {
            log.debug("Enterprise OIDC resolution refused for org {}: no enabled OIDC connection", orgId);
            return null;
        }
        ConnectionIdentity identity = ConnectionIdentity.of(connection);
        if (identity.issuer() == null || identity.clientId() == null) {
            log.warn("Enterprise OIDC resolution refused for org {}: the stored connection is incomplete", orgId);
            return null;
        }
        Instant now = clock.instant();
        CachedFailure failure = failures.get(orgId);
        if (failure != null) {
            if (failure.identity().equals(identity)
                    && failure.allowPrivateIssuerHosts() == ssoProperties.isAllowPrivateIssuerHosts()
                    && now.isBefore(failure.cachedAt().plus(DISCOVERY_CACHE_TTL))) {
                return null;
            }
            failures.remove(orgId, failure);
        }
        try (var lease = registrationSlots.acquire(Integer.toString(orgId), 0)) {
            CachedTemplate cached = cache.get(registrationId);
            ClientRegistration template;
            if (cached != null && cached.identity().equals(identity) && !cached.expired(now)) {
                template = cached.template();
                ssoHttpClient.requireSafeEnterpriseDestinations(orgId, destinations(identity.issuer(), template));
            } else {
                ssoHttpClient.requireSafeEnterpriseDestinations(orgId, List.of(identity.issuer()));
                template = buildTemplate(orgId, registrationId, identity, connection);
                ssoHttpClient.requireSafeEnterpriseDestinations(orgId, destinations(identity.issuer(), template));
                cache.put(registrationId, new CachedTemplate(identity, template, now));
            }
            SsoConnection current = ssoConnectionMapper.findByOrg(orgId);
            if (!admitted(current) || !identity.equals(ConnectionIdentity.of(current))) {
                log.warn("Enterprise OIDC resolution refused for org {}: connection identity changed", orgId);
                return null;
            }
            ClientRegistration registration = withSecret(template, current);
            SsoConnection afterDecryption = ssoConnectionMapper.findByOrg(orgId);
            if (!admitted(afterDecryption) || !identity.equals(ConnectionIdentity.of(afterDecryption))) {
                log.warn("Enterprise OIDC resolution refused for org {}: connection identity changed", orgId);
                return null;
            }
            return registration;
        } catch (IOException | RuntimeException e) {
            if (cacheableFailure(e)) {
                failures.put(orgId, new CachedFailure(identity, ssoProperties.isAllowPrivateIssuerHosts(),
                        clock.instant()));
            }
            log.warn("Enterprise OIDC resolution refused for org {}: {} [{}]",
                    orgId, refusalReason(e), e.getClass().getName());
            log.debug("Enterprise OIDC resolution failure for org {}", orgId, e);
            return null;
        }
    }

    /** Excludes shared-capacity deadlines and interruption, including wrapped transport failures. */
    private static boolean cacheableFailure(Exception failure) {
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (cause instanceof SsoTransportSaturatedException || cause instanceof SsoTransportException) {
                return false;
            }
            cause = cause.getCause();
        }
        return cause == null;
    }

    private static String refusalReason(Exception failure) {
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (cause instanceof SsoTransportSaturatedException) {
                return "OIDC transport saturated";
            }
            if (cause instanceof SsoTransportException transport) {
                return transport.reason() == SsoTransportException.Reason.TIMEOUT
                        ? "transport_timeout" : "transport_interrupted";
            }
            if (cause instanceof SocketTimeoutException) {
                return "transport_timeout";
            }
            cause = cause.getCause();
        }
        if (failure instanceof IllegalArgumentException) {
            return "destination or metadata refused by policy";
        }
        if (failure instanceof IOException || failure instanceof UncheckedIOException) {
            return "transport_failure";
        }
        if (failure instanceof IllegalStateException) {
            return "client secret unavailable";
        }
        if (failure instanceof RestClientException) {
            return "discovery response unusable";
        }
        return "unexpected resolution failure";
    }

    private boolean admitted(SsoConnection connection) {
        return ssoProperties.isEnabled() && connection != null && connection.isEnabled()
                && "oidc".equals(connection.getProtocol());
    }

    /**
     * Kept as a compatibility hook for callers that save SSO settings.
     * @param orgId the organization whose cached registration is stale
     */
    public void evict(int orgId) {
        cache.remove(REGISTRATION_PREFIX + orgId);
        failures.remove(orgId);
    }

    private ClientRegistration buildTemplate(int orgId, String registrationId, ConnectionIdentity identity,
            SsoConnection connection) {
        return discover(orgId, identity.issuer())
                .registrationId(registrationId)
                .clientId(identity.clientId())
                .clientSecret(TEMPLATE_SECRET)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/api/login/oauth2/code/{registrationId}")
                .scope(scopes(connection.getOidcScopes()))
                .build();
    }

    /**
     * Removes terminal path slashes only for discovery URL composition. Metadata issuer and token
     * {@code iss} validation retain the configured issuer byte-for-byte, including its trailing slash.
     */
    private ClientRegistration.Builder discover(int orgId, String issuer) {
        URI uri = URI.create(issuer);
        String path = uri.getRawPath();
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        List<String> paths = List.of(path + "/.well-known/openid-configuration",
                "/.well-known/openid-configuration" + path,
                "/.well-known/oauth-authorization-server" + path);
        for (int index = 0; index < paths.size(); index++) {
            String discoveryPath = paths.get(index);
            URI discoveryUri = UriComponentsBuilder.fromUri(uri).replacePath(discoveryPath).build(true).toUri();
            Map<String, Object> metadata;
            try {
                metadata = ssoHttpClient.metadata(orgId, discoveryUri);
            } catch (HttpClientErrorException e) {
                continue;
            }
            if (metadata == null || !issuer.equals(metadata.get("issuer"))) {
                throw new IllegalArgumentException("OIDC discovery issuer mismatch");
            }
            return index >= FIRST_RFC8414_PATH_INDEX
                    ? fromServerMetadata(metadata, issuer) : ClientRegistrations.fromOidcConfiguration(metadata);
        }
        throw new IllegalArgumentException("OIDC discovery unavailable");
    }

    /**
     * Builds a registration from RFC 8414 metadata, which the OIDC-only parser rejects because it
     * carries no {@code subject_types_supported} or {@code id_token_signing_alg_values_supported}.
     * Both fallback paths use this reader, matching Spring Security's issuer discovery ordering;
     * only the initial issuer-relative OIDC discovery path uses the strict OIDC reader.
     */
    private ClientRegistration.Builder fromServerMetadata(Map<String, Object> metadata, String issuer) {
        String authorizationUri = requiredText(metadata, "authorization_endpoint");
        String tokenUri = requiredText(metadata, "token_endpoint");
        String jwkSetUri = requiredText(metadata, "jwks_uri");
        String userInfoUri = optionalText(metadata, "userinfo_endpoint");
        return ClientRegistration.withRegistrationId(URI.create(issuer).getHost())
                .issuerUri(issuer).clientName(issuer).userNameAttributeName("sub")
                .authorizationUri(authorizationUri).tokenUri(tokenUri)
                .jwkSetUri(jwkSetUri).userInfoUri(userInfoUri)
                .providerConfigurationMetadata(metadata);
    }

    private static String requiredText(Map<String, Object> metadata, String key) {
        String value = optionalText(metadata, key);
        if (value == null) {
            throw new IllegalArgumentException("OIDC metadata is incomplete");
        }
        return value;
    }

    private static String optionalText(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("OIDC metadata is incomplete");
        }
        return text;
    }

    private static List<String> destinations(String issuer, ClientRegistration template) {
        ClientRegistration.ProviderDetails provider = template.getProviderDetails();
        List<String> urls = new ArrayList<>();
        urls.add(issuer);
        urls.add(requiredEndpoint(provider.getAuthorizationUri()));
        urls.add(requiredEndpoint(provider.getTokenUri()));
        urls.add(requiredEndpoint(provider.getJwkSetUri()));
        String userInfoUri = provider.getUserInfoEndpoint().getUri();
        if (userInfoUri != null && !userInfoUri.isBlank()) {
            urls.add(userInfoUri);
        }
        return List.copyOf(urls);
    }

    private static String requiredEndpoint(String uri) {
        if (uri == null || uri.isBlank()) {
            throw new IllegalArgumentException("OIDC metadata is incomplete");
        }
        return uri;
    }

    private ClientRegistration withSecret(ClientRegistration template, SsoConnection connection) {
        String secret = ssoSecretCipher.decryptOidcClientSecret(connection.getOrgId(),
                connection.getOidcClientSecretEnc());
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("OIDC client secret is unavailable");
        }
        return ClientRegistration.withClientRegistration(template)
                .clientId(connection.getOidcClientId())
                .clientSecret(secret)
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

    /** Shares organization parsing between registration lookup and callback transport admission. */
    static Integer parseOrgId(String registrationId) {
        if (registrationId == null || !registrationId.startsWith(REGISTRATION_PREFIX)) {
            return null;
        }
        try {
            return Integer.valueOf(registrationId.substring(REGISTRATION_PREFIX.length()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private record ConnectionIdentity(String issuer, String clientId, String secretReference, String scopes) {
        static ConnectionIdentity of(SsoConnection connection) {
            return new ConnectionIdentity(trimmed(connection.getOidcIssuer()), connection.getOidcClientId(),
                    connection.getOidcClientSecretEnc(), connection.getOidcScopes());
        }

        private static String trimmed(String value) {
            return value == null ? null : value.trim();
        }
    }

    private record CachedTemplate(ConnectionIdentity identity, ClientRegistration template, Instant cachedAt) {
        boolean expired(Instant now) {
            return cachedAt.plus(DISCOVERY_CACHE_TTL).isBefore(now);
        }
    }

    private record CachedFailure(ConnectionIdentity identity, boolean allowPrivateIssuerHosts, Instant cachedAt) {
    }
}
