package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class AiChatPresenceRegistryTest {
    @Test
    void typingExpiresBeforePresenceAndExplicitRemovalIsImmediate() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-10T12:00:00Z"));
        AiChatPresenceRegistry registry = new AiChatPresenceRegistry(clock);

        AiChatPresenceRegistry.Snapshot initial = registry.touch(7, 13, 17, true);
        clock.advanceSeconds(7);
        AiChatPresenceRegistry.Snapshot afterTyping = registry.snapshot(7, 13);
        registry.remove(7, 13, 17);

        assertEquals(java.util.Set.of(17), initial.presentUserIds());
        assertEquals(java.util.Set.of(17), initial.typingUserIds());
        assertEquals(java.util.Set.of(17), afterTyping.presentUserIds());
        assertTrue(afterTyping.typingUserIds().isEmpty());
        assertTrue(registry.snapshot(7, 13).presentUserIds().isEmpty());
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
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
