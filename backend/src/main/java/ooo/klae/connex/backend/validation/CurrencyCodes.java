package ooo.klae.connex.backend.validation;

import java.util.Currency;
import java.util.Locale;

import ooo.klae.connex.backend.exceptions.BadRequestException;

/** Shared ISO 4217 monetary-code validation; normalization never guesses a currency or performs FX. */
public final class CurrencyCodes {
    private CurrencyCodes() {
    }

    /** Whether a value names a supported monetary currency after trimming and case normalization. */
    public static boolean isSupported(String value) {
        if (value == null) return false;
        String code = value.trim();
        if (!code.matches("[A-Za-z]{3}")) return false;
        try {
            return Currency.getInstance(code.toUpperCase(Locale.ROOT)).getDefaultFractionDigits() >= 0;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    /** Compares existing bucket names without guessing aliases or performing conversion. */
    public static boolean equivalent(String left, String right) {
        return left != null && right != null
            && left.trim().toUpperCase(Locale.ROOT).equals(right.trim().toUpperCase(Locale.ROOT));
    }

    /** Preserves an unchanged stored value; newly supplied values must be ISO monetary codes. */
    public static String updated(String value, String stored) {
        return equivalent(value, stored) ? stored : optional(value);
    }

    /** Applies the deal creation default only to missing values, then validates explicit input. */
    public static String forDealCreation(String value) {
        return value == null || value.isBlank() ? "USD" : required(value);
    }

    /** Returns an uppercase monetary code, refusing missing, blank, and unrecognized values. */
    public static String required(String value) {
        if (!isSupported(value)) {
            throw new BadRequestException("currency must be a supported ISO 4217 monetary code");
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    /** Preserves an absent currency; a supplied value must be a supported monetary code. */
    public static String optional(String value) {
        return value == null ? null : required(value);
    }
}
