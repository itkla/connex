package ooo.klae.connex.backend.validation;

import java.util.Locale;

import ooo.klae.connex.backend.exceptions.BadRequestException;

/** Supported workspace defaults, matching the catalog currency picker. */
public final class WorkspaceCurrencyCodes {
    public static final String PATTERN = "USD|JPY|EUR|GBP|AUD|CAD|CHF|CNY|HKD|SGD|KRW|INR|NZD|SEK|NOK|DKK|MXN|BRL|ZAR|AED";

    private WorkspaceCurrencyCodes() {
    }

    /** Normalizes an explicit setting without guessing aliases or converting existing amounts. */
    public static String required(String value) {
        String code = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!code.matches(PATTERN)) {
            throw new BadRequestException("defaultCurrency must be a supported currency code");
        }
        return code;
    }
}
