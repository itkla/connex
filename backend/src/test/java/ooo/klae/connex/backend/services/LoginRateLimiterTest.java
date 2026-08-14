package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.util.ClientIpResolver.ResolvedClientIp;

/**
 * Verifies the failed-login throttle: it blocks past the per-IP and per-username caps,
 * isolates by key, clears the username bucket on success, resets after the window, and
 * never attributes failures to null/blank keys.
 */
class LoginRateLimiterTest {

    @Test
    void blocksAfterPerIpCap() {
        LoginRateLimiter limiter = new LoginRateLimiter(3, 100, 5000, 900);
        long now = 1_000L;

        for (int i = 0; i < 3; i++) {
            assertFalse(limiter.isBlocked("1.1.1.1", "alice", now));
            limiter.recordFailure("1.1.1.1", "u" + i, now);
        }
        assertTrue(limiter.isBlocked("1.1.1.1", "bob", now), "the per-IP cap must block regardless of username");
    }

    @Test
    void blocksAfterPerUsernameCapAcrossIps() {
        LoginRateLimiter limiter = new LoginRateLimiter(100, 3, 5000, 900);
        long now = 1_000L;

        limiter.recordFailure("1.1.1.1", "victim", now);
        limiter.recordFailure("2.2.2.2", "VICTIM", now);
        limiter.recordFailure("3.3.3.3", "victim", now);
        assertTrue(limiter.isBlocked("4.4.4.4", "victim", now), "per-username cap must block distributed stuffing");
    }

    @Test
    void successClearsUsernameBucket() {
        LoginRateLimiter limiter = new LoginRateLimiter(100, 2, 5000, 900);
        long now = 1_000L;

        limiter.recordFailure("1.1.1.1", "alice", now);
        limiter.recordFailure("1.1.1.1", "alice", now);
        assertTrue(limiter.isBlocked("9.9.9.9", "alice", now));

        limiter.recordSuccess("alice");
        assertFalse(limiter.isBlocked("9.9.9.9", "alice", now), "a successful login clears the username bucket");
    }

    @Test
    void resetsAfterWindowElapses() {
        LoginRateLimiter limiter = new LoginRateLimiter(1, 100, 5000, 900);
        long start = 1_000L;

        limiter.recordFailure("1.1.1.1", "alice", start);
        assertTrue(limiter.isBlocked("1.1.1.1", "alice", start));
        assertFalse(limiter.isBlocked("1.1.1.1", "alice", start + 900_000L), "budget refreshes after the window");
    }

    @Test
    void skipsPerIpThrottleForNonPublicAddress() {
        LoginRateLimiter limiter = new LoginRateLimiter(1, 100, 5000, 900);
        long now = 1_000L;

        for (int i = 0; i < 5; i++) {
            limiter.recordFailure("127.0.0.1", "u" + i, now);
        }
        assertFalse(limiter.isBlocked("127.0.0.1", "bystander", now),
            "a loopback proxy address must not per-IP-lock the whole instance");

        for (int i = 0; i < 5; i++) {
            limiter.recordFailure("10.0.0.5", "v" + i, now);
        }
        assertFalse(limiter.isBlocked("10.0.0.5", "bystander", now),
            "a private proxy address must not per-IP-lock the whole instance");
    }

    @Test
    void throttlesSanitizedPrivateClientWithoutThrottlingPrivateDirectPeer() {
        LoginRateLimiter limiter = new LoginRateLimiter(1, 100, 5000, 900);
        long now = 1_000L;
        ResolvedClientIp privateClient = new ResolvedClientIp("172.20.5.10", true);
        ResolvedClientIp privateProxy = new ResolvedClientIp("172.18.0.4", false);

        limiter.recordFailureForClient(privateClient, "alice", now);
        limiter.recordFailureForClient(privateProxy, "bob", now);

        assertTrue(limiter.isBlockedForClient(privateClient, "bystander", now));
        assertFalse(limiter.isBlockedForClient(privateProxy, "bystander", now));
    }

    @Test
    void ignoresUnattributableKeys() {
        LoginRateLimiter limiter = new LoginRateLimiter(1, 1, 5000, 900);
        limiter.recordFailure(null, null, 1_000L);
        limiter.recordFailure("", "   ", 1_000L);
        assertFalse(limiter.isBlocked(null, null, 1_000L));
        assertFalse(limiter.isBlocked("", "", 1_000L));
    }

    @Test
    void oneTimeLinkExchangeUsesAnIndependentIpBucket() {
        LoginRateLimiter limiter = new LoginRateLimiter(1, 100, 5000, 900);
        long now = 1_000L;

        assertTrue(limiter.tryAcquireOneTimeLinkExchange(new ResolvedClientIp("203.0.113.8", false), now));
        assertFalse(limiter.tryAcquireOneTimeLinkExchange(new ResolvedClientIp("203.0.113.8", false), now));
        assertFalse(limiter.isBlocked("203.0.113.8", "alice", now));
    }

    @Test
    void oneTimeLinkExchangeUsesASeparateSharedSourceCircuitBreaker() {
        LoginRateLimiter limiter = new LoginRateLimiter(1, 100, 2, 900);
        long now = 1_000L;

        assertTrue(limiter.tryAcquireOneTimeLinkExchange(new ResolvedClientIp("10.0.0.5", false), now));
        assertTrue(limiter.tryAcquireOneTimeLinkExchange(null, now));
        assertFalse(limiter.tryAcquireOneTimeLinkExchange(new ResolvedClientIp(" ", false), now));
    }
}
