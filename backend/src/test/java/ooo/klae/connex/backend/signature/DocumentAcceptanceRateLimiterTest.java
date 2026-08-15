package ooo.klae.connex.backend.signature;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

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
}
