package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.exceptions.BadRequestException;

class TimezoneSupportTest {

    @Test
    void validatesZoneAndUsesRegistrationFallback() {
        assertEquals("Asia/Tokyo", TimezoneSupport.validate("Asia/Tokyo", "UTC"));
        assertEquals("UTC", TimezoneSupport.validate(null, "UTC"));
        assertThrows(
            BadRequestException.class,
            () -> TimezoneSupport.validate("Mars/Olympus_Mons", "UTC")
        );
    }
}