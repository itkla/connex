import { currencyFractionDigits } from '@/app/lib/currencies';
import { getNumberFormatter } from '@/app/lib/intlFormatters';
import type { DocumentContent } from '@/app/lib/types';

type TotalRow = {
    label: 'subtotal' | 'tax' | 'oneTimeTotal' | 'recurringTotal' | 'grandTotal' | 'roundingAdjustment' | 'displayedItemsTotal';
    value: number | bigint;
    signed?: boolean;
    emphasis?: boolean;
    muted?: boolean;
};

type TotalGroup = {
    label?: 'subtotalTaxBreakdown' | 'billingBreakdown' | 'itemAmountsBreakdown';
    rows: TotalRow[];
};

function displayedInteger(amount: number): bigint {
    return BigInt(getNumberFormatter('en', { maximumFractionDigits: 0, useGrouping: false }).format(amount));
}

/**
 * Reconciles displayed rows to a displayed total for currencies without minor units. Rounding
 * matches formatCurrency (including negative ties). Displayed integer sums use bigint to avoid
 * losing units on large documents; canonical amounts are never recomputed.
 */
export function documentRoundingAdjustment(amounts: readonly number[], total: number, currency: string): number {
    if (amounts.length === 0 || currencyFractionDigits(currency) !== 0) return 0;
    const displayedTotal = displayedInteger(total);
    const displayedSum = amounts.reduce((sum, amount) => sum + displayedInteger(amount), BigInt(0));
    return Number(displayedTotal - displayedSum);
}

/**
 * Preserves canonical breakdown values, separating additive paths only when display rounding
 * requires reconciliation. Item amounts already include tax, so their adjustment must not be
 * added to the subtotal/tax or billing breakdown. Each adjustment immediately precedes its total.
 */
export function documentTotalGroups(content: Pick<DocumentContent, 'deal' | 'lineItems' | 'totals'>): TotalGroup[] {
    const { totals } = content;
    const currency = content.deal.currency;
    const itemAdjustment = documentRoundingAdjustment(content.lineItems.map((item) => item.lineTotal), totals.grandTotal, currency);
    const taxAdjustment = documentRoundingAdjustment([totals.subtotal, totals.tax], totals.grandTotal, currency);
    const billingAdjustment = documentRoundingAdjustment([totals.oneTimeTotal, totals.recurringTotal], totals.grandTotal, currency);
    const hasBillingBreakdown = totals.recurringTotal > 0;
    const subtotalTax: TotalRow[] = [
        { label: 'subtotal', value: totals.subtotal },
        { label: 'tax', value: totals.tax },
    ];
    const billing: TotalRow[] = hasBillingBreakdown ? [
        { label: 'oneTimeTotal', value: totals.oneTimeTotal, muted: true },
        { label: 'recurringTotal', value: totals.recurringTotal, muted: true },
    ] : [];
    const grandTotal: TotalRow = { label: 'grandTotal', value: totals.grandTotal, emphasis: true };
    if (itemAdjustment === 0 && taxAdjustment === 0 && (!hasBillingBreakdown || billingAdjustment === 0)) {
        return [{ rows: [...subtotalTax, ...billing, grandTotal] }];
    }
    const adjustmentRows = (value: number): TotalRow[] => value === 0
        ? [] : [{ label: 'roundingAdjustment', value, signed: true }];
    return [
        {
            label: 'subtotalTaxBreakdown',
            rows: [...subtotalTax, ...adjustmentRows(taxAdjustment), { ...grandTotal, emphasis: false }],
        },
        ...(hasBillingBreakdown ? [{
            label: 'billingBreakdown' as const,
            rows: [...billing, ...adjustmentRows(billingAdjustment), { ...grandTotal, emphasis: false }],
        }] : []),
        {
            label: 'itemAmountsBreakdown',
            rows: [
                { label: 'displayedItemsTotal', value: displayedInteger(totals.grandTotal) - BigInt(itemAdjustment) },
                ...adjustmentRows(itemAdjustment), grandTotal,
            ],
        },
    ];
}
