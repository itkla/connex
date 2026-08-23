package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.exceptions.TooManyRequestsException;

/**
 * The scope preview evaluates a whole smart segment and costs no model tokens, so no generation
 * budget bounds it. These cases pin the only thing that does.
 */
class AiChatScopePreviewRateLimiterTest {
    private static final Instant NOW = Instant.parse("2026-08-23T04:00:00Z");

    @Test
    void anExhaustedWindowRefusesFurtherSegmentEvaluationForThatPrincipal() {
        AiChatScopePreviewRateLimiter limiter =
                new AiChatScopePreviewRateLimiter(2, 60, Clock.fixed(NOW, ZoneOffset.UTC));

        limiter.acquire(7, 11);
        limiter.acquire(7, 11);

        assertThrows(TooManyRequestsException.class, () -> limiter.acquire(7, 11));
    }

    @Test
    void theWindowIsPerWorkspaceAndMemberRatherThanGlobal() {
        AiChatScopePreviewRateLimiter limiter =
                new AiChatScopePreviewRateLimiter(1, 60, Clock.fixed(NOW, ZoneOffset.UTC));

        limiter.acquire(7, 11);
        limiter.acquire(7, 12);
        limiter.acquire(8, 11);

        assertThrows(TooManyRequestsException.class, () -> limiter.acquire(7, 11));
        assertEquals(3, limiter.trackedPrincipals());
    }

    @Test
    void theAllowanceReturnsOnceTheFixedWindowHasElapsed() {
        MutableClock clock = new MutableClock(NOW);
        AiChatScopePreviewRateLimiter limiter =
                new AiChatScopePreviewRateLimiter(1, 60, clock);

        limiter.acquire(7, 11);
        assertThrows(TooManyRequestsException.class, () -> limiter.acquire(7, 11));

        clock.advanceSeconds(61);
        limiter.acquire(7, 11);
        limiter.evictStale();

        assertEquals(1, limiter.trackedPrincipals());
    }

    @Test
    void nonPositiveSettingsAreRefusedRatherThanDisablingTheLimit() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

        assertThrows(IllegalArgumentException.class,
                () -> new AiChatScopePreviewRateLimiter(0, 60, clock));
        assertThrows(IllegalArgumentException.class,
                () -> new AiChatScopePreviewRateLimiter(30, 0, clock));
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advanceSeconds(long seconds) {
            instant = instant.plusSeconds(seconds);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
