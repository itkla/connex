package ooo.klae.connex.backend.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class DateTimesTest {

    @Test
    void parsesFullMysqlDatetime() {
        assertEquals(Instant.parse("2026-06-20T10:00:00Z").toEpochMilli(),
            DateTimes.epochMillis("2026-06-20 10:00:00"));
    }

    @Test
    void toleratesIsoSeparatorTrailingZAndFraction() {
        long expected = Instant.parse("2026-06-20T10:00:00Z").toEpochMilli();
        assertEquals(expected, DateTimes.epochMillis("2026-06-20T10:00:00Z"));
        assertEquals(expected, DateTimes.epochMillis("2026-06-20T10:00:00.123Z"));
    }

    @Test
    void padsDateOnlyAndMinutePrecision() {
        assertEquals(Instant.parse("2026-06-20T00:00:00Z").toEpochMilli(),
            DateTimes.epochMillis("2026-06-20"));
        assertEquals(Instant.parse("2026-06-20T10:30:00Z").toEpochMilli(),
            DateTimes.epochMillis("2026-06-20 10:30"));
    }

    @Test
    void returnsNullForBlankOrUnparseable() {
        assertNull(DateTimes.epochMillis(null));
        assertNull(DateTimes.epochMillis("  "));
        assertNull(DateTimes.epochMillis("not-a-date"));
    }
}
