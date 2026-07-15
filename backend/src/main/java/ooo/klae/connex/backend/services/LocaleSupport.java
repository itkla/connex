package ooo.klae.connex.backend.services;

import java.util.Locale;
import java.util.Set;

import ooo.klae.connex.backend.exceptions.BadRequestException;

final class LocaleSupport {
    private static final String DEFAULT_LOCALE = "en";
    private static final Set<String> SUPPORTED_LOCALES = Set.of(DEFAULT_LOCALE, "ja");

    private LocaleSupport() {
    }

    static String validate(String locale, String fallback) {
        String value = locale == null || locale.isBlank() ? fallback : locale;
        if (value == null || value.isBlank()) {
            throw new BadRequestException("Locale is required");
        }
        if (!SUPPORTED_LOCALES.contains(value)) {
            throw new BadRequestException("Unsupported locale: " + value);
        }
        return value;
    }

    static Locale resolve(String locale) {
        return "ja".equals(locale)
                ? Locale.JAPANESE
                : Locale.ENGLISH;
    }
}
