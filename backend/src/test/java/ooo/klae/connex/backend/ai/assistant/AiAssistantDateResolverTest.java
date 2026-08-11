package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.services.AuthService;

class AiAssistantDateResolverTest {
    @Test
    void nextThursdayUsesTheActorsTimezoneAcrossTheDstBoundary() {
        AuthService authService = mock(AuthService.class);
        User actor = new User();
        actor.setTimezone("America/New_York");
        when(authService.getCurrentUser()).thenReturn(actor);
        AiAssistantDateResolver resolver = new AiAssistantDateResolver(
                authService,
                Clock.fixed(Instant.parse("2026-03-06T15:00:00Z"), ZoneOffset.UTC));

        AiAssistantDateResolver.ResolvedDateTime resolved =
                resolver.resolveDateTime("9:00am next Thursday");

        assertEquals("America/New_York", resolved.timezone().getId());
        assertEquals("2026-03-12T09:00", resolved.local().toString());
        assertEquals("2026-03-12T13:00", resolved.utc().toString());
        assertEquals("2026-03-12 13:00:00", resolved.mysqlUtc());
    }

    @Test
    void nonexistentLocalTimeInDstGapIsRejected() {
        AiAssistantDateResolver resolver = resolver("America/New_York");

        assertThrows(
                BadRequestException.class,
                () -> resolver.resolveDateTime("2026-03-08T02:30:00"));
    }

    @Test
    void ambiguousLocalTimeInDstOverlapIsRejectedWithoutAnOffset() {
        AiAssistantDateResolver resolver = resolver("America/New_York");

        assertThrows(
                BadRequestException.class,
                () -> resolver.resolveDateTime("2026-11-01T01:30:00"));
        assertEquals(
                "2026-11-01T05:30",
                resolver.resolveDateTime("2026-11-01T01:30:00-04:00").utc().toString());
    }

    private static AiAssistantDateResolver resolver(String timezone) {
        AuthService authService = mock(AuthService.class);
        User actor = new User();
        actor.setTimezone(timezone);
        when(authService.getCurrentUser()).thenReturn(actor);
        return new AiAssistantDateResolver(
                authService,
                Clock.fixed(Instant.parse("2026-03-06T15:00:00Z"), ZoneOffset.UTC));
    }
}
