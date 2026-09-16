package ooo.klae.connex.backend.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.oauth2.client.authentication.OAuth2LoginAuthenticationToken;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizationRequestRepository;
import org.springframework.security.oauth2.client.web.OAuth2LoginAuthenticationFilter;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationExchange;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationResponse;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.util.UriComponentsBuilder;
import org.slf4j.LoggerFactory;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import ooo.klae.connex.backend.beans.SsoConnection;
import ooo.klae.connex.backend.businesscard.BusinessCardRateLimiter;
import ooo.klae.connex.backend.capability.CapabilityEntitlement;
import ooo.klae.connex.backend.mappers.SsoConnectionMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.notifications.WebSocketSessionRegistry;
import ooo.klae.connex.backend.services.AuditService;
import ooo.klae.connex.backend.services.LoginRateLimiter;
import ooo.klae.connex.backend.services.PrivilegedAccountService;
import ooo.klae.connex.backend.services.SessionSecurityService;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.sso.CompositeClientRegistrationRepository;
import ooo.klae.connex.backend.sso.DbClientRegistrationRepository;
import ooo.klae.connex.backend.sso.DbRelyingPartyRegistrationRepository;
import ooo.klae.connex.backend.sso.SocialLoginClientRegistrations;
import ooo.klae.connex.backend.sso.SocialLoginProperties;
import ooo.klae.connex.backend.sso.SsoAuthenticationSuccessHandler;
import ooo.klae.connex.backend.sso.SsoHttpClient;
import ooo.klae.connex.backend.sso.SsoHttpClientTestSupport;
import ooo.klae.connex.backend.sso.SsoProperties;
import ooo.klae.connex.backend.sso.SsoSecretCipher;
import ooo.klae.connex.backend.sso.SsoTransportSaturatedException;
import ooo.klae.connex.backend.tenant.WorkspaceCookie;
import ooo.klae.connex.backend.tenant.WorkspaceRequestResolver;
import ooo.klae.connex.backend.util.ClientIpResolver;
import ooo.klae.connex.backend.webauthn.WebAuthnService;

