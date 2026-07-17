/**
 * Common ISO 4217 currency codes offered in catalog/pricing pickers. Not exhaustive — the
 * backend accepts any code up to 8 chars; this is the curated shortlist for the UI.
 */
export const CURRENCY_CODES = [
    'USD', 'JPY', 'EUR', 'GBP', 'AUD', 'CAD', 'CHF', 'CNY', 'HKD', 'SGD',
    'KRW', 'INR', 'NZD', 'SEK', 'NOK', 'DKK', 'MXN', 'BRL', 'ZAR', 'AED',
] as const;

export type CurrencyCode = (typeof CURRENCY_CODES)[number];

/** Whether a code is one of the curated shortlist (for defaulting the picker). */
export function isKnownCurrency(code: string): code is CurrencyCode {
    return (CURRENCY_CODES as readonly string[]).includes(code);
}
