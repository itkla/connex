package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Locale;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.exceptions.BadRequestException;

class LocaleSupportTest {
    @Test
    void validationAcceptsOnlyExactSupportedLocales() {
        assertEquals("en", LocaleSupport.validate("en", null));
        assertEquals("ja", LocaleSupport.validate("ja", null));
        assertEquals("en", LocaleSupport.validate(null, "en"));
        assertThrows(BadRequestException.class, () -> LocaleSupport.validate(null, null));
        assertThrows(BadRequestException.class, () -> LocaleSupport.validate("fr", null));
        assertThrows(BadRequestException.class, () -> LocaleSupport.validate("EN", null));
        assertThrows(BadRequestException.class, () -> LocaleSupport.validate("en-US", null));
        assertThrows(BadRequestException.class, () -> LocaleSupport.validate(" en ", null));
    }

    @Test
    void resolutionFallsBackToEnglishForUntrustedValues() {
        assertEquals(Locale.JAPANESE, LocaleSupport.resolve("ja"));
        assertEquals(Locale.ENGLISH, LocaleSupport.resolve("en"));
        assertEquals(Locale.ENGLISH, LocaleSupport.resolve(null));
        assertEquals(Locale.ENGLISH, LocaleSupport.resolve("../../ja"));
    }
}
