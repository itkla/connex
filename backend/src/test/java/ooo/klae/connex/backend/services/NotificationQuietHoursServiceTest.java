package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.NotificationQuietHours;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.NotificationQuietHoursDto;
import ooo.klae.connex.backend.dto.NotificationQuietHoursRequest;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.mappers.NotificationQuietHoursMapper;

@ExtendWith(MockitoExtension.class)
class NotificationQuietHoursServiceTest {
    @Mock private NotificationQuietHoursMapper quietHoursMapper;
    @Mock private AuthService authService;

    private NotificationQuietHoursService service;

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setId(42);
        user.setTimezone("America/New_York");
        lenient().when(authService.getCurrentUser()).thenReturn(user);
        service = new NotificationQuietHoursService(
            quietHoursMapper,
            authService,
            new NotificationQuietHoursEvaluator(),
            Clock.fixed(Instant.parse("2026-07-21T03:00:00Z"), ZoneOffset.UTC)
        );
    }

    @Test
    void absentRowReturnsDisabledProfileDefaults() {
        NotificationQuietHoursDto result = service.getCurrent();

        assertFalse(result.enabled());
        assertEquals("America/New_York", result.timezone());
        assertEquals("22:00", result.start());
        assertEquals("07:00", result.end());
        assertEquals(List.of(DayOfWeek.values()), result.days());
        assertFalse(result.activeNow());
        assertEquals("security_only", result.bypassPolicy());
    }

    @Test
    void updateDerivesUserAndPersistsOrderedDayMask() {
        NotificationQuietHoursRequest request = new NotificationQuietHoursRequest(
            true,
            "America/New_York",
            "22:00",
            "07:00",
            List.of(DayOfWeek.MONDAY, DayOfWeek.FRIDAY)
        );

        NotificationQuietHoursDto result = service.updateCurrent(request);

        ArgumentCaptor<NotificationQuietHours> saved = ArgumentCaptor.forClass(NotificationQuietHours.class);
        verify(quietHoursMapper).upsert(saved.capture());
        assertEquals(42, saved.getValue().getUserId());
        assertEquals(17, saved.getValue().getDaysMask());
        assertTrue(result.activeNow());
        assertEquals(List.of(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), result.days());
    }

    @Test
    void disabledEmptyDaysNormalizeToAllDays() {
        NotificationQuietHoursRequest request = new NotificationQuietHoursRequest(
            false, "UTC", "22:00", "07:00", List.of());

        NotificationQuietHoursDto result = service.updateCurrent(request);

        assertEquals(List.of(DayOfWeek.values()), result.days());
    }

    @Test
    void rejectsEnabledEmptyDuplicateDaysEqualTimesAndInvalidTimezone() {
        assertThrows(BadRequestException.class, () -> service.updateCurrent(
            new NotificationQuietHoursRequest(true, "UTC", "22:00", "07:00", List.of())));
        assertThrows(BadRequestException.class, () -> service.updateCurrent(
            new NotificationQuietHoursRequest(false, "UTC", "22:00", "07:00",
                List.of(DayOfWeek.MONDAY, DayOfWeek.MONDAY))));
        assertThrows(BadRequestException.class, () -> service.updateCurrent(
            new NotificationQuietHoursRequest(false, "UTC", "22:00", "22:00", List.of())));
        assertThrows(BadRequestException.class, () -> service.updateCurrent(
            new NotificationQuietHoursRequest(false, "+09:00", "22:00", "07:00", List.of())));
    }
}
