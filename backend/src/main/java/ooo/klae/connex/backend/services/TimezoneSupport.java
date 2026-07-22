package ooo.klae.connex.backend.services;

import java.time.DateTimeException;
import java.time.ZoneId;

import ooo.klae.connex.backend.exceptions.BadRequestException;

final class TimezoneSupport {
    private TimezoneSupport() {
    }

    static String validate(String timezone, String fallback) {
        String value = timezone == null || timezone.isBlank() ? fallback : timezone.trim();
        if (value == null || value.isBlank()) {
            throw new BadRequestException("Timezone is required");
        }
        try {
            return ZoneId.of(value).getId();
        } catch (DateTimeException ex) {
            throw new BadRequestException("Invalid IANA timezone: " + value);
        }
    }

    static String validateIana(String timezone, String fallback) {
        String value = validate(timezone, fallback);
        if (!ZoneId.getAvailableZoneIds().contains(value)) {
            throw new BadRequestException("Invalid IANA timezone: " + value);
        }
        return value;
    }
}
