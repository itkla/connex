/**
 * Common ISO 4217 currency codes offered in pricing and approval pickers. This curated UI
 * shortlist is not exhaustive; the server owns monetary currency validation.
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

/** Effective new-deal default; absence retains USD without guessing from locale or stored deals. */
export function workspaceDefaultCurrency(workspace: { defaultCurrency?: string | null } | null | undefined): string {
    return workspace?.defaultCurrency ?? 'USD';
}
