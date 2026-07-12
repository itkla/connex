package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.exceptions.BadRequestException;

@ExtendWith(MockitoExtension.class)
class UserCalendarServiceTest {
    @Mock private AuthService authService;

    private User user;
    private UserCalendarService service;

    @BeforeEach
    void setUp() {
        user = new User();
        when(authService.getCurrentUser()).thenReturn(user);
        Clock clock = Clock.fixed(Instant.parse("2026-07-10T00:30:00Z"), ZoneOffset.UTC);
        service = new UserCalendarService(authService, clock);
    }

    @Test
    void todayUsesThePersistedTimezoneAcrossUtcDateBoundaries() {
        user.setTimezone("America/Los_Angeles");
        assertEquals(LocalDate.of(2026, 7, 9), service.today());

        user.setTimezone("Asia/Tokyo");
        assertEquals(LocalDate.of(2026, 7, 10), service.today());
    }

    @Test
    void todayRejectsMalformedStoredTimezones() {
        user.setTimezone("Mars/Olympus");

        assertThrows(BadRequestException.class, service::today);
    }
}
