package ooo.klae.connex.backend.password;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Bounded HIBP range client that transmits only a five-character SHA-1 prefix.
 */
@Component
public class HibpBreachedPasswordLookup implements BreachedPasswordLookup {
    static final URI RANGE_BASE = URI.create("https://api.pwnedpasswords.com/range/");
    static final String USER_AGENT = "Connex-Backend/BreachedPasswordScreening";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration MIN_REQUEST_INTERVAL = Duration.ofMillis(50);
    private static final int MAX_RESPONSE_BYTES = 2_000_000;
    private static final int MAX_ATTEMPTS = 2;
    private static final int MAX_CONCURRENT_REQUESTS = 4;

    private final RestClient restClient;
    private final URI rangeBase;
    private final LongSupplier nanoTime;
    private final int maxAttempts;
    private final int maxResponseBytes;
    private final long minRequestIntervalNanos;
    private final Semaphore capacity;
    private final AtomicLong nextRequestAtNanos = new AtomicLong();

    public HibpBreachedPasswordLookup() {
        this(newRestClient(), RANGE_BASE, System::nanoTime, MAX_ATTEMPTS, MAX_RESPONSE_BYTES,
                MAX_CONCURRENT_REQUESTS, MIN_REQUEST_INTERVAL);
    }

    HibpBreachedPasswordLookup(RestClient restClient, URI rangeBase, LongSupplier nanoTime,
            int maxAttempts, int maxResponseBytes, int maxConcurrentRequests,
            Duration minRequestInterval) {
        this.restClient = Objects.requireNonNull(restClient, "restClient");
        this.rangeBase = Objects.requireNonNull(rangeBase, "rangeBase");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.maxAttempts = positive(maxAttempts);
        this.maxResponseBytes = positive(maxResponseBytes);
        this.capacity = new Semaphore(positive(maxConcurrentRequests));
        this.minRequestIntervalNanos = Objects.requireNonNull(
                minRequestInterval, "minRequestInterval").toNanos();
    }

    @Override
    public boolean isBreached(String sha1Hex) {
        requireSha1(sha1Hex);
        if (!capacity.tryAcquire()) {
            throw unavailable(BreachedPasswordUnavailableReason.CAPACITY);
        }
        try {
            reserveRequestSlot();
            String prefix = sha1Hex.substring(0, 5);
            String suffix = sha1Hex.substring(5);
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                try {
                    RangeResponse response = request(prefix);
                    if (response.statusCode() == 200) {
                        return containsSuffix(response.body(), suffix);
                    }
                    if (response.statusCode() == 429) {
                        throw unavailable(BreachedPasswordUnavailableReason.RATE_LIMITED);
                    }
                    if (response.statusCode() < 500) {
                        throw unavailable(BreachedPasswordUnavailableReason.MALFORMED_RESPONSE);
                    }
                    if (attempt == maxAttempts) {
                        throw unavailable(BreachedPasswordUnavailableReason.UPSTREAM);
                    }
                } catch (BreachedPasswordSourceUnavailableException exception) {
                    throw exception;
                } catch (IOException | RuntimeException exception) {
                    if (attempt == maxAttempts) {
                        throw unavailable(timeoutReason(exception));
                    }
                }
            }
            throw unavailable(BreachedPasswordUnavailableReason.UPSTREAM);
        } finally {
            capacity.release();
        }
    }

    private RangeResponse request(String prefix) throws IOException {
        return restClient.get()
                .uri(rangeBase.resolve(prefix))
                .header("Add-Padding", "true")
                .header("User-Agent", USER_AGENT)
                .exchange((request, response) -> {
                    int statusCode = response.getStatusCode().value();
                    byte[] body = statusCode == 200
                            ? readBounded(response.getBody())
                            : new byte[0];
                    return new RangeResponse(statusCode, body);
                });
    }

    private boolean containsSuffix(byte[] body, String expectedSuffix) {
        String response = new String(body, StandardCharsets.US_ASCII);
        String[] lines = response.split("\n", -1);
        boolean sawValidRow = false;
        for (String rawLine : lines) {
            String line = rawLine.endsWith("\r")
                    ? rawLine.substring(0, rawLine.length() - 1)
                    : rawLine;
            if (line.isEmpty()) {
                continue;
            }
            int separator = line.indexOf(':');
            if (separator != 35 || line.indexOf(':', separator + 1) >= 0) {
                throw unavailable(BreachedPasswordUnavailableReason.MALFORMED_RESPONSE);
            }
            String suffix = line.substring(0, separator);
            String count = line.substring(separator + 1);
            if (!suffix.matches("[0-9A-F]{35}") || !count.matches("[0-9]+")) {
                throw unavailable(BreachedPasswordUnavailableReason.MALFORMED_RESPONSE);
            }
            long occurrences;
            try {
                occurrences = Long.parseLong(count);
            } catch (NumberFormatException exception) {
                throw unavailable(BreachedPasswordUnavailableReason.MALFORMED_RESPONSE);
            }
            sawValidRow = true;
            if (suffix.equals(expectedSuffix) && occurrences > 0) {
                return true;
            }
        }
        if (!sawValidRow) {
            throw unavailable(BreachedPasswordUnavailableReason.MALFORMED_RESPONSE);
        }
        return false;
    }

    private byte[] readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8_192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (read > maxResponseBytes - total) {
                throw unavailable(BreachedPasswordUnavailableReason.MALFORMED_RESPONSE);
            }
            output.write(buffer, 0, read);
            total += read;
        }
        return output.toByteArray();
    }

    private void reserveRequestSlot() {
        long now = nanoTime.getAsLong();
        long existing = nextRequestAtNanos.get();
        long next = now + minRequestIntervalNanos;
        if ((existing != 0 && now - existing < 0)
                || !nextRequestAtNanos.compareAndSet(existing, next == 0 ? 1 : next)) {
            throw unavailable(BreachedPasswordUnavailableReason.CAPACITY);
        }
    }

    static RestClient newRestClient() {
        HttpClient client = HttpClient.newBuilder()
                .proxy(HttpClient.Builder.NO_PROXY)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(client);
        requestFactory.setReadTimeout(REQUEST_TIMEOUT);
        return RestClient.builder().requestFactory(requestFactory).build();
    }

    private static BreachedPasswordUnavailableReason timeoutReason(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SocketTimeoutException || current instanceof HttpTimeoutException) {
                return BreachedPasswordUnavailableReason.TIMEOUT;
            }
            current = current.getCause();
        }
        return BreachedPasswordUnavailableReason.UPSTREAM;
    }

    private static void requireSha1(String sha1Hex) {
        if (sha1Hex == null || !sha1Hex.matches("[0-9A-F]{40}")) {
            throw unavailable(BreachedPasswordUnavailableReason.MALFORMED_RESPONSE);
        }
    }

    private static int positive(int value) {
        if (value <= 0) {
            throw new IllegalArgumentException("Breached-password client bound must be positive");
        }
        return value;
    }

    private static BreachedPasswordSourceUnavailableException unavailable(
            BreachedPasswordUnavailableReason reason) {
        return new BreachedPasswordSourceUnavailableException(reason);
    }

    private record RangeResponse(int statusCode, byte[] body) {
    }
}
