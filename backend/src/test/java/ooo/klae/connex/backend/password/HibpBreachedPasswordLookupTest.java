package ooo.klae.connex.backend.password;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.sun.net.httpserver.HttpServer;

class HibpBreachedPasswordLookupTest {
    private static final URI RANGE_BASE = URI.create("https://api.pwnedpasswords.com/range/");
    private static final String PASSWORD = "password";
    private static final String PASSWORD_SHA1 = "5BAA61E4C9B93F3F0682250B6CF8331B7EE68FD8";
    private static final String PASSWORD_SUFFIX = "1E4C9B93F3F0682250B6CF8331B7EE68FD8";
    private static final String UNIQUE_SHA1 = "C805A2FFAF2B30CC484C8D610DFCC5292C1794DE";

    @Test
    void sendsOnlyFiveCharacterPrefixWithPaddingAndMatchesSuffixLocally() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(RANGE_BASE + "5BAA6"))
                .andExpect(method(GET))
                .andExpect(header("Add-Padding", "true"))
                .andExpect(header("User-Agent", HibpBreachedPasswordLookup.USER_AGENT))
                .andExpect(request -> {
                    String requestMetadata = request.getURI().getRawPath()
                            + request.getHeaders();
                    assertFalse(requestMetadata.contains(PASSWORD));
                    assertFalse(requestMetadata.contains(PASSWORD_SHA1));
                    assertFalse(requestMetadata.contains(PASSWORD_SUFFIX));
                })
                .andRespond(withSuccess(PASSWORD_SUFFIX + ":100\r\n", MediaType.TEXT_PLAIN));
        HibpBreachedPasswordLookup lookup = lookup(builder.build(), 2);

        assertTrue(lookup.isBreached(PASSWORD_SHA1));

        server.verify();
    }

    @Test
    void paddingRowsWithZeroCountDoNotRejectUniquePassword() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(RANGE_BASE + "C805A"))
                .andRespond(withSuccess(UNIQUE_SHA1.substring(5) + ":0\n", MediaType.TEXT_PLAIN));
        HibpBreachedPasswordLookup lookup = lookup(builder.build(), 2);

        assertFalse(lookup.isBreached(UNIQUE_SHA1));

        server.verify();
    }

    @Test
    void retriesOneServerFailureThenSucceeds() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(RANGE_BASE + "C805A")).andRespond(withServerError());
        server.expect(requestTo(RANGE_BASE + "C805A"))
                .andRespond(withSuccess("00000000000000000000000000000000000:0\n", MediaType.TEXT_PLAIN));
        HibpBreachedPasswordLookup lookup = lookup(builder.build(), 2);

        assertFalse(lookup.isBreached(UNIQUE_SHA1));

        server.verify();
    }

    @Test
    void repeatedServerFailureIsUnavailableAfterBoundedRetry() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(RANGE_BASE + "C805A")).andRespond(withServerError());
        server.expect(requestTo(RANGE_BASE + "C805A")).andRespond(withServerError());
        HibpBreachedPasswordLookup lookup = lookup(builder.build(), 2);

        BreachedPasswordSourceUnavailableException exception = assertThrows(
                BreachedPasswordSourceUnavailableException.class,
                () -> lookup.isBreached(UNIQUE_SHA1));

        assertEquals(BreachedPasswordUnavailableReason.UPSTREAM, exception.getReason());
        server.verify();
    }

    @Test
    void emptySuccessResponseFailsClosedAsMalformed() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(RANGE_BASE + "C805A"))
                .andRespond(withSuccess("", MediaType.TEXT_PLAIN));
        HibpBreachedPasswordLookup lookup = lookup(builder.build(), 1);

        BreachedPasswordSourceUnavailableException exception = assertThrows(
                BreachedPasswordSourceUnavailableException.class,
                () -> lookup.isBreached(UNIQUE_SHA1));

        assertEquals(BreachedPasswordUnavailableReason.MALFORMED_RESPONSE, exception.getReason());
        server.verify();
    }

    @Test
    void oversizedResponseFailsClosedAsMalformed() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(RANGE_BASE + "C805A"))
                .andRespond(withSuccess(
                        "00000000000000000000000000000000000:0\n",
                        MediaType.TEXT_PLAIN));
        HibpBreachedPasswordLookup lookup = new HibpBreachedPasswordLookup(
                builder.build(), RANGE_BASE, () -> 1L, 1, 8, 1, Duration.ZERO);

        BreachedPasswordSourceUnavailableException exception = assertThrows(
                BreachedPasswordSourceUnavailableException.class,
                () -> lookup.isBreached(UNIQUE_SHA1));

        assertEquals(BreachedPasswordUnavailableReason.MALFORMED_RESPONSE, exception.getReason());
        server.verify();
    }

    @Test
    void overflowingOccurrenceCountFailsClosedAsMalformed() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(RANGE_BASE + "C805A"))
                .andRespond(withSuccess(
                        "00000000000000000000000000000000000:999999999999999999999999\n",
                        MediaType.TEXT_PLAIN));
        HibpBreachedPasswordLookup lookup = lookup(builder.build(), 1);

        BreachedPasswordSourceUnavailableException exception = assertThrows(
                BreachedPasswordSourceUnavailableException.class,
                () -> lookup.isBreached(UNIQUE_SHA1));

        assertEquals(BreachedPasswordUnavailableReason.MALFORMED_RESPONSE, exception.getReason());
        server.verify();
    }

    @Test
    void rateLimitIsSanitizedAndNotRetried() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(RANGE_BASE + "C805A"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        HibpBreachedPasswordLookup lookup = lookup(builder.build(), 2);

        BreachedPasswordSourceUnavailableException exception = assertThrows(
                BreachedPasswordSourceUnavailableException.class,
                () -> lookup.isBreached(UNIQUE_SHA1));

        assertEquals(BreachedPasswordUnavailableReason.RATE_LIMITED, exception.getReason());
        assertFalse(exception.getMessage().contains(UNIQUE_SHA1));
        server.verify();
    }

    @Test
    void timeoutIsBoundedRetriedAndSanitized() {
        RestClient client = RestClient.builder()
                .requestFactory((uri, method) -> {
                    throw new SocketTimeoutException("sentinel-sensitive-timeout");
                })
                .build();
        HibpBreachedPasswordLookup lookup = new HibpBreachedPasswordLookup(
                client, RANGE_BASE, () -> 1L, 2, 1024, 1, Duration.ZERO);

        BreachedPasswordSourceUnavailableException exception = assertThrows(
                BreachedPasswordSourceUnavailableException.class,
                () -> lookup.isBreached(UNIQUE_SHA1));

        assertEquals(BreachedPasswordUnavailableReason.TIMEOUT, exception.getReason());
        assertFalse(exception.getMessage().contains("sentinel"));
    }

    @Test
    void configuredReadDeadlineFailsClosedForSlowBody() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        CountDownLatch responseStarted = new CountDownLatch(1);
        CountDownLatch releaseResponse = new CountDownLatch(1);
        server.createContext("/range/", exchange -> {
            responseStarted.countDown();
            exchange.sendResponseHeaders(200, 100);
            try {
                releaseResponse.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/range/");
        HibpBreachedPasswordLookup lookup = new HibpBreachedPasswordLookup(
                HibpBreachedPasswordLookup.newRestClient(), base, System::nanoTime,
                1, 1024, 1, Duration.ZERO);

        try {
            BreachedPasswordSourceUnavailableException exception = assertThrows(
                    BreachedPasswordSourceUnavailableException.class,
                    () -> lookup.isBreached(UNIQUE_SHA1));

            assertEquals(BreachedPasswordUnavailableReason.TIMEOUT, exception.getReason());
            assertTrue(responseStarted.await(1, TimeUnit.SECONDS));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while verifying slow response timeout");
        } finally {
            releaseResponse.countDown();
            server.stop(0);
        }
    }

    @Test
    void repeatedCallsInsideRateWindowAreRefusedWithoutAnotherRequest() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(RANGE_BASE + "C805A"))
                .andRespond(withSuccess("00000000000000000000000000000000000:0\n", MediaType.TEXT_PLAIN));
        AtomicLong now = new AtomicLong(100L);
        HibpBreachedPasswordLookup lookup = new HibpBreachedPasswordLookup(
                builder.build(), RANGE_BASE, now::get, 1, 1024, 1, Duration.ofNanos(10));

        assertFalse(lookup.isBreached(UNIQUE_SHA1));
        BreachedPasswordSourceUnavailableException exception = assertThrows(
                BreachedPasswordSourceUnavailableException.class,
                () -> lookup.isBreached(UNIQUE_SHA1));

        assertEquals(BreachedPasswordUnavailableReason.CAPACITY, exception.getReason());
        server.verify();
    }

    @Test
    void concurrentRequestBeyondCapacityFailsImmediately() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        CountDownLatch requestStarted = new CountDownLatch(1);
        CountDownLatch releaseRequest = new CountDownLatch(1);
        server.expect(requestTo(RANGE_BASE + "C805A"))
                .andRespond(request -> {
                    requestStarted.countDown();
                    try {
                        if (!releaseRequest.await(2, TimeUnit.SECONDS)) {
                            throw new IOException("Timed out waiting to release mock response");
                        }
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted while waiting to release mock response");
                    }
                    return withSuccess(
                            "00000000000000000000000000000000000:0\n",
                            MediaType.TEXT_PLAIN).createResponse(request);
                });
        HibpBreachedPasswordLookup lookup = new HibpBreachedPasswordLookup(
                builder.build(), RANGE_BASE, System::nanoTime, 1, 1024, 1, Duration.ZERO);

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<Boolean> first = executor.submit(() -> lookup.isBreached(UNIQUE_SHA1));
            assertTrue(requestStarted.await(1, TimeUnit.SECONDS));

            BreachedPasswordSourceUnavailableException exception = assertThrows(
                    BreachedPasswordSourceUnavailableException.class,
                    () -> lookup.isBreached(UNIQUE_SHA1));

            assertEquals(BreachedPasswordUnavailableReason.CAPACITY, exception.getReason());
            releaseRequest.countDown();
            assertFalse(result(first));
        } finally {
            releaseRequest.countDown();
        }
        server.verify();
    }

    private static HibpBreachedPasswordLookup lookup(RestClient restClient, int maxAttempts) {
        return new HibpBreachedPasswordLookup(
                restClient, RANGE_BASE, () -> 1L, maxAttempts, 1024, 1, Duration.ZERO);
    }

    private static boolean result(Future<Boolean> result)
            throws InterruptedException, ExecutionException {
        return result.get();
    }
}