@SpringJUnitWebConfig(SecurityConfigOAuthTest.TestConfig.class)
class SecurityConfigOAuthTest {
    private static final String ISSUER = "https://93.184.216.34";
    @Autowired private WebApplicationContext context;
    @Autowired private Filter springSecurityFilterChain;
    @Autowired private TestConfig fixtures;
    private MockMvc mvc;
    private HttpServer sentinel;
    private String sentinelUrl;
    private final AtomicInteger sentinelRequests = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        reset(fixtures.mapper, fixtures.cipher, fixtures.success, fixtures.http);
        fixtures.rebound.set(false);
        fixtures.properties.setEnabled(false);
        fixtures.properties.setAllowPrivateIssuerHosts(false);
        fixtures.enterprise.evict(17);
        SsoConnection connection = new SsoConnection();
        connection.setOrgId(17);
        connection.setEnabled(true);
        connection.setProtocol("oidc");
        connection.setOidcIssuer(ISSUER);
        connection.setOidcClientId("enterprise-client");
        connection.setOidcClientSecretEnc("test-reference");
        when(fixtures.mapper.findByOrg(17)).thenReturn(connection);
        when(fixtures.cipher.decryptOidcClientSecret(17, "test-reference")).thenReturn("test-secret");
        doReturn(metadata()).when(fixtures.http).metadata(anyInt(), any(URI.class));
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(springSecurityFilterChain).build();
        sentinel = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        sentinel.createContext("/", exchange -> {
            sentinelRequests.incrementAndGet();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        sentinel.start();
        sentinelUrl = "http://127.0.0.1:" + sentinel.getAddress().getPort() + "/probe";
    }

    @AfterEach
    void tearDown() {
        if (fixtures.callbackFilter != null && fixtures.providerManager != null) {
            fixtures.callbackFilter.setAuthenticationManager(fixtures.providerManager);
        }
        sentinel.stop(0);
    }

    @Test
    void disabledEnterpriseCannotInitiateOrCompleteWhileGoogleCanAuthenticate() throws Exception {
        acceptProviderAuthentication();
        mvc.perform(get("/api/oauth2/authorization/org-17"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/auth/login?sso_error=1"));
        callback("org-17", pending("org-17")).andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/auth/login?sso_error=1"));
        verifyNoInteractions(fixtures.success);
        verifyNoInteractions(fixtures.mapper);

        var initiation = mvc.perform(get("/api/oauth2/authorization/google"))
                .andExpect(status().is3xxRedirection()).andReturn();
        String authorizationLocation = initiation.getResponse().getRedirectedUrl();
        assertNotNull(authorizationLocation);
        assertTrue(authorizationLocation.startsWith("https://accounts.google.com/"));
        MockHttpSession session = pending("google");
        callback("google", session).andExpect(status().isOk());
        verify(fixtures.success).onAuthenticationSuccess(any(), any(), any());
        assertEquals(0, sentinelRequests.get());
    }

    @Test
    void callbackRechecksKillSwitchAfterSuccessfulEnterpriseInitiation() throws Exception {
        acceptProviderAuthentication();
        fixtures.properties.setEnabled(true);
        String location = mvc.perform(get("/api/oauth2/authorization/org-17"))
                .andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
        assertNotNull(location);
        assertTrue(location.startsWith(ISSUER + "/authorize"));
        MockHttpSession session = pending("org-17");
        fixtures.properties.setEnabled(false);
        callback("org-17", session).andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/auth/login?sso_error=1"));
        verifyNoInteractions(fixtures.success);
        assertNull(session.getAttribute("SPRING_SECURITY_CONTEXT"));
    }

    @ParameterizedTest
    @ValueSource(strings = { "token_endpoint", "jwks_uri", "userinfo_endpoint", "authorization_endpoint" })
    void publicDiscoveryWithPrivateEndpointCannotInitiateOrReachSentinelOnCallback(String endpoint) throws Exception {
        fixtures.properties.setEnabled(true);
        Map<String, Object> metadata = metadata();
        metadata.put(endpoint, sentinelUrl.replace("http:", "https:"));
        doReturn(metadata).when(fixtures.http).metadata(anyInt(), any(URI.class));
        mvc.perform(get("/api/oauth2/authorization/org-17"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/auth/login?sso_error=1"));
        callback("org-17", pending("org-17")).andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/auth/login?sso_error=1"));
        verifyNoInteractions(fixtures.success);
        assertEquals(0, sentinelRequests.get());
    }

    @Test
    void rejectedInitiationIsIndistinguishableAcrossOrganizations() throws Exception {
        fixtures.properties.setEnabled(true);
        when(fixtures.mapper.findByOrg(18)).thenReturn(null);
        Map<String, Object> metadata = metadata();
        metadata.put("token_endpoint", sentinelUrl);
        doReturn(metadata).when(fixtures.http).metadata(anyInt(), any(URI.class));
        mvc.perform(get("/api/oauth2/authorization/org-17"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/auth/login?sso_error=1"));
        mvc.perform(get("/api/oauth2/authorization/org-18"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/auth/login?sso_error=1"));
        assertEquals(0, sentinelRequests.get());
    }

    @ParameterizedTest
    @ValueSource(strings = { "org-18", "microsoft" })
    void rejectedInitiationLogsOnlyTheExceptionClassBeforeRedirecting(String registrationId) throws Exception {
        Logger logger = assertInstanceOf(Logger.class, LoggerFactory.getLogger(SecurityConfig.class));
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            mvc.perform(get("/api/oauth2/authorization/" + registrationId))
                    .andExpect(redirectedUrl("/auth/login?sso_error=1"));
            List<ILoggingEvent> warnings = appender.list.stream().filter(event -> event.getLevel() == Level.WARN).toList();
            assertEquals(1, warnings.size());
            ILoggingEvent warning = warnings.getFirst();
            assertEquals("OAuth2 initiation failed [{}]", warning.getMessage());
            assertEquals(1, warning.getArgumentArray().length);
            String exceptionClass = assertInstanceOf(String.class, warning.getArgumentArray()[0]);
            assertTrue(exceptionClass.matches("[A-Za-z0-9_.$]+"));
            assertTrue(Exception.class.isAssignableFrom(Class.forName(exceptionClass)));
            assertNull(warning.getThrowableProxy());
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "token", "jwks", "userinfo" })
    void realCallbackRefusesRebindingAtEachProviderStage(String stage) throws Exception {
        fixtures.properties.setEnabled(true);
        String base = "https://localhost:" + sentinel.getAddress().getPort();
        Map<String, Object> metadata = metadata();
        metadata.put("token_endpoint", base + "/token");
        metadata.put("jwks_uri", base + "/jwks");
        metadata.put("userinfo_endpoint", base + "/userinfo");
        doReturn(metadata).when(fixtures.http).metadata(anyInt(), any(URI.class));
        MockHttpSession session = new MockHttpSession();
        var initiation = mvc.perform(get("/api/oauth2/authorization/org-17").session(session))
                .andExpect(status().is3xxRedirection()).andReturn();
        String location = initiation.getResponse().getRedirectedUrl();
        assertNotNull(location);
        assertTrue(location.startsWith(ISSUER + "/authorize"));
        var parameters = UriComponentsBuilder.fromUriString(location).build().getQueryParams();
        String state = parameters.getFirst("state");
        String nonce = parameters.getFirst("nonce");
        assertNotNull(state);
        assertNotNull(nonce);
        state = org.springframework.web.util.UriUtils.decode(state, java.nio.charset.StandardCharsets.UTF_8);
        nonce = org.springframework.web.util.UriUtils.decode(nonce, java.nio.charset.StandardCharsets.UTF_8);
        var key = new com.nimbusds.jose.jwk.gen.RSAKeyGenerator(2048).keyID("test-key").generate();
        String token = signedToken(key, "enterprise-client", nonce);
        Map<String, String> bodies = Map.of(
                "/token", "{\"access_token\":\"test-access\",\"token_type\":\"Bearer\",\"expires_in\":60,\"id_token\":\"" + token + "\"}",
                "/jwks", new com.nimbusds.jose.jwk.JWKSet(key.toPublicJWK()).toString(),
                "/userinfo", "{\"sub\":\"test-subject\"}");
        doAnswer(invocation -> {
            URI uri = invocation.getArgument(1, URI.class);
            HttpMethod method = invocation.getArgument(2, HttpMethod.class);
            if (uri.getPath().equals("/" + stage)) {
                fixtures.rebound.set(true);
                return invocation.callRealMethod();
            }
            String body = bodies.get(uri.getPath());
            assertNotNull(body);
            var request = new MockClientHttpRequest(method, uri);
            var response = new MockClientHttpResponse(body.getBytes(java.nio.charset.StandardCharsets.UTF_8), HttpStatus.OK);
            response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            request.setResponse(response);
            return request;
        }).when(fixtures.http).createEnterpriseRequest(eq(17), any(URI.class), any(HttpMethod.class));

        mvc.perform(get("/api/login/oauth2/code/org-17").session(session).param("code", "test-code").param("state", state))
                .andExpect(redirectedUrl("/auth/login?sso_error=1"));

        verify(fixtures.http).createEnterpriseRequest(17, URI.create(base + "/token"), HttpMethod.POST);
        if (!stage.equals("token")) {
            verify(fixtures.http).createEnterpriseRequest(17, URI.create(base + "/jwks"), HttpMethod.GET);
        }
        if (stage.equals("userinfo")) {
            verify(fixtures.http).createEnterpriseRequest(17, URI.create(base + "/userinfo"), HttpMethod.GET);
        }
        assertTrue(fixtures.rebound.get());
        SsoHttpClientTestSupport.verifyNoHttpClientCreated(fixtures.http);
        assertEquals(0, sentinelRequests.get());
        assertNull(session.getAttribute("SPRING_SECURITY_CONTEXT"));
        verifyNoInteractions(fixtures.success);
    }

    @Test
    void tokenJwksAndUserinfoClientsRefuseLoopbackBeforeEgress() {
        ClientRegistration registration = registration("org-17", sentinelUrl);
        assertThrows(RuntimeException.class, () -> SecurityConfig
                .oidcTokenResponseClient(fixtures.http, fixtures.social).getTokenResponse(grant(registration)));
        assertThrows(RuntimeException.class, () -> SecurityConfig
                .oauth2UserService(fixtures.http, fixtures.social)
                .loadUser(new OAuth2UserRequest(registration, accessToken())));
        clearInvocations(fixtures.http);
        var decoder = new SecurityConfig().idTokenDecoderFactory(fixtures.http, fixtures.social)
                .createDecoder(registration);
        assertThrows(RuntimeException.class, () -> decoder.decode(signedToken()));
        verify(fixtures.http).createEnterpriseRequest(17, URI.create(sentinelUrl),
                org.springframework.http.HttpMethod.GET);
        assertEquals(0, sentinelRequests.get());
    }

    @Test
    void overlappingCallbackStagesShareCanonicalOrganizationCapacity() throws Exception {
        CountDownLatch userInfoEntered = new CountDownLatch(1);
        CountDownLatch tokenEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger stalledRequests = new AtomicInteger();
        ClientRegistration userInfoA = registration("org-17", "https://userinfo.a.example/userinfo");
        ClientRegistration tokenA = registration("org-017", "https://token.a.example/token");
        ClientRegistration tokenB = registration("org-18", "https://token.b.example/token");
        String idToken = signedToken();
        try (SsoHttpClient http = SsoHttpClientTestSupport.callbackClient(fixtures.properties,
                userInfoEntered, tokenEntered, release, stalledRequests);
                var callers = Executors.newFixedThreadPool(3)) {
            var tokens = SecurityConfig.oidcTokenResponseClient(http, fixtures.social);
            var users = SecurityConfig.oauth2UserService(http, fixtures.social);
            var userInfo = callers.submit(() -> users.loadUser(new OAuth2UserRequest(userInfoA, accessToken())));
            var token = callers.submit(() -> tokens.getTokenResponse(grant(tokenA)));
            try {
                assertTrue(userInfoEntered.await(10, TimeUnit.SECONDS));
                assertTrue(tokenEntered.await(10, TimeUnit.SECONDS));
                assertNotNull(callers.submit(() -> tokens.getTokenResponse(grant(tokenB))).get(5, TimeUnit.SECONDS));
                assertSaturated(assertThrows(RuntimeException.class, () -> tokens.getTokenResponse(grant(tokenA))));
                assertSaturated(assertThrows(RuntimeException.class,
                        () -> users.loadUser(new OAuth2UserRequest(userInfoA, accessToken()))));
                var decoder = new SecurityConfig().idTokenDecoderFactory(http, fixtures.social)
                        .createDecoder(registration("org-0017", "https://jwks.a.example/jwks"));
                assertSaturated(assertThrows(RuntimeException.class, () -> decoder.decode(idToken)));
                assertSaturated(assertThrows(RuntimeException.class,
                        () -> http.metadata(17, URI.create("https://issuer.a.example/.well-known/openid-configuration"))));
                assertSaturated(assertThrows(RuntimeException.class,
                        () -> http.requireSafeEnterpriseDestinations(17, List.of("https://issuer.a.example"))));
                assertEquals(2, stalledRequests.get());
            } finally {
                release.countDown();
            }
            assertNotNull(userInfo.get(10, TimeUnit.SECONDS));
            assertNotNull(token.get(10, TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
    }

    private static void assertSaturated(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && !(cause instanceof SsoTransportSaturatedException)) {
            cause = cause.getCause();
        }
        assertInstanceOf(SsoTransportSaturatedException.class, cause);
    }

    @Test
    void tokenAndUserinfoClientsAcceptBoundedProviderResponses() {
        fixtures.properties.setAllowPrivateIssuerHosts(true);
        sentinel.removeContext("/");
        sentinel.createContext("/", exchange -> {
            String body = exchange.getRequestMethod().equals("POST")
                    ? "{\"access_token\":\"test-access\",\"token_type\":\"Bearer\",\"expires_in\":60}"
                    : "{\"sub\":\"test-subject\"}";
            byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        ClientRegistration registration = registration("org-17", sentinelUrl);
        assertNotNull(SecurityConfig.oidcTokenResponseClient(fixtures.http, fixtures.social)
                .getTokenResponse(grant(registration)));
        assertEquals("test-subject", SecurityConfig.oauth2UserService(fixtures.http, fixtures.social)
                .loadUser(new OAuth2UserRequest(registration, accessToken())).getName());
    }

    @Test
    void privateIssuerExemptionNeverAppliesToConsumerSocialLogin() {
        fixtures.properties.setAllowPrivateIssuerHosts(true);
        ClientRegistration social = registration(SocialLoginClientRegistrations.GOOGLE, sentinelUrl);
        assertThrows(RuntimeException.class, () -> SecurityConfig
                .oidcTokenResponseClient(fixtures.http, fixtures.social).getTokenResponse(grant(social)));
        assertThrows(RuntimeException.class, () -> SecurityConfig
                .oauth2UserService(fixtures.http, fixtures.social)
                .loadUser(new OAuth2UserRequest(social, accessToken())));
        var decoder = new SecurityConfig().idTokenDecoderFactory(fixtures.http, fixtures.social)
                .createDecoder(social);
        assertThrows(RuntimeException.class, () -> decoder.decode(signedToken()));
        assertEquals(0, sentinelRequests.get());
    }

    @Test
    void guardedJwksDecoderIsCachedPerRegistrationIdentity() throws Exception {
        fixtures.properties.setAllowPrivateIssuerHosts(true);
        var key = new com.nimbusds.jose.jwk.gen.RSAKeyGenerator(2048).keyID("test-key").generate();
        sentinel.removeContext("/");
        sentinel.createContext("/", exchange -> {
            sentinelRequests.incrementAndGet();
            byte[] bytes = new com.nimbusds.jose.jwk.JWKSet(key.toPublicJWK()).toString()
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        var factory = new SecurityConfig().idTokenDecoderFactory(fixtures.http, fixtures.social);
        ClientRegistration registration = registration("org-17", sentinelUrl);
        assertEquals("test-subject", factory.createDecoder(registration).decode(signedToken(key)).getSubject());
        assertEquals("test-subject", factory.createDecoder(registration).decode(signedToken(key)).getSubject());
        assertEquals(1, sentinelRequests.get());
        ClientRegistration changedIssuer = ClientRegistration.withClientRegistration(registration)
                .issuerUri(ISSUER + "/new-issuer").build();
        assertNotSame(factory.createDecoder(registration), factory.createDecoder(changedIssuer));
        assertThrows(org.springframework.security.oauth2.jwt.JwtValidationException.class,
                () -> factory.createDecoder(changedIssuer).decode(signedToken(key)));
        assertEquals(2, sentinelRequests.get());
    }

    @Test
    void decoderCacheIncludesScopesAsAnOrderIndependentSet() {
        var factory = new SecurityConfig().idTokenDecoderFactory(fixtures.http, fixtures.social);
        ClientRegistration initial = registration("org-17", ISSUER + "/jwks");
        ClientRegistration expanded = ClientRegistration.withClientRegistration(initial)
                .scope("openid", "profile", "email").build();
        ClientRegistration reordered = ClientRegistration.withClientRegistration(initial)
                .scope("email", "openid", "profile").build();
        assertNotSame(factory.createDecoder(initial), factory.createDecoder(expanded));
        assertSame(factory.createDecoder(expanded), factory.createDecoder(reordered));
    }

    private void acceptProviderAuthentication() {
        AuthenticationManager acceptedProvider = authentication -> {
            if (!(authentication instanceof OAuth2LoginAuthenticationToken login)) {
                throw new IllegalArgumentException("Unexpected authentication type");
            }
            var authorities = List.of(new SimpleGrantedAuthority("OIDC_USER"));
            var user = new DefaultOAuth2User(authorities, Map.of("sub", "test-subject"), "sub");
            return new OAuth2LoginAuthenticationToken(login.getClientRegistration(), login.getAuthorizationExchange(),
                    user, authorities, accessToken());
        };
        OAuth2LoginAuthenticationFilter callbackFilter = fixtures.callbackFilter;
        assertNotNull(callbackFilter);
        assertNotNull(fixtures.providerManager);
        callbackFilter.setAuthenticationManager(acceptedProvider);
    }

    private org.springframework.test.web.servlet.ResultActions callback(String id, MockHttpSession session) throws Exception {
        return mvc.perform(get("/api/login/oauth2/code/" + id).session(session).param("code", "test-code").param("state", "test-state"));
    }

    private static MockHttpSession pending(String id) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession session = new MockHttpSession();
        request.setSession(session);
        new HttpSessionOAuth2AuthorizationRequestRepository().saveAuthorizationRequest(
                authorization(id), request, new MockHttpServletResponse());
        return session;
    }

    private static OAuth2AuthorizationRequest authorization(String id) {
        return OAuth2AuthorizationRequest.authorizationCode().authorizationUri(ISSUER + "/authorize")
                .clientId("test-client").redirectUri("http://localhost/api/login/oauth2/code/" + id)
                .state("test-state").scope("openid").attributes(Map.of("registration_id", id)).build();
    }

    private static ClientRegistration registration(String registrationId, String endpoint) {
        return ClientRegistration.withRegistrationId(registrationId).clientId("test-client").clientSecret("test-secret")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("http://localhost/api/login/oauth2/code/org-17").scope("openid")
                .authorizationUri(ISSUER + "/authorize").issuerUri(ISSUER)
                .tokenUri(endpoint).jwkSetUri(endpoint).userInfoUri(endpoint).userNameAttributeName("sub").build();
    }

    private static OAuth2AuthorizationCodeGrantRequest grant(ClientRegistration registration) {
        return new OAuth2AuthorizationCodeGrantRequest(registration, new OAuth2AuthorizationExchange(
                authorization("org-17"), OAuth2AuthorizationResponse.success("test-code").state("test-state")
                        .redirectUri("http://localhost/api/login/oauth2/code/org-17").build()));
    }

    private static OAuth2AccessToken accessToken() {
        return new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "test-access", Instant.now(), Instant.now().plusSeconds(60));
    }

    private static String signedToken() {
        try {
            var key = new com.nimbusds.jose.jwk.gen.RSAKeyGenerator(2048).keyID("test-key").generate();
            return signedToken(key);
        } catch (com.nimbusds.jose.JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String signedToken(com.nimbusds.jose.jwk.RSAKey key) throws com.nimbusds.jose.JOSEException {
        return signedToken(key, "test-client", null);
    }

    private static String signedToken(com.nimbusds.jose.jwk.RSAKey key, String audience, String nonce)
            throws com.nimbusds.jose.JOSEException {
        var claims = new com.nimbusds.jwt.JWTClaimsSet.Builder().subject("test-subject").issuer(ISSUER)
                .audience(audience).issueTime(java.util.Date.from(Instant.now()))
                .expirationTime(java.util.Date.from(Instant.now().plusSeconds(60)));
        if (nonce != null) {
            claims.claim("nonce", nonce);
        }
        var jwt = new com.nimbusds.jwt.SignedJWT(new com.nimbusds.jose.JWSHeader.Builder(
                com.nimbusds.jose.JWSAlgorithm.RS256).keyID("test-key").build(),
                claims.build());
        jwt.sign(new com.nimbusds.jose.crypto.RSASSASigner(key));
        return jwt.serialize();
    }

    private static Map<String, Object> metadata() {
        return new HashMap<>(Map.of("issuer", ISSUER, "authorization_endpoint", ISSUER + "/authorize",
                "token_endpoint", ISSUER + "/token", "jwks_uri", ISSUER + "/jwks", "userinfo_endpoint", ISSUER + "/userinfo",
                "response_types_supported", List.of("code"), "subject_types_supported", List.of("public"),
                "id_token_signing_alg_values_supported", List.of("RS256")));
    }

    @Configuration
    @EnableWebSecurity
    static class TestConfig {
        final SsoProperties properties = new SsoProperties();
        final SsoConnectionMapper mapper = mock(SsoConnectionMapper.class);
        final SsoSecretCipher cipher = mock(SsoSecretCipher.class);
        final AtomicBoolean rebound = new AtomicBoolean();
        final SsoHttpClient http = spy(SsoHttpClientTestSupport.rebindingClient(properties, rebound));
        final DbClientRegistrationRepository enterprise = new DbClientRegistrationRepository(mapper, cipher, properties, http);
        final SsoAuthenticationSuccessHandler success = mock(SsoAuthenticationSuccessHandler.class);
        final SocialLoginClientRegistrations social = new SocialLoginClientRegistrations(socialProperties());
        OAuth2LoginAuthenticationFilter callbackFilter;
        AuthenticationManager providerManager;

        @Bean(destroyMethod = "close")
        SsoHttpClient http() {
            return http;
        }

        @Bean
        JwtDecoderFactory<ClientRegistration> decoderFactory() {
            return new SecurityConfig().idTokenDecoderFactory(http, social);
        }

        private static SocialLoginProperties socialProperties() {
            SocialLoginProperties socialProperties = new SocialLoginProperties();
            socialProperties.getGoogle().setEnabled(true);
            socialProperties.getGoogle().setClientId("google-client");
            socialProperties.getGoogle().setClientSecret("google-secret");
            return socialProperties;
        }

        @Bean
        SecurityFilterChain chain(HttpSecurity security) throws Exception {
            CompositeClientRegistrationRepository registrations = new CompositeClientRegistrationRepository(social, enterprise, properties);
            SecurityConfig config = new SecurityConfig();
            SecurityFilterChain chain = config.chain(security, new SessionRegistryImpl(), registrations, social, http,
                    mock(DbRelyingPartyRegistrationRepository.class), success,
                    mock(SessionSecurityService.class), mock(UserMapper.class), mock(WebSocketSessionRegistry.class),
                    new PrivilegedMfaProperties(), mock(PrivilegedAccountService.class), mock(WebAuthnService.class),
                    mock(AuditService.class), mock(BusinessCardRateLimiter.class), mock(CapabilityEntitlement.class),
                    mock(WorkspaceRequestResolver.class), mock(WorkspaceService.class), mock(WorkspaceCookie.class),
                    mock(OneTimeLinkFlowCookie.class), mock(LogoutAuditHandler.class), mock(LoginRateLimiter.class),
                    mock(ClientIpResolver.class), new tools.jackson.databind.ObjectMapper(),
                    config.corsConfigurationSource(new String[] { "http://localhost" }), "", false, new String[] { "http://localhost" });
            providerManager = security.getSharedObject(AuthenticationManager.class);
            for (Filter filter : chain.getFilters()) {
                if (filter instanceof OAuth2LoginAuthenticationFilter callback) {
                    callbackFilter = callback;
                }
            }
            return chain;
        }
    }
}
