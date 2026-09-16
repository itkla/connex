package ooo.klae.connex.backend.signature;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import ooo.klae.connex.backend.exceptions.TooManyRequestsException;

class DocumentAcceptanceRateLimiterTest {
    @Test
    void distinctUnknownWorkspaceTokensStillShareTheSourceBucket() {
        SignatureProperties properties = new SignatureProperties();
        properties.setMaxRequestsPerSource(1);
        DocumentAcceptanceRateLimiter rateLimiter = new DocumentAcceptanceRateLimiter(
            properties,
            Clock.fixed(Instant.parse("2026-08-15T00:00:00Z"), ZoneOffset.UTC));

        rateLimiter.acquire(
            DocumentAcceptanceToken.hash("w2147483646-" + "a".repeat(64)),
            "198.51.100.40");

        assertThrows(TooManyRequestsException.class, () -> rateLimiter.acquire(
            DocumentAcceptanceToken.hash("w2147483645-" + "b".repeat(64)),
            "198.51.100.40"));
    }

    @Test
    void exhaustedSourceCannotAllocateBucketsThatDenyAFreshSource() {
        SignatureProperties properties = new SignatureProperties();
        properties.setMaxRequestsPerSource(1);
        properties.setRateLimitMaxKeys(3);
        DocumentAcceptanceRateLimiter rateLimiter = new DocumentAcceptanceRateLimiter(
            properties, Clock.fixed(Instant.parse("2026-08-15T00:00:00Z"), ZoneOffset.UTC));

        rateLimiter.acquire("first", "198.51.100.40");
        assertThrows(TooManyRequestsException.class, () -> rateLimiter.acquire("second", "198.51.100.40"));
        assertThrows(TooManyRequestsException.class, () -> rateLimiter.acquire("third", "198.51.100.40"));

        assertDoesNotThrow(() -> rateLimiter.acquire("fourth", "198.51.100.41"));
        assertEquals(2, trackedKeys(rateLimiter, "tokenWindows"));
    }

    @Test
    void exhaustedSourceCannotConsumeAnotherTokensAllowance() {
        SignatureProperties properties = new SignatureProperties();
        properties.setMaxRequestsPerSource(1);
        properties.setMaxRequestsPerToken(1);
        DocumentAcceptanceRateLimiter rateLimiter = new DocumentAcceptanceRateLimiter(
            properties, Clock.fixed(Instant.parse("2026-08-15T00:00:00Z"), ZoneOffset.UTC));
        rateLimiter.acquire("first", "198.51.100.40");

        assertThrows(TooManyRequestsException.class, () -> rateLimiter.acquire("shared", "198.51.100.40"));

        assertDoesNotThrow(() -> rateLimiter.acquire("shared", "198.51.100.41"));
    }

    @Test
    void admittedSourceCannotOwnTheWholeTokenRegistry() {
        SignatureProperties properties = new SignatureProperties();
        properties.setRateLimitMaxKeys(3);
        DocumentAcceptanceRateLimiter rateLimiter = new DocumentAcceptanceRateLimiter(
            properties, Clock.fixed(Instant.parse("2026-08-15T00:00:00Z"), ZoneOffset.UTC));
        rateLimiter.acquire("first", "198.51.100.40");

        assertThrows(TooManyRequestsException.class, () -> rateLimiter.acquire("second", "198.51.100.40"));
        assertThrows(TooManyRequestsException.class, () -> rateLimiter.acquire("third", "198.51.100.40"));

        assertDoesNotThrow(() -> rateLimiter.acquire("fourth", "198.51.100.41"));
        assertDoesNotThrow(() -> rateLimiter.acquire("first", "198.51.100.40"));
    }

    @Test
    void sourceWindowResetPreservesItsLiveTokenAllocationCap() {
        SignatureProperties properties = new SignatureProperties();
        properties.setRateLimitMaxKeys(4);
        Clock clock = mock(Clock.class);
        when(clock.millis()).thenReturn(0L);
        DocumentAcceptanceRateLimiter rateLimiter = new DocumentAcceptanceRateLimiter(properties, clock);
        rateLimiter.acquire("first", "198.51.100.40");
        when(clock.millis()).thenReturn(30_000L);
        rateLimiter.acquire("second", "198.51.100.40");
        when(clock.millis()).thenReturn(60_000L);
        rateLimiter.acquire("third", "198.51.100.40");

        assertThrows(TooManyRequestsException.class, () -> rateLimiter.acquire("fourth", "198.51.100.40"));

        assertDoesNotThrow(() -> rateLimiter.acquire("fourth", "198.51.100.41"));
        when(clock.millis()).thenReturn(90_000L);
        assertDoesNotThrow(() -> rateLimiter.acquire("fifth", "198.51.100.40"));
    }

    @Test
    void evictionWorkIsBoundedAndSubsequentAdmissionReclaimsExpiredCapacity() {
        SignatureProperties properties = new SignatureProperties();
        properties.setRateLimitMaxKeys(128);
        Clock clock = mock(Clock.class);
        when(clock.millis()).thenReturn(0L);
        DocumentAcceptanceRateLimiter rateLimiter = new DocumentAcceptanceRateLimiter(properties, clock);
        for (int index = 0; index < 128; index++) {
            rateLimiter.acquire("token-" + index, "source-" + index);
        }
        when(clock.millis()).thenReturn(60_000L);

        rateLimiter.evictStale();

        assertEquals(96, trackedKeys(rateLimiter, "tokenWindows"));
        assertEquals(96, trackedKeys(rateLimiter, "sourceWindows"));
        assertDoesNotThrow(() -> rateLimiter.acquire("fresh", "fresh-source"));
        assertEquals(65, trackedKeys(rateLimiter, "tokenWindows"));
        assertEquals(65, trackedKeys(rateLimiter, "sourceWindows"));
    }

    private static int trackedKeys(DocumentAcceptanceRateLimiter rateLimiter, String field) {
        Object value = ReflectionTestUtils.getField(rateLimiter, field);
        if (value instanceof Map<?, ?> windows) {
            return windows.size();
        }
        throw new AssertionError("Missing rate-limit registry: " + field);
    }
}
