package ooo.klae.connex.backend.services;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.NotificationQuietHours;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.NotificationQuietHoursDto;
import ooo.klae.connex.backend.dto.NotificationQuietHoursRequest;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.mappers.NotificationQuietHoursMapper;

/**
 * Authenticated global quiet-hours operations and delivery evaluation.
 */
@Service
@RequiredArgsConstructor
public class NotificationQuietHoursService {
    private static final String DEFAULT_START = "22:00";
    private static final String DEFAULT_END = "07:00";
    private static final String BYPASS_POLICY = "security_only";
    private static final int ALL_DAYS_MASK = 127;

    private final NotificationQuietHoursMapper quietHoursMapper;
    private final AuthService authService;
    private final NotificationQuietHoursEvaluator evaluator;
    private final Clock clock;

    @Transactional(readOnly = true)
    public NotificationQuietHoursDto getCurrent() {
        User user = authService.getCurrentUser();
        NotificationQuietHours quietHours = quietHoursMapper.findByUserId(user.getId());
        if (quietHours == null) {
            quietHours = defaults(user);
        }
        return toDto(quietHours, clock.instant());
    }

    @Transactional
    public NotificationQuietHoursDto updateCurrent(NotificationQuietHoursRequest request) {
        if (request == null || request.enabled() == null || request.days() == null) {
            throw new BadRequestException("Quiet hours require enabled, timezone, start, end, and days");
        }
        String timezone = TimezoneSupport.validateIana(request.timezone(), null);
        LocalTime start = parseLocalTime(request.start());
        LocalTime end = parseLocalTime(request.end());
        if (start.equals(end)) {
            throw new BadRequestException("Quiet-hours start and end must be different");
        }
        EnumSet<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        for (DayOfWeek day : request.days()) {
            if (day == null || !days.add(day)) {
                throw new BadRequestException("Quiet-hours days must be unique");
            }
        }
        if (request.enabled() && days.isEmpty()) {
            throw new BadRequestException("Enabled quiet hours require at least one day");
        }
        if (days.isEmpty()) {
            days = EnumSet.allOf(DayOfWeek.class);
        }
        NotificationQuietHours quietHours = new NotificationQuietHours();
        quietHours.setUserId(authService.getCurrentUser().getId());
        quietHours.setEnabled(request.enabled());
        quietHours.setTimezone(timezone);
        quietHours.setStartLocal(start.toString());
        quietHours.setEndLocal(end.toString());
        quietHours.setDaysMask(mask(days));
        quietHoursMapper.upsert(quietHours);
        return toDto(quietHours, clock.instant());
    }

    public NotificationQuietHoursEvaluator.Evaluation evaluateForUser(int userId, Instant asOf) {
        NotificationQuietHours quietHours = quietHoursMapper.findByUserId(userId);
        if (quietHours == null) {
            return new NotificationQuietHoursEvaluator.Evaluation(false, null);
        }
        return evaluator.evaluate(quietHours, asOf);
    }

    private NotificationQuietHoursDto toDto(NotificationQuietHours quietHours, Instant asOf) {
        NotificationQuietHoursEvaluator.Evaluation evaluation = evaluator.evaluate(quietHours, asOf);
        return new NotificationQuietHoursDto(
            quietHours.isEnabled(),
            quietHours.getTimezone(),
            LocalTime.parse(quietHours.getStartLocal()).toString(),
            LocalTime.parse(quietHours.getEndLocal()).toString(),
            days(quietHours.getDaysMask()),
            evaluation.active(),
            evaluation.nextTransitionAt() == null ? null : evaluation.nextTransitionAt().toString(),
            BYPASS_POLICY
        );
    }

    private static NotificationQuietHours defaults(User user) {
        NotificationQuietHours quietHours = new NotificationQuietHours();
        quietHours.setUserId(user.getId());
        quietHours.setTimezone(TimezoneSupport.validateIana(user.getTimezone(), "UTC"));
        quietHours.setStartLocal(DEFAULT_START);
        quietHours.setEndLocal(DEFAULT_END);
        quietHours.setDaysMask(ALL_DAYS_MASK);
        return quietHours;
    }

    private static LocalTime parseLocalTime(String value) {
        if (value == null || !value.matches("(?:[01]\\d|2[0-3]):[0-5]\\d")) {
            throw new BadRequestException("Quiet-hours times must use HH:mm");
        }
        return LocalTime.parse(value);
    }

    private static int mask(EnumSet<DayOfWeek> days) {
        int mask = 0;
        for (DayOfWeek day : days) {
            mask |= 1 << (day.getValue() - 1);
        }
        return mask;
    }

    private static List<DayOfWeek> days(int mask) {
        return Arrays.stream(DayOfWeek.values())
            .filter(day -> (mask & (1 << (day.getValue() - 1))) != 0)
            .toList();
    }
}
