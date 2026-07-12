package ooo.klae.connex.backend.services;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.User;

/**
 * Resolves calendar boundaries from the authenticated user's persisted timezone.
 */
@Service
@RequiredArgsConstructor
public class UserCalendarService {
    private final AuthService authService;
    private final Clock clock;

    /**
     * Returns the authenticated user's current local calendar date.
     */
    public LocalDate today() {
        User user = authService.getCurrentUser();
        ZoneId timezone = ZoneId.of(TimezoneSupport.validate(user.getTimezone(), "UTC"));
        return LocalDate.now(clock.withZone(timezone));
    }
}
