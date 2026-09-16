package ooo.klae.connex.backend.sso;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

class SsoHttpClientTest {
    private final AtomicInteger requests = new AtomicInteger();
    private final SsoProperties properties = new SsoProperties();
    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void startSentinel() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/probe", exchange -> {
            requests.incrementAndGet();
            byte[] body = "ok".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopSentinel() {
        server.stop(0);
    }

    @Test
    void publicAdmissionCannotRebindToLoopbackAtRequestTime() throws Exception {
        AtomicInteger resolutions = new AtomicInteger();
        InetAddress publicAddress = InetAddress.getByName("93.184.216.34");
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        try (SsoHttpClient http = spy(new SsoHttpClient(properties,
                host -> new InetAddress[] { resolutions.incrementAndGet() == 1 ? publicAddress : loopback },
                Duration.ofSeconds(10)))) {
            String url = "https://rebind.example:" + server.getAddress().getPort() + "/probe";
            http.requireSafeEnterpriseDestinations(17, List.of(url));
            assertThrows(RestClientException.class,
                    () -> new RestTemplate(http.forEnterpriseRegistration("org-17")).postForObject(url, "code", String.class));
            SsoHttpClientTestSupport.verifyNoHttpClientCreated(http);
        }
        assertEquals(2, resolutions.get());
        assertEquals(0, requests.get());
    }

    @ParameterizedTest
    @MethodSource("ooo.klae.connex.backend.sso.SsoHttpClientTestSupport#transitionHosts")
    void refusesTransitionDnsAnswersBeforeEgress(String host) throws Exception {
        InetAddress address = SsoHttpClientTestSupport.transitionAddress(host);
        try (SsoHttpClient http = new SsoHttpClient(properties, ignored -> new InetAddress[] { address },
                Duration.ofSeconds(10))) {
            RestClientException failure = assertThrows(RestClientException.class,
                    () -> new RestTemplate(http).getForObject(baseUrl.replace("http:", "https:") + "/probe", String.class));
            Throwable cause = failure;
            while (cause.getCause() != null) {
                cause = cause.getCause();
            }
            assertInstanceOf(java.net.UnknownHostException.class, cause);
            assertEquals("OIDC destination is not permitted", cause.getMessage());
        }
        assertEquals(0, requests.get());
    }

    @Test
    void pinsTheValidatedAddressWithoutResolvingAgainAndPreservesHost() throws Exception {
        properties.setAllowPrivateIssuerHosts(true);
        AtomicInteger resolutions = new AtomicInteger();
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        server.createContext("/host", exchange -> {
            byte[] body = exchange.getRequestHeaders().getFirst("Host")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        try (SsoHttpClient http = new SsoHttpClient(properties, host -> {
            assertEquals("pinned.example", host);
            assertEquals(1, resolutions.incrementAndGet());
            return new InetAddress[] { loopback };
        }, Duration.ofSeconds(10))) {
            String authority = "pinned.example:" + server.getAddress().getPort();
            assertEquals(authority, new RestTemplate(http.forEnterpriseRegistration("org-17"))
                    .getForObject("http://" + authority + "/host", String.class));
        }
        assertEquals(1, resolutions.get());
    }

    @Test
    void pinnedTransportAcceptsPunycodeHostnames() throws Exception {
        properties.setAllowPrivateIssuerHosts(true);
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        try (SsoHttpClient http = new SsoHttpClient(properties, host -> {
            assertEquals("xn--bcher-kva.example", host);
            return new InetAddress[] { loopback };
        }, Duration.ofSeconds(10))) {
            assertEquals("ok", new RestTemplate(http.forEnterpriseRegistration("org-17")).getForObject(
                    "http://xn--bcher-kva.example:" + server.getAddress().getPort() + "/probe", String.class));
        }
        assertEquals(1, requests.get());
    }

    @Test
    void refusesRedirectsWithoutContactingTheirTarget() {
        properties.setAllowPrivateIssuerHosts(true);
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", baseUrl + "/probe");
            exchange.sendResponseHeaders(307, -1);
            exchange.close();
        });
        try (SsoHttpClient http = new SsoHttpClient(properties)) {
            assertThrows(RestClientException.class,
                    () -> new RestTemplate(http.forEnterpriseRegistration("org-17")).postForObject(baseUrl + "/redirect", "code", String.class));
        }
        assertEquals(0, requests.get());
    }

