import { formatDate } from '@/app/lib/utils';
import type { Product } from '@/app/lib/types';

/** Localized copy lookup, as `useTranslations('ProductsBrowser')` returns it. */
type ProductTranslator = (key: string, values?: Record<string, string>) => string;

/** The product's tax rate as a percentage, or an em dash when the catalog does not set one. */
export function formatTaxRate(product: Product, locale: string): string {
    return product.taxRate == null
        ? '—'
        : `${new Intl.NumberFormat(locale).format(product.taxRate)}%`;
}

/** When the product can be sold, stated as a range, an open end, or no limit at all. */
export function formatEffectiveRange(
    product: Product,
    locale: string,
    t: ProductTranslator,
): string {
    const start = product.effectiveStart ? formatDate(product.effectiveStart, locale) : null;
    const end = product.effectiveEnd ? formatDate(product.effectiveEnd, locale) : null;
    if (start && end) return t('effectiveRange', { start, end });
    if (start) return t('effectiveFrom', { date: start });
    if (end) return t('effectiveUntil', { date: end });
    return t('noDateLimit');
}
