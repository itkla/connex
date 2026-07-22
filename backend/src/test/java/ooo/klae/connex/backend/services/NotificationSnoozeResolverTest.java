package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.dto.SnoozeRequest;
import ooo.klae.connex.backend.exceptions.BadRequestException;

class NotificationSnoozeResolverTest {
    private static final Instant NOW = Instant.parse("2026-07-17T20:00:00Z");
    private final NotificationSnoozeResolver resolver = new NotificationSnoozeResolver(
        Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void resolvesPresetsInTheRequestedTimezone() {
        NotificationSnoozeResolver.Resolution later = resolver.resolve(
            preset("later_today", "America/New_York"));
        NotificationSnoozeResolver.Resolution tomorrow = resolver.resolve(
            preset("tomorrow_morning", "America/New_York"));
        NotificationSnoozeResolver.Resolution nextWeek = resolver.resolve(
            preset("next_week", "America/New_York"));

        assertEquals(Instant.parse("2026-07-17T21:00:00Z"), later.until());
        assertEquals(Instant.parse("2026-07-18T13:00:00Z"), tomorrow.until());
        assertEquals(Instant.parse("2026-07-20T13:00:00Z"), nextWeek.until());
        assertEquals("America/New_York", nextWeek.timezone());
    }

    @Test
    void rejectsLaterTodayWhenFivePmHasPassed() {
        NotificationSnoozeResolver eveningResolver = new NotificationSnoozeResolver(
            Clock.fixed(Instant.parse("2026-07-17T22:00:00Z"), ZoneOffset.UTC));

        assertThrows(BadRequestException.class,
            () -> eveningResolver.resolve(preset("later_today", "America/New_York")));
    }

    @Test
    void resolvesCustomAndLegacyRequests() {
        SnoozeRequest custom = new SnoozeRequest();
        custom.setUntil("2026-07-18T05:30:00Z");
        custom.setTimezone("Asia/Tokyo");
        SnoozeRequest legacy = new SnoozeRequest();
        legacy.setHours(2);

        assertEquals(Instant.parse("2026-07-18T05:30:00Z"), resolver.resolve(custom).until());
        assertEquals("Asia/Tokyo", resolver.resolve(custom).timezone());
        assertEquals(Instant.parse("2026-07-17T22:00:00Z"), resolver.resolve(legacy).until());
        assertEquals("UTC", resolver.resolve(legacy).timezone());
    }

    @Test
    void rejectsInvalidSelectorTimezonePastAndHorizon() {
        SnoozeRequest none = new SnoozeRequest();
        SnoozeRequest both = preset("tomorrow_morning", "UTC");
        both.setUntil("2026-07-18T05:30:00Z");
        SnoozeRequest invalidZone = preset("tomorrow_morning", "+09:00");
        SnoozeRequest past = custom("2026-07-17T19:59:59Z");
        SnoozeRequest beyond = custom("2026-08-16T20:00:01Z");
        SnoozeRequest fractionalBeyond = custom("2026-08-16T20:00:00.500Z");

        assertThrows(BadRequestException.class, () -> resolver.resolve(none));
        assertThrows(BadRequestException.class, () -> resolver.resolve(both));
        assertThrows(BadRequestException.class, () -> resolver.resolve(invalidZone));
        assertThrows(BadRequestException.class, () -> resolver.resolve(past));
        assertThrows(BadRequestException.class, () -> resolver.resolve(beyond));
        assertThrows(BadRequestException.class, () -> resolver.resolve(fractionalBeyond));
    }

    @Test
    void localResolutionMovesGapsForwardAndUsesEarlierOverlapOffset() {
        ZoneId zone = ZoneId.of("America/New_York");

        assertEquals(
            Instant.parse("2026-03-08T07:30:00Z"),
            NotificationSnoozeResolver.resolveLocal(LocalDateTime.parse("2026-03-08T02:30:00"), zone));
        assertEquals(
            Instant.parse("2026-11-01T05:30:00Z"),
            NotificationSnoozeResolver.resolveLocal(LocalDateTime.parse("2026-11-01T01:30:00"), zone));
    }

    private static SnoozeRequest preset(String value, String timezone) {
        SnoozeRequest request = new SnoozeRequest();
        request.setPreset(value);
        request.setTimezone(timezone);
        return request;
    }

    private static SnoozeRequest custom(String until) {
        SnoozeRequest request = new SnoozeRequest();
        request.setUntil(until);
        request.setTimezone("UTC");
        return request;
    }
}