    @Test
    void boundsResponseBytes() {
        properties.setAllowPrivateIssuerHosts(true);
        server.createContext("/large", exchange -> {
            exchange.sendResponseHeaders(200, SsoHttpClient.MAX_RESPONSE_BYTES + 1);
            exchange.getResponseBody().write(new byte[SsoHttpClient.MAX_RESPONSE_BYTES + 1]);
            exchange.close();
        });
        try (SsoHttpClient http = new SsoHttpClient(properties)) {
            assertThrows(RestClientException.class, () -> new RestTemplate(http.forEnterpriseRegistration("org-17"))
                    .getForObject(baseUrl + "/large", String.class));
        }
    }

    @Test
    void deadlineIncludesBlockedDnsAndPreventsLateEgress() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        properties.setAllowPrivateIssuerHosts(true);
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        try (SsoHttpClient http = new SsoHttpClient(properties, host -> {
            entered.countDown();
            try {
                while (true) {
                    try {
                        assertTrue(release.await(30, TimeUnit.SECONDS));
                        return new InetAddress[] { loopback };
                    } catch (InterruptedException e) {
                        Thread.interrupted();
                    }
                }
            } finally {
                finished.countDown();
            }
        }, Duration.ofSeconds(5))) {
            assertTimeoutPreemptively(Duration.ofSeconds(15), () -> assertThrows(RestClientException.class,
                    () -> new RestTemplate(http.forEnterpriseRegistration("org-17")).getForObject(baseUrl + "/probe", String.class)));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            release.countDown();
            assertTrue(finished.await(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
        assertEquals(0, requests.get());
    }

    @Test
    void timedOutDnsIsInterruptedAndReturnsItsWorkerAndPermitPromptly() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger resolutions = new AtomicInteger();
        InetAddress publicAddress = InetAddress.getByName("93.184.216.34");
        try (SsoHttpClient http = new SsoHttpClient(properties, host -> {
            if (resolutions.incrementAndGet() == 1) {
                entered.countDown();
                try {
                    assertTrue(release.await(30, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    interrupted.countDown();
                    Thread.currentThread().interrupt();
                    throw new java.net.UnknownHostException("Interrupted test resolver");
                }
            }
            return new InetAddress[] { publicAddress };
        }, Duration.ofSeconds(5))) {
            try {
                UncheckedIOException failure = assertThrows(UncheckedIOException.class,
                        () -> http.requireSafeEnterpriseDestinations(17, List.of("https://idp.example")));
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertEquals(SsoTransportException.Reason.TIMEOUT,
                        assertInstanceOf(SsoTransportException.class, failure.getCause()).reason());
                assertTrue(interrupted.await(5, TimeUnit.SECONDS));
                assertTransportIdle(http, "resolutionWorkers");
                http.requireSafeEnterpriseDestinations(17, List.of("https://idp.example"));
                assertEquals(2, resolutions.get());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void timedOutHttpCancelsTheActualRequestAndReturnsItsPermit() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch aborted = new CountDownLatch(1);
        AtomicReference<HttpUriRequestBase> outbound = new AtomicReference<>();
        CloseableHttpClient client = mock(CloseableHttpClient.class);
        when(client.executeOpen(any(), any(), any())).thenAnswer(invocation -> {
            HttpUriRequestBase request = assertInstanceOf(HttpUriRequestBase.class, invocation.getArgument(1));
            outbound.set(request);
            request.setDependency(() -> {
                aborted.countDown();
                return true;
            });
            entered.countDown();
            assertTrue(aborted.await(30, TimeUnit.SECONDS));
            throw new IOException("Cancelled test request");
        });
        InetAddress publicAddress = InetAddress.getByName("93.184.216.34");
        try (SsoHttpClient http = spy(new SsoHttpClient(properties,
                host -> new InetAddress[] { publicAddress }, Duration.ofSeconds(5)))) {
            doReturn(client).when(http).pinnedClient(anyString(), any(InetAddress[].class));
            assertThrows(RestClientException.class, () -> new RestTemplate(http.forEnterpriseRegistration("org-17"))
                    .getForObject("https://idp.example/jwks", String.class));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTrue(aborted.await(5, TimeUnit.SECONDS));
            assertTrue(assertInstanceOf(HttpUriRequestBase.class, outbound.get()).isCancelled());
            assertTransportIdle(http, "egressWorkers");
        } finally {
            aborted.countDown();
        }
    }

    @Test
    void timedOutQueuedRequestReturnsItsPermitWithoutRunningDns() throws Exception {
        CountDownLatch occupied = new CountDownLatch(SsoHttpClient.WORKERS);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger resolutions = new AtomicInteger();
        try (SsoHttpClient http = new SsoHttpClient(properties, host -> {
            resolutions.incrementAndGet();
            throw new AssertionError("Expired queued request reached DNS");
        }, Duration.ofSeconds(5))) {
            ThreadPoolExecutor workers = assertInstanceOf(ThreadPoolExecutor.class,
                    ReflectionTestUtils.getField(http, "resolutionWorkers"));
            for (int index = 0; index < SsoHttpClient.WORKERS; index++) {
                workers.execute(() -> {
                    occupied.countDown();
                    try {
                        assertTrue(release.await(30, TimeUnit.SECONDS));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            try {
                assertTrue(occupied.await(10, TimeUnit.SECONDS));
                UncheckedIOException failure = assertThrows(UncheckedIOException.class,
                        () -> http.requireSafeEnterpriseDestinations(17, List.of("https://idp.example")));
                assertEquals(SsoTransportException.Reason.TIMEOUT,
                        assertInstanceOf(SsoTransportException.class, failure.getCause()).reason());
                assertTrue(workers.getQueue().isEmpty());
                assertTrue(destinationEntries(http).isEmpty());
                assertTrue(organizationEntries(http).isEmpty());
                assertEquals(0, resolutions.get());
            } finally {
                release.countDown();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "organizationSlots", "destinationSlots" })
    void dnsCompletionReturnsLeasesBeforeTwoOrganizationLoginsAdvance(String pausedLimiter) throws Exception {
        CountDownLatch firstDnsEntered = new CountDownLatch(1);
        CountDownLatch secondDnsEntered = new CountDownLatch(1);
        CountDownLatch finishFirstDns = new CountDownLatch(1);
        CountDownLatch finishSecondDns = new CountDownLatch(1);
        CountDownLatch firstDiscoveryEntered = new CountDownLatch(1);
        CountDownLatch bothDiscoveriesEntered = new CountDownLatch(2);
        CountDownLatch finishDiscovery = new CountDownLatch(1);
        AtomicInteger resolutions = new AtomicInteger();
        AtomicReference<Future<?>> firstDnsTask = new AtomicReference<>();
        TrackedPermits organization = new TrackedPermits(2);
        TrackedPermits destination = new TrackedPermits(SsoHttpClient.SLOTS_PER_DESTINATION);
        TrackedPermits paused = pausedLimiter.equals("organizationSlots") ? organization : destination;
        paused.pauseNextRelease.set(true);
        InetAddress publicAddress = InetAddress.getByName("93.184.216.34");
        URI discovery = URI.create("https://idp.example/.well-known/openid-configuration");
        CloseableHttpClient client = mock(CloseableHttpClient.class);
        when(client.executeOpen(any(), any(), any())).thenAnswer(invocation -> {
            firstDiscoveryEntered.countDown();
            bothDiscoveriesEntered.countDown();
            assertTrue(finishDiscovery.await(30, TimeUnit.SECONDS));
            BasicClassicHttpResponse response = new BasicClassicHttpResponse(200);
            response.setHeader("Content-Type", "application/json");
            response.setEntity(new StringEntity("{\"issuer\":\"https://idp.example\"}", ContentType.APPLICATION_JSON));
            return response;
        });
        try (SsoHttpClient http = spy(new SsoHttpClient(properties, host -> {
            int call = resolutions.incrementAndGet();
            if (call <= 2) {
                (call == 1 ? firstDnsEntered : secondDnsEntered).countDown();
                try {
                    assertTrue((call == 1 ? finishFirstDns : finishSecondDns).await(30, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new java.net.UnknownHostException("Interrupted test resolver");
                }
            }
            return new InetAddress[] { publicAddress };
        }, Duration.ofSeconds(60)))) {
            ReflectionTestUtils.setField(http, "organizationSlots", new SsoTransportSlots(() -> organization.permits));
            ReflectionTestUtils.setField(http, "destinationSlots", new SsoTransportSlots(() -> destination.permits));
            ThreadPoolExecutor workers = spy(assertInstanceOf(ThreadPoolExecutor.class,
                    ReflectionTestUtils.getField(http, "resolutionWorkers")));
            doAnswer(invocation -> {
                firstDnsTask.compareAndSet(null, assertInstanceOf(Future.class, invocation.getArgument(0)));
                return invocation.callRealMethod();
            }).when(workers).execute(any(Runnable.class));
            ReflectionTestUtils.setField(http, "resolutionWorkers", workers);
            doReturn(client).when(http).pinnedClient(anyString(), any(InetAddress[].class));
            try (var callers = Executors.newFixedThreadPool(2)) {
                try {
                    var firstLogin = callers.submit(() -> {
                        http.requireSafeEnterpriseDestinations(17, List.of("https://idp.example"));
                        return http.metadata(17, discovery);
                    });
                    assertTrue(firstDnsEntered.await(10, TimeUnit.SECONDS));
                    var secondLogin = callers.submit(() -> {
                        http.requireSafeEnterpriseDestinations(17, List.of("https://idp.example"));
                        return http.metadata(17, discovery);
                    });
                    assertTrue(secondDnsEntered.await(10, TimeUnit.SECONDS));
                    finishFirstDns.countDown();
                    assertTrue(paused.releaseEntered.await(10, TimeUnit.SECONDS));
                    assertFalse(assertInstanceOf(Future.class, firstDnsTask.get()).isDone(),
                            "DNS completion must remain unpublished until both leases are returned");
                    assertFalse(firstLogin.isDone());
                    assertEquals(2, paused.active.get());
                    paused.resumeRelease.countDown();
                    assertTrue(firstDiscoveryEntered.await(10, TimeUnit.SECONDS));
                    assertEquals(2, organization.active.get());
                    assertEquals(2, destination.active.get());
                    finishSecondDns.countDown();
                    assertTrue(bothDiscoveriesEntered.await(10, TimeUnit.SECONDS));
                    assertEquals(2, organization.active.get());
                    assertEquals(2, destination.active.get());
                    UncheckedIOException saturated = assertThrows(UncheckedIOException.class,
                            () -> http.requireSafeEnterpriseDestinations(17, List.of("https://idp.example")));
                    assertInstanceOf(SsoTransportSaturatedException.class, saturated.getCause());
                    assertEquals(4, resolutions.get());
                    finishDiscovery.countDown();
                    assertEquals(Map.of("issuer", "https://idp.example"), firstLogin.get(10, TimeUnit.SECONDS));
                    assertEquals(Map.of("issuer", "https://idp.example"), secondLogin.get(10, TimeUnit.SECONDS));
                    assertTransportIdle(http, "resolutionWorkers");
                    assertTransportIdle(http, "egressWorkers");
                    organization.assertBalanced(4, 2);
                    destination.assertBalanced(4, 2);
                } finally {
                    finishFirstDns.countDown();
                    finishSecondDns.countDown();
                    finishDiscovery.countDown();
                    paused.resumeRelease.countDown();
                }
            }
        }
    }

    /** Counts actual semaphore transitions and can pause one release before its permit becomes available. */
    private static final class TrackedPermits {
        private final Semaphore permits;
        private final int capacity;
        private final AtomicInteger acquired = new AtomicInteger();
        private final AtomicInteger released = new AtomicInteger();
        private final AtomicInteger active = new AtomicInteger();
        private final AtomicInteger peak = new AtomicInteger();
        private final AtomicBoolean pauseNextRelease = new AtomicBoolean();
        private final CountDownLatch releaseEntered = new CountDownLatch(1);
        private final CountDownLatch resumeRelease = new CountDownLatch(1);

        private TrackedPermits(int capacity) throws InterruptedException {
            this.capacity = capacity;
            permits = spy(new Semaphore(capacity, true));
            doAnswer(invocation -> {
                boolean admitted = assertInstanceOf(Boolean.class, invocation.callRealMethod());
                if (admitted) {
                    acquired.incrementAndGet();
                    int held = active.incrementAndGet();
                    assertTrue(held <= capacity);
                    peak.accumulateAndGet(held, Math::max);
                }
                return admitted;
            }).when(permits).tryAcquire(anyLong(), eq(TimeUnit.NANOSECONDS));
            doAnswer(invocation -> {
                if (pauseNextRelease.compareAndSet(true, false)) {
                    releaseEntered.countDown();
                    assertTrue(resumeRelease.await(30, TimeUnit.SECONDS));
                }
                assertTrue(active.decrementAndGet() >= 0);
                invocation.callRealMethod();
                released.incrementAndGet();
                assertTrue(permits.availablePermits() <= capacity);
                return null;
            }).when(permits).release();
        }

        private void assertBalanced(int expectedAcquisitions, int expectedPeak) {
            assertEquals(expectedAcquisitions, acquired.get());
            assertEquals(expectedAcquisitions, released.get());
            assertEquals(0, active.get());
            assertEquals(expectedPeak, peak.get());
            assertEquals(capacity, permits.availablePermits());
        }
    }

    private static Map<?, ?> destinationEntries(SsoHttpClient http) {
        SsoTransportSlots limiter = assertInstanceOf(SsoTransportSlots.class,
                ReflectionTestUtils.getField(http, "destinationSlots"));
        return assertInstanceOf(Map.class, ReflectionTestUtils.getField(limiter, "slots"));
    }

    private static Map<?, ?> organizationEntries(SsoHttpClient http) {
        SsoTransportSlots limiter = assertInstanceOf(SsoTransportSlots.class,
                ReflectionTestUtils.getField(http, "organizationSlots"));
        return assertInstanceOf(Map.class, ReflectionTestUtils.getField(limiter, "slots"));
    }

    private static void assertTransportIdle(SsoHttpClient http, String name) {
        ThreadPoolExecutor workers = assertInstanceOf(ThreadPoolExecutor.class,
                ReflectionTestUtils.getField(http, name));
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            while (workers.getActiveCount() != 0) {
                Thread.sleep(1);
            }
        });
        assertTrue(destinationEntries(http).isEmpty());
        assertTrue(organizationEntries(http).isEmpty());
    }

    @Test
    void pinnedTransportAcceptsBracketedIpv6Literals() throws Exception {
        properties.setAllowPrivateIssuerHosts(true);
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        try (SsoHttpClient http = new SsoHttpClient(properties, host -> {
            assertEquals("[2606:2800:220:1:248:1893:25c8:1946]", host);
            return new InetAddress[] { loopback };
        }, Duration.ofSeconds(10))) {
            assertEquals("ok", new RestTemplate(http.forEnterpriseRegistration("org-17")).getForObject(
                    "http://[2606:2800:220:1:248:1893:25c8:1946]:" + server.getAddress().getPort() + "/probe",
                    String.class));
        }
        assertEquals(1, requests.get());
    }

    @Test
    void oneSlowDestinationCannotStarveTheOthers() throws Exception {
        CountDownLatch occupied = new CountDownLatch(SsoHttpClient.SLOTS_PER_DESTINATION);
        CountDownLatch release = new CountDownLatch(1);
        InetAddress publicAddress = InetAddress.getByName("93.184.216.34");
        int callers = SsoHttpClient.SLOTS_PER_DESTINATION * 4;
        try (SsoHttpClient http = new SsoHttpClient(properties, host -> {
            if (host.equals("slow.example")) {
                occupied.countDown();
                try {
                    assertTrue(release.await(60, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new java.net.UnknownHostException("Interrupted test resolver");
                }
            }
            return new InetAddress[] { publicAddress };
        }, Duration.ofSeconds(10))) {
            try (ExecutorService pool = Executors.newFixedThreadPool(callers)) {
                List<Future<?>> stalled = new ArrayList<>();
                for (int index = 0; index < callers; index++) {
                    int orgId = 100 + index;
                    stalled.add(pool.submit(() ->
                            http.requireSafeEnterpriseDestinations(orgId, List.of("https://slow.example"))));
                }
                try {
                    assertTrue(occupied.await(30, TimeUnit.SECONDS));
                    http.requireSafeEnterpriseDestinations(17, List.of("https://other-tenant.example",
                            "https://other-tenant.example/token", "https://other-tenant.example/jwks"));
                    http.requireSafeEnterpriseDestinations(17, List.of("https://accounts.google.example/jwks"));
                } finally {
                    release.countDown();
                }
                for (Future<?> caller : stalled) {
                    caller.get(30, TimeUnit.SECONDS);
                }
            }
        }
    }

    @Test
    void severalEnterpriseHostsCannotStarveFourConcurrentGoogleJwksFetches() throws Exception {
        CountDownLatch occupied = new CountDownLatch(SsoHttpClient.WORKERS);
        CountDownLatch releaseEnterprise = new CountDownLatch(1);
        CountDownLatch socialEntered = new CountDownLatch(SsoHttpClient.SOCIAL_WORKERS);
        AtomicBoolean enterpriseAdmissions = new AtomicBoolean(true);
        CountDownLatch releaseSocial = new CountDownLatch(1);
        InetAddress publicAddress = InetAddress.getByName("93.184.216.34");
        CloseableHttpClient client = mock(CloseableHttpClient.class);
        when(client.executeOpen(any(), any(), any())).thenAnswer(invocation -> {
            socialEntered.countDown();
            assertTrue(releaseSocial.await(20, TimeUnit.SECONDS));
            BasicClassicHttpResponse response = new BasicClassicHttpResponse(200);
            response.setEntity(new StringEntity("{\"keys\":[]}", ContentType.APPLICATION_JSON));
            return response;
        });
        try (SsoHttpClient http = spy(new SsoHttpClient(properties, host -> {
            if (enterpriseAdmissions.get()) {
                occupied.countDown();
                try {
                    assertTrue(releaseEnterprise.await(30, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                throw new java.net.UnknownHostException("Stopped test provider");
            }
            return new InetAddress[] { publicAddress };
        }, Duration.ofSeconds(30)))) {
            doReturn(client).when(http).pinnedClient(anyString(), any(InetAddress[].class));
            try (var callers = Executors.newFixedThreadPool(SsoHttpClient.WORKERS + SsoHttpClient.SOCIAL_WORKERS)) {
                List<Future<?>> enterprise = new ArrayList<>();
                List<Future<String>> social = new ArrayList<>();
                for (int index = 0; index < SsoHttpClient.WORKERS; index++) {
                    String host = index == 0 ? "accounts.google.com" : "tenant-host-" + index + ".example";
                    String url = "https://" + host + "/jwks";
                    String registrationId = "org-" + (100 + index);
                    enterprise.add(callers.submit(() -> assertThrows(RestClientException.class,
                            () -> new RestTemplate(http.forEnterpriseRegistration(registrationId)).getForObject(url, String.class))));
                }
                try {
                    assertTrue(occupied.await(10, TimeUnit.SECONDS));
                    enterpriseAdmissions.set(false);
                    for (int index = 0; index < SsoHttpClient.SOCIAL_WORKERS; index++) {
                        social.add(callers.submit(() -> new RestTemplate(http)
                                .getForObject("https://accounts.google.com/jwks", String.class)));
                    }
                    assertTrue(socialEntered.await(10, TimeUnit.SECONDS));
                    releaseSocial.countDown();
                    for (Future<String> fetch : social) {
                        assertEquals("{\"keys\":[]}", fetch.get(10, TimeUnit.SECONDS));
                    }
                } finally {
                    releaseSocial.countDown();
                    releaseEnterprise.countDown();
                }
                for (Future<?> fetch : enterprise) {
                    fetch.get(10, TimeUnit.SECONDS);
                }
            }
        }
    }

    @Test
    void refusedDestinationsAreNotReportedAsSaturation() throws Exception {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        try (SsoHttpClient http = new SsoHttpClient(properties, host -> new InetAddress[] { loopback },
                Duration.ofSeconds(10))) {
            assertThrows(IllegalArgumentException.class,
                    () -> http.requireSafeEnterpriseDestinations(17, List.of("https://idp.example")));
        }
    }

    @Test
    void privateIssuerExemptionDoesNotReachConsumerSocialEgress() {
        properties.setAllowPrivateIssuerHosts(true);
        try (SsoHttpClient http = new SsoHttpClient(properties)) {
            assertThrows(RestClientException.class,
                    () -> new RestTemplate(http).getForObject(baseUrl + "/probe", String.class));
        }
        assertEquals(0, requests.get());
    }
}
