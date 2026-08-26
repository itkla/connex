'use client';

import { useLocale, useTranslations } from 'next-intl';

import { formatCurrency } from '@/app/lib/utils';
import { formatEffectiveRange, formatTaxRate } from '@/app/components/records/products/productDisplay';
import type { Product } from '@/app/lib/types';

/**
 * A product as one row of the viewport-forced mobile list: the name over the catalog facts a phone
 * user still has to be able to read — SKU, billing, tax, and the availability window — with the
 * price trailing, because price is what a catalog list is ranked by.
 *
 * Everything renders as phrasing content: the shared list row is a `<button>`, so this may not
 * introduce block elements or anything else interactive. Availability leads the secondary line
 * rather than sitting in a pill, since an inactive product is the fact that changes what the row
 * means and it must survive truncation.
 */
export default function ProductListRow({ product }: { product: Product }) {
    const t = useTranslations('ProductsBrowser');
    const locale = useLocale();

    const secondary = [
        product.active ? null : t('inactive'),
        product.sku || null,
        product.billingFrequency === 'recurring' ? t('recurring') : t('oneTime'),
        product.taxRate == null ? null : formatTaxRate(product, locale),
        formatEffectiveRange(product, locale, t),
    ].filter((part): part is string => Boolean(part)).join(' · ');

    return (
        <span className="flex min-w-0 items-center gap-2">
            <span className="min-w-0 flex-1">
                <span className="flex min-w-0 items-center gap-1.5">
                    <span className="truncate text-sm font-medium text-foreground">{product.name}</span>
                    {product.active ? null : (
                        <span className="shrink-0 rounded-full bg-muted px-2 py-0.5 text-[10px] font-medium tracking-wider text-muted-foreground uppercase">
                            {t('inactive')}
                        </span>
                    )}
                </span>
                <span className="mt-0.5 block truncate text-xs text-muted-foreground">{secondary}</span>
            </span>
            <span className="shrink-0 text-right">
                <span className="block text-sm font-semibold tabular-nums text-foreground">
                    {formatCurrency(product.unitPrice, product.currency, locale)}
                </span>
                {product.unit ? (
                    <span className="block text-xs text-muted-foreground">
                        {t('perUnit', { unit: product.unit })}
                    </span>
                ) : null}
            </span>
        </span>
    );
}
