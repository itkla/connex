package ooo.klae.connex.backend.sso;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;
import org.slf4j.LoggerFactory;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import ooo.klae.connex.backend.beans.SsoConnection;
import ooo.klae.connex.backend.mappers.SsoConnectionMapper;

class DbClientRegistrationRepositoryTest {
    private static final String ISSUER = "https://93.184.216.34";
    private final SsoConnectionMapper mapper = mock(SsoConnectionMapper.class);
    private final SsoSecretCipher cipher = mock(SsoSecretCipher.class);
    private final SsoProperties properties = new SsoProperties();
    private final AtomicReference<SsoConnection> current = new AtomicReference<>();
    private SsoHttpClient http;
    private DbClientRegistrationRepository repository;

    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
        http = spy(new SsoHttpClient(properties));
        repository = new DbClientRegistrationRepository(mapper, cipher, properties, http);
        current.set(connection(ISSUER, "client-a", "reference-a"));
        when(mapper.findByOrg(17)).thenAnswer(invocation -> current.get());
        when(cipher.decryptOidcClientSecret(17, "reference-a")).thenReturn("secret-a");
        doReturn(metadata(ISSUER)).when(http).metadata(anyInt(), any(URI.class));
    }

    @AfterEach
    void close() {
        http.close();
    }

    @ParameterizedTest
    @ValueSource(strings = { "token_endpoint", "jwks_uri", "userinfo_endpoint", "authorization_endpoint" })
    void rejectsEveryDiscoveredPrivateEndpointBeforeDecrypting(String endpoint) {
        Map<String, Object> metadata = metadata(ISSUER);
        metadata.put(endpoint, "https://127.0.0.1:18080/probe");
        doReturn(metadata).when(http).metadata(anyInt(), any(URI.class));

        assertNull(repository.findByRegistrationId("org-17"));
        verify(cipher, never()).decryptOidcClientSecret(17, "reference-a");
    }

    @ParameterizedTest
    @ValueSource(strings = { "token_endpoint", "jwks_uri", "userinfo_endpoint", "authorization_endpoint" })
    void rejectsCleartextPublicEndpointsBeforeDecryptingUnlessExplicitlyExempted(String endpoint) {
        Map<String, Object> metadata = metadata(ISSUER);
        metadata.put(endpoint, "http://93.184.216.34/probe");
        doReturn(metadata).when(http).metadata(anyInt(), any(URI.class));
        assertNull(repository.findByRegistrationId("org-17"));
        verifyNoInteractions(cipher);
        properties.setAllowPrivateIssuerHosts(true);
        assertNotNull(repository.findByRegistrationId("org-17"));
    }

    @Test
    void rejectsCleartextIssuerBeforeDiscoveryUnlessExplicitlyExempted() {
        String issuer = "http://93.184.216.34";
        current.set(connection(issuer, "client-a", "reference-a"));
        doReturn(metadata(issuer)).when(http).metadata(anyInt(), any(URI.class));
        assertNull(repository.findByRegistrationId("org-17"));
        verify(http, never()).metadata(anyInt(), any(URI.class));
        verifyNoInteractions(cipher);
        properties.setAllowPrivateIssuerHosts(true);
        assertNotNull(repository.findByRegistrationId("org-17"));
    }

    @ParameterizedTest
    @ValueSource(strings = { "TIMEOUT", "INTERRUPTED" })
    void logsTransportFailuresSeparatelyFromPolicyRefusals(String reason) {
        SsoTransportException failure = new SsoTransportException(SsoTransportException.Reason.valueOf(reason),
                new java.io.IOException("sensitive provider detail"));
        doThrow(new UncheckedIOException(failure)).when(http).requireSafeEnterpriseDestinations(anyInt(), any());
        Logger logger = assertInstanceOf(Logger.class, LoggerFactory.getLogger(DbClientRegistrationRepository.class));
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertNull(repository.findByRegistrationId("org-17"));
            List<ILoggingEvent> warnings = appender.list.stream().filter(event -> event.getLevel() == Level.WARN).toList();
            assertEquals(1, warnings.size());
            assertEquals("Enterprise OIDC resolution refused for org 17: transport_"
                    + reason.toLowerCase(java.util.Locale.ROOT) + " [java.io.UncheckedIOException]",
                    warnings.getFirst().getFormattedMessage());
            assertNull(warnings.getFirst().getThrowableProxy());
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void oneOrganizationCannotUseSeveralHostsToExceedItsResolutionCap() throws Exception {
        CountDownLatch occupied = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger discoveryCalls = new AtomicInteger();
        InetAddress publicAddress = InetAddress.getByName("93.184.216.34");
        http.close();
        http = spy(new SsoHttpClient(properties, host -> {
            if (host.startsWith("tenant-host-")) {
                occupied.countDown();
                try {
                    assertTrue(release.await(30, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                throw new java.net.UnknownHostException("Stopped test discovery");
            }
            return new InetAddress[] { publicAddress };
        }, Duration.ofSeconds(30)));
        doAnswer(invocation -> {
            URI uri = invocation.getArgument(1, URI.class);
            if (uri.getHost().equals("93.184.216.34")) {
                int index = discoveryCalls.incrementAndGet();
                http.requireSafeEnterpriseDestinations(17, List.of("https://tenant-host-" + index + ".example"));
            }
            return metadata("https://" + uri.getHost());
        }).when(http).metadata(anyInt(), any(URI.class));
        SsoConnection other = connection("https://93.184.216.35", "other-client", "other-reference");
        other.setOrgId(18);
        when(mapper.findByOrg(18)).thenReturn(other);
        when(cipher.decryptOidcClientSecret(18, "other-reference")).thenReturn("other-secret");
        CloseableHttpClient client = mock(CloseableHttpClient.class);
        when(client.executeOpen(any(), any(), any())).thenAnswer(invocation -> {
            BasicClassicHttpResponse response = new BasicClassicHttpResponse(200);
            response.setEntity(new StringEntity("{\"keys\":[]}", ContentType.APPLICATION_JSON));
            return response;
        });
        doReturn(client).when(http).pinnedClient(anyString(), any(InetAddress[].class));
        repository = new DbClientRegistrationRepository(mapper, cipher, properties, http);
        try (var callers = Executors.newFixedThreadPool(3)) {
            var first = callers.submit(() -> repository.findByRegistrationId("org-17"));
            var second = callers.submit(() -> repository.findByRegistrationId("org-17"));
            try {
                assertTrue(occupied.await(10, TimeUnit.SECONDS));
                assertNull(callers.submit(() -> repository.findByRegistrationId("org-017")).get(5, TimeUnit.SECONDS));
                assertEquals(2, discoveryCalls.get());
                assertNotNull(repository.findByRegistrationId("org-18"));
                assertEquals("{\"keys\":[]}", new RestTemplate(http)
                        .getForObject("https://accounts.google.com/jwks", String.class));
            } finally {
                release.countDown();
            }
            assertNull(first.get(10, TimeUnit.SECONDS));
            assertNull(second.get(10, TimeUnit.SECONDS));
        }
        assertEquals(2, discoveryCalls.get());
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void issuerChangeCannotReplaceOrganizationCapacityHeldByTimedOutDns(boolean discovery) throws Exception {
        CountDownLatch occupied = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger resolutions = new AtomicInteger();
        InetAddress publicAddress = InetAddress.getByName("93.184.216.34");
        properties.setAllowPrivateIssuerHosts(discovery);
        current.set(connection("https://stalled.a.example", "client-a", "reference-a"));
        http.close();
        http = spy(new SsoHttpClient(properties, host -> {
            resolutions.incrementAndGet();
            if (host.equals("stalled.a.example")) {
                occupied.countDown();
                while (true) {
                    try {
                        assertTrue(release.await(30, TimeUnit.SECONDS));
                        break;
                    } catch (InterruptedException e) {
                        Thread.interrupted();
                    }
                }
            }
            return new InetAddress[] { publicAddress };
        }, Duration.ofSeconds(5)));
        CloseableHttpClient client = mock(CloseableHttpClient.class);
        when(client.executeOpen(any(), any(), any())).thenThrow(new AssertionError("Expired discovery reached HTTP"));
        doReturn(client).when(http).pinnedClient(anyString(), any(InetAddress[].class));
        SsoConnection other = connection(ISSUER, "other-client", "other-reference");
        other.setOrgId(18);
        when(mapper.findByOrg(18)).thenReturn(other);
        when(cipher.decryptOidcClientSecret(18, "other-reference")).thenReturn("other-secret");
        doReturn(metadata(ISSUER)).when(http).metadata(eq(18), any(URI.class));
        repository = new DbClientRegistrationRepository(mapper, cipher, properties, http);
        Logger logger = assertInstanceOf(Logger.class, LoggerFactory.getLogger(DbClientRegistrationRepository.class));
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try (var callers = Executors.newFixedThreadPool(2)) {
            var first = callers.submit(() -> repository.findByRegistrationId("org-17"));
            var second = callers.submit(() -> repository.findByRegistrationId("org-017"));
            try {
                assertTrue(occupied.await(10, TimeUnit.SECONDS));
                assertNull(first.get(15, TimeUnit.SECONDS));
                assertNull(second.get(15, TimeUnit.SECONDS));
                assertEquals(2, resolutions.get());
                current.set(connection("https://replacement.a.example", "client-a", "reference-a"));
                repository.evict(17);
                appender.list.clear();

                assertNull(repository.findByRegistrationId("org-0017"));

                assertTrue(appender.list.stream().anyMatch(event -> event.getLevel() == Level.WARN
                        && event.getFormattedMessage().contains("OIDC transport saturated")));
                assertEquals(2, resolutions.get());
                assertNotNull(repository.findByRegistrationId("org-18"));
                var failure = assertThrows(org.springframework.web.client.RestClientException.class,
                        () -> new RestTemplate(http.forEnterpriseRegistration("org-00017"))
                                .getForObject("https://replacement-jwks.a.example/jwks", String.class));
                assertInstanceOf(SsoTransportSaturatedException.class, failure.getCause());
                verify(cipher, never()).decryptOidcClientSecret(17, "reference-a");
            } finally {
                release.countDown();
            }
            completedTasks(assertInstanceOf(ThreadPoolExecutor.class,
                    ReflectionTestUtils.getField(http, discovery ? "egressWorkers" : "resolutionWorkers")));
            verify(client, never()).executeOpen(any(), any(), any());
            doReturn(metadata("https://replacement.a.example")).when(http).metadata(eq(17), any(URI.class));
            assertNotNull(repository.findByRegistrationId("org-17"));
        } finally {
            release.countDown();
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @ParameterizedTest
    @MethodSource("ooo.klae.connex.backend.sso.SsoHttpClientTestSupport#transitionHosts")
    void rejectsTransitionIssuerBeforeDiscovery(String host) {
        current.set(connection("https://[" + host + "]:18080", "client-a", "reference-a"));
        assertNull(repository.findByRegistrationId("org-17"));
        verify(http, never()).metadata(anyInt(), any(URI.class));
        verifyNoInteractions(cipher);
    }

    @ParameterizedTest
    @MethodSource("ooo.klae.connex.backend.sso.SsoHttpClientTestSupport#transitionHosts")
    void rejectsEveryDiscoveredTransitionDestination(String host) {
        for (String endpoint : List.of("token_endpoint", "jwks_uri", "userinfo_endpoint", "authorization_endpoint")) {
            repository.evict(17);
            Map<String, Object> metadata = metadata(ISSUER);
            metadata.put(endpoint, "https://[" + host + "]:18080/probe");
            doReturn(metadata).when(http).metadata(anyInt(), any(URI.class));
            assertNull(repository.findByRegistrationId("org-17"), endpoint);
        }
        verifyNoInteractions(cipher);
    }

    @Test
    void oneSlowProviderDoesNotFailAnUnrelatedResolution() throws Exception {
        CountDownLatch occupied = new CountDownLatch(SsoHttpClient.SLOTS_PER_DESTINATION);
        CountDownLatch release = new CountDownLatch(1);
        InetAddress publicAddress = InetAddress.getByName("93.184.216.34");
        http.close();
        http = spy(new SsoHttpClient(properties, host -> {
            if (host.equals("busy.example")) {
                occupied.countDown();
                try {
                    assertTrue(release.await(60, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new java.net.UnknownHostException("Interrupted test resolver");
                }
            }
            return new InetAddress[] { publicAddress };
        }, Duration.ofSeconds(10)));
        doReturn(metadata(ISSUER)).when(http).metadata(anyInt(), any(URI.class));
        repository = new DbClientRegistrationRepository(mapper, cipher, properties, http);
        assertNotNull(repository.findByRegistrationId("org-17"));
        int callers = SsoHttpClient.SLOTS_PER_DESTINATION * 4;
        try (var pool = Executors.newFixedThreadPool(callers)) {
            List<Future<?>> stalled = new ArrayList<>();
            for (int index = 0; index < callers; index++) {
                int orgId = 100 + index;
                stalled.add(pool.submit(() ->
                        http.requireSafeEnterpriseDestinations(orgId, List.of("https://busy.example"))));
            }
            try {
                assertTrue(occupied.await(30, TimeUnit.SECONDS));
                assertNotNull(repository.findByRegistrationId("org-17"));
                assertNotNull(repository.findByRegistrationId("org-17"));
            } finally {
                release.countDown();
            }
            for (Future<?> caller : stalled) {
                try {
                    caller.get(60, TimeUnit.SECONDS);
                } catch (ExecutionException expected) {
                    assertNotNull(expected.getCause());
                }
            }
        }
        verify(http, times(1)).metadata(anyInt(), any(URI.class));
    }

    @Test
    void warmResolutionCostsOneTransportTask() {
        ThreadPoolExecutor resolutionWorkers = assertInstanceOf(ThreadPoolExecutor.class,
                ReflectionTestUtils.getField(http, "resolutionWorkers"));
        assertNotNull(repository.findByRegistrationId("org-17"));
        long cold = completedTasks(resolutionWorkers);
        assertEquals(2, cold);
        assertNotNull(repository.findByRegistrationId("org-17"));
        assertEquals(cold + 1, completedTasks(resolutionWorkers));
        verify(http, times(1)).metadata(anyInt(), any(URI.class));
    }

    private static long completedTasks(ThreadPoolExecutor pool) {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            while (pool.getActiveCount() != 0 || !pool.getQueue().isEmpty()) {
                Thread.sleep(1);
            }
        });
        return pool.getCompletedTaskCount();
    }

    @Test
    void killSwitchRejectsStoredEnabledConnectionBeforeDiscovery() {
        properties.setEnabled(false);
        assertNull(repository.findByRegistrationId("org-17"));
        verifyNoInteractions(mapper, http);
    }

    @Test
    void cachedTemplateCannotSurviveAnUnobservedIssuerChange() {
        assertNotNull(repository.findByRegistrationId("org-17"));
        String nextIssuer = "https://93.184.216.35";
        current.set(connection(nextIssuer, "client-b", "reference-b"));
        doReturn(metadata(nextIssuer)).when(http).metadata(anyInt(), any(URI.class));
        when(cipher.decryptOidcClientSecret(17, "reference-b")).thenReturn("secret-b");

        var registration = repository.findByRegistrationId("org-17");
        assertNotNull(registration);
        assertEquals(nextIssuer + "/token", registration.getProviderDetails().getTokenUri());
        assertEquals("client-b", registration.getClientId());
        assertEquals("secret-b", registration.getClientSecret());
    }

    @Test
    void lookupPausedAtDiscoveryFailsClosedAcrossIssuerTransition() throws Exception {
        CountDownLatch discoveryEntered = new CountDownLatch(1);
        CountDownLatch releaseDiscovery = new CountDownLatch(1);
        doAnswer(invocation -> {
            discoveryEntered.countDown();
            assertTrue(releaseDiscovery.await(10, TimeUnit.SECONDS));
            return metadata(ISSUER);
        }).when(http).metadata(anyInt(), any(URI.class));
        try (var executor = Executors.newSingleThreadExecutor()) {
            var lookup = executor.submit(() -> repository.findByRegistrationId("org-17"));
            try {
                assertTrue(discoveryEntered.await(10, TimeUnit.SECONDS));
                current.set(connection("https://93.184.216.35", "client-b", "reference-b"));
                repository.evict(17);
            } finally {
                releaseDiscovery.countDown();
            }
            assertNull(lookup.get(10, TimeUnit.SECONDS));
        }
        verify(cipher, never()).decryptOidcClientSecret(17, "reference-a");
    }

    @Test
    void cachedSecretDecryptionFailureReturnsUnknownRegistration() {
        assertNotNull(repository.findByRegistrationId("org-17"));
        when(cipher.decryptOidcClientSecret(17, "reference-a")).thenThrow(new IllegalStateException("retired"));
        assertNull(repository.findByRegistrationId("org-17"));
    }

    @Test
    void missingDecryptedSecretReturnsUnknownRegistration() {
        when(cipher.decryptOidcClientSecret(17, "reference-a")).thenReturn(null);
        assertNull(repository.findByRegistrationId("org-17"));
    }

    @Test
    void identityChangeDuringDecryptionDiscardsTheRegistration() {
        when(cipher.decryptOidcClientSecret(17, "reference-a")).thenAnswer(invocation -> {
            current.set(connection("https://93.184.216.35", "client-b", "reference-b"));
            return "secret-a";
        });
        assertNull(repository.findByRegistrationId("org-17"));
    }

    @Test
    void optionalUserinfoCanBeAbsentButIssuerMustMatchExactly() {
        Map<String, Object> metadata = metadata(ISSUER);
        metadata.remove("userinfo_endpoint");
        doReturn(metadata).when(http).metadata(anyInt(), any(URI.class));
        assertNotNull(repository.findByRegistrationId("org-17"));
        repository.evict(17);
        metadata.put("issuer", "https://93.184.216.35");
        assertNull(repository.findByRegistrationId("org-17"));
    }

    @Test
    void secondDiscoveryPathAcceptsRfc8414MetadataWithoutOidcOnlyFields() {
        current.set(connection(ISSUER + "/tenant", "client-a", "reference-a"));
        Map<String, Object> metadata = metadata(ISSUER + "/tenant");
        metadata.remove("subject_types_supported");
        metadata.remove("id_token_signing_alg_values_supported");
        doReturn(metadata).when(http).metadata(anyInt(), any(URI.class));
        doThrow(new org.springframework.web.client.HttpClientErrorException(org.springframework.http.HttpStatus.NOT_FOUND))
                .when(http).metadata(17, URI.create(ISSUER + "/tenant/.well-known/openid-configuration"));

        var registration = repository.findByRegistrationId("org-17");

        assertNotNull(registration);
        assertEquals(ISSUER + "/tenant", registration.getProviderDetails().getIssuerUri());
        assertEquals(ISSUER + "/tenant/token", registration.getProviderDetails().getTokenUri());
        verify(http).metadata(17, URI.create(ISSUER + "/.well-known/openid-configuration/tenant"));
        verify(http, never()).metadata(17, URI.create(ISSUER + "/.well-known/oauth-authorization-server/tenant"));
    }

    @Test
    void rfc8414FallbackHandlesMetadataWithoutOidcOnlyFields() {
        current.set(connection(ISSUER + "/tenant", "client-a", "reference-a"));
        Map<String, Object> metadata = metadata(ISSUER + "/tenant");
        metadata.remove("subject_types_supported");
        metadata.remove("id_token_signing_alg_values_supported");
        doReturn(metadata).when(http).metadata(anyInt(), any(URI.class));
        doThrow(new org.springframework.web.client.HttpClientErrorException(org.springframework.http.HttpStatus.NOT_FOUND))
                .when(http).metadata(17, URI.create(ISSUER + "/tenant/.well-known/openid-configuration"));
        doThrow(new org.springframework.web.client.HttpClientErrorException(org.springframework.http.HttpStatus.NOT_FOUND))
                .when(http).metadata(17, URI.create(ISSUER + "/.well-known/openid-configuration/tenant"));
        var registration = repository.findByRegistrationId("org-17");
        assertNotNull(registration);
        assertEquals(ISSUER + "/tenant/token", registration.getProviderDetails().getTokenUri());
        verify(http).metadata(17, URI.create(ISSUER + "/.well-known/oauth-authorization-server/tenant"));
    }

    @ParameterizedTest
    @CsvSource({"/,0", "/,1", "/,2", "/tenant/,0", "/tenant/,1", "/tenant/,2"})
    void trailingIssuerSlashesAreRemovedOnlyFromDiscoveryPaths(String issuerPath, int successfulPath) {
        String issuer = "https://idp.example.com" + issuerPath;
        current.set(connection(issuer, "client-a", "reference-a"));
        properties.setAllowPrivateIssuerHosts(true);
        String normalizedPath = issuerPath.substring(0, issuerPath.length() - 1);
        List<String> expected = List.of(normalizedPath + "/.well-known/openid-configuration",
                "/.well-known/openid-configuration" + normalizedPath,
                "/.well-known/oauth-authorization-server" + normalizedPath);
        AtomicInteger attempts = new AtomicInteger();
        doAnswer(invocation -> {
            URI uri = invocation.getArgument(1, URI.class);
            int index = attempts.getAndIncrement();
            assertEquals("https://idp.example.com" + expected.get(index), uri.toString());
            if (index < successfulPath) {
                throw new org.springframework.web.client.HttpClientErrorException(
                        org.springframework.http.HttpStatus.NOT_FOUND);
            }
            return metadata(issuer);
        }).when(http).metadata(anyInt(), any(URI.class));

        var registration = repository.findByRegistrationId("org-17");

        assertNotNull(registration);
        assertEquals(issuer, registration.getProviderDetails().getIssuerUri());
        assertEquals(successfulPath + 1, attempts.get());
        repository.evict(17);
        doReturn(metadata("https://idp.example.com" + normalizedPath))
                .when(http).metadata(anyInt(), any(URI.class));
        assertNull(repository.findByRegistrationId("org-17"));
    }

    @ParameterizedTest
    @ValueSource(strings = { "dns", "404", "503", "issuer", "decrypt" })
    void providerFailuresAreCachedAcrossRegistrationAliasesUntilDiscoveryTtl(String reason) throws Exception {
        Clock clock = mock(Clock.class);
        Instant start = Instant.parse("2026-09-15T00:00:00Z");
        when(clock.instant()).thenReturn(start);
        AtomicInteger resolutions = new AtomicInteger();
        http.close();
        http = spy(new SsoHttpClient(properties, host -> {
            resolutions.incrementAndGet();
            if ("dns".equals(reason)) {
                throw new UnknownHostException("unavailable");
            }
            return new InetAddress[] { InetAddress.getByName("93.184.216.34") };
        }, Duration.ofSeconds(10)));
        doAnswer(invocation -> {
            return switch (reason) {
                case "404" -> throw new org.springframework.web.client.HttpClientErrorException(
                        org.springframework.http.HttpStatus.NOT_FOUND);
                case "503" -> throw new org.springframework.web.client.HttpServerErrorException(
                        org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE);
                case "issuer" -> metadata(ISSUER + "/wrong");
                default -> metadata(ISSUER);
            };
        }).when(http).metadata(anyInt(), any(URI.class));
        if ("decrypt".equals(reason)) {
            when(cipher.decryptOidcClientSecret(17, "reference-a")).thenThrow(new IllegalStateException("retired"));
        }
        repository = new DbClientRegistrationRepository(mapper, cipher, properties, http, clock);

        assertNull(repository.findByRegistrationId("org-17"));
        int initialResolutions = resolutions.get();
        assertEquals("decrypt".equals(reason) ? 6 : 1, initialResolutions);
        when(clock.instant()).thenReturn(start.plus(Duration.ofMinutes(10)).minusMillis(1));
        assertNull(repository.findByRegistrationId("org-17"));
        assertNull(repository.findByRegistrationId("org-017"));
        assertEquals(initialResolutions, resolutions.get());
        when(clock.instant()).thenReturn(start.plus(Duration.ofMinutes(10)));
        assertNull(repository.findByRegistrationId("org-17"));
        assertTrue(resolutions.get() > initialResolutions);
    }

    @ParameterizedTest
    @ValueSource(strings = { "saturated", "TIMEOUT", "INTERRUPTED" })
    void sharedCapacityFailuresAreRetriedWithoutNegativeCaching(String reason) {
        java.io.IOException failure = "saturated".equals(reason)
                ? new SsoTransportSaturatedException("saturated")
                : new SsoTransportException(SsoTransportException.Reason.valueOf(reason), null);
        doThrow(new UncheckedIOException(failure)).doCallRealMethod()
                .when(http).requireSafeEnterpriseDestinations(anyInt(), any());

        assertNull(repository.findByRegistrationId("org-17"));
        assertNotNull(repository.findByRegistrationId("org-17"));
    }

    @ParameterizedTest
    @ValueSource(strings = { "issuer", "client", "secret", "scopes", "evict" })
    void providerFailureCacheIsInvalidatedByRegistrationIdentityChanges(String field) {
        doThrow(new org.springframework.web.client.HttpServerErrorException(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE))
                .when(http).metadata(anyInt(), any(URI.class));
        assertNull(repository.findByRegistrationId("org-17"));
        assertNull(repository.findByRegistrationId("org-17"));
        verify(http).metadata(anyInt(), any(URI.class));
        SsoConnection replacement = connection(ISSUER, "client-a", "reference-a");
        switch (field) {
            case "issuer" -> replacement.setOidcIssuer(ISSUER + "/replacement");
            case "client" -> replacement.setOidcClientId("client-b");
            case "secret" -> {
                replacement.setOidcClientSecretEnc("reference-b");
                when(cipher.decryptOidcClientSecret(17, "reference-b")).thenReturn("secret-b");
            }
            case "scopes" -> replacement.setOidcScopes("openid,email");
            case "evict" -> repository.evict(17);
            default -> throw new IllegalArgumentException(field);
        }
        current.set(replacement);
        doReturn(metadata(replacement.getOidcIssuer())).when(http).metadata(anyInt(), any(URI.class));

        assertNotNull(repository.findByRegistrationId("org-17"));
        verify(http, times(2)).metadata(anyInt(), any(URI.class));
    }

    private static SsoConnection connection(String issuer, String clientId, String reference) {
        SsoConnection connection = new SsoConnection();
        connection.setOrgId(17);
        connection.setEnabled(true);
        connection.setProtocol("oidc");
        connection.setOidcIssuer(issuer);
        connection.setOidcClientId(clientId);
        connection.setOidcClientSecretEnc(reference);
        return connection;
    }

    private static Map<String, Object> metadata(String issuer) {
        return new HashMap<>(Map.of(
                "issuer", issuer,
                "authorization_endpoint", issuer + "/authorize",
                "token_endpoint", issuer + "/token",
                "jwks_uri", issuer + "/jwks",
                "userinfo_endpoint", issuer + "/userinfo",
                "response_types_supported", List.of("code"),
                "subject_types_supported", List.of("public"),
                "id_token_signing_alg_values_supported", List.of("RS256")));
    }
}
