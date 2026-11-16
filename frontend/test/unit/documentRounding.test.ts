import { describe, expect, it } from 'vitest';

import { currencyFractionDigits } from '@/app/lib/currencies';
import { documentRoundingAdjustment, documentTotalGroups } from '@/app/lib/documentRounding';
import type { DocumentContent } from '@/app/lib/types';

describe('document display rounding', () => {
    it.each([
        { amounts: [100, 101], total: 201, expected: 0 },
        { amounts: [100.5, 100.5], total: 201, expected: -1 },
        { amounts: [100.49, 100.49], total: 200.98, expected: 1 },
        { amounts: [100.5, 100.5, 100.5, 100.5, 100.5], total: 502.5, expected: -2 },
        { amounts: [100.49, 100.49, 100.49, 100.49], total: 401.96, expected: 2 },
        { amounts: [100.5], total: 100.5, expected: 0 },
        { amounts: [], total: 0, expected: 0 },
        { amounts: [-100.5, -100.5], total: -201, expected: 1 },
        { amounts: [0.1, 0.2], total: 0.3, expected: 0 },
        { amounts: [110.55, 110.55], total: 221.1, expected: -1 },
        { amounts: [100.49, 10.45], total: 110.94, expected: 1 },
        { amounts: [9999999999999998, 1], total: 10000000000000000, expected: 1 },
    ])('reconciles $amounts to $total with adjustment $expected', ({ amounts, total, expected }) => {
        expect(documentRoundingAdjustment(amounts, total, 'JPY')).toBe(expected);
    });

    it.each(['USD', 'EUR', 'KWD', 'CLF', 'YEN', 'invalid'])('leaves %s unchanged', (currency) => {
        expect(documentRoundingAdjustment([100.5, 100.5], 201, currency)).toBe(0);
    });

    it.each(['JPY', 'KRW', 'CLP', 'VND', 'XAF'])('uses ISO precision beyond the currency picker: %s', (currency) => {
        expect(currencyFractionDigits(currency)).toBe(0);
        expect(documentRoundingAdjustment([100.5, 100.5], 201, currency)).toBe(-1);
    });
});

function documentAmounts(lines: { subtotal: number; tax: number; recurring?: boolean }[], currency = 'JPY') {
    const content: Pick<DocumentContent, 'deal' | 'lineItems' | 'totals'> = {
        deal: { name: 'Quote', currency },
        lineItems: lines.map((line, id) => ({
            id, dealId: 1, name: 'Service', currency, quantity: 1, unitPrice: line.subtotal,
            billingFrequency: line.recurring ? 'recurring' : 'one_time', position: id,
            lineSubtotal: line.subtotal, lineTax: line.tax, lineTotal: line.subtotal + line.tax,
            createdAt: '', updatedAt: '',
        })),
        totals: {
            currency,
            subtotal: lines.reduce((sum, line) => sum + line.subtotal, 0),
            tax: lines.reduce((sum, line) => sum + line.tax, 0),
            oneTimeTotal: lines.filter((line) => !line.recurring).reduce((sum, line) => sum + line.subtotal + line.tax, 0),
            recurringTotal: lines.filter((line) => line.recurring).reduce((sum, line) => sum + line.subtotal + line.tax, 0),
            grandTotal: lines.reduce((sum, line) => sum + line.subtotal + line.tax, 0),
        },
    };
    return content;
}

describe('independent document breakdowns', () => {
    it.each([
        [{ subtotal: 100.5, tax: 0 }, { subtotal: 100.5, tax: 0 }],
        [{ subtotal: 100.49, tax: 0 }, { subtotal: 100.49, tax: 0 }],
        [{ subtotal: 100.49, tax: 10.45 }],
        [{ subtotal: 100.49, tax: 10.05 }, { subtotal: 100.49, tax: 10.05 }],
        [{ subtotal: 100.5, tax: 0 }, { subtotal: 100.5, tax: 0, recurring: true }],
    ])('every displayed path adds up without changing canonical breakdowns: %j', (...lines) => {
        const content = documentAmounts(lines);
        const original = JSON.stringify(content);
        const groups = documentTotalGroups(content);
        expect(groups.length).toBeGreaterThan(1);
        const displayed = (value: number | bigint) => Number(new Intl.NumberFormat('en', {
            maximumFractionDigits: 0, useGrouping: false,
        }).format(value));
        for (const group of groups) {
            const total = group.rows.at(-1);
            expect(total?.label).toBe('grandTotal');
            expect(group.rows.slice(0, -1).reduce((sum, row) => sum + displayed(row.value), 0))
                .toBe(displayed(content.totals.grandTotal));
            const adjustment = group.rows.find((row) => row.label === 'roundingAdjustment');
            if (adjustment) {
                expect(group.rows.at(-2)).toBe(adjustment);
                expect(adjustment.signed).toBe(true);
                expect(adjustment.value).not.toBe(0);
            }
        }
        expect(groups[0].rows[0].value).toBe(content.totals.subtotal);
        expect(groups[0].rows[1].value).toBe(content.totals.tax);
        expect(JSON.stringify(content)).toBe(original);
    });

    it('reconciles subtotal and tax even when the inclusive item already matches the grand total', () => {
        const groups = documentTotalGroups(documentAmounts([{ subtotal: 100.49, tax: 10.45 }]));
        expect(groups[0].rows).toContainEqual({ label: 'roundingAdjustment', value: 1, signed: true });
        expect(groups.at(-1)?.rows.some((row) => row.label === 'roundingAdjustment')).toBe(false);
    });

    it('reconciles the canonical recurring breakdown independently', () => {
        const content = documentAmounts([{ subtotal: 100.5, tax: 0 }, { subtotal: 100.5, tax: 0, recurring: true }]);
        const group = documentTotalGroups(content).find((group) => group.label === 'billingBreakdown');
        expect(group?.rows).toEqual([
            { label: 'oneTimeTotal', value: 100.5, muted: true },
            { label: 'recurringTotal', value: 100.5, muted: true },
            { label: 'roundingAdjustment', value: -1, signed: true },
            { label: 'grandTotal', value: 201, emphasis: false },
        ]);
    });

    it('preserves an exact displayed item sum beyond safe integer precision', () => {
        const groups = documentTotalGroups(documentAmounts([{ subtotal: 9999999999999998, tax: 0 }, { subtotal: 1, tax: 0 }]));
        expect(groups.at(-1)?.rows[0]).toEqual({ label: 'displayedItemsTotal', value: BigInt('9999999999999999') });
        expect(groups.at(-1)?.rows.at(-2)).toEqual({ label: 'roundingAdjustment', value: 1, signed: true });
    });

    it.each(['JPY', 'USD'])('keeps the existing layout when %s rows and summaries agree', (currency) => {
        expect(documentTotalGroups(documentAmounts([{ subtotal: 100, tax: 10 }], currency))).toEqual([{
            rows: [
                { label: 'subtotal', value: 100 }, { label: 'tax', value: 10 },
                { label: 'grandTotal', value: 110, emphasis: true },
            ],
        }]);
    });

    it('does not reconcile USD even when values have more than two decimals', () => {
        const groups = documentTotalGroups(documentAmounts([{ subtotal: 0.005, tax: 0 }, { subtotal: 0.005, tax: 0 }], 'USD'));
        expect(groups).toHaveLength(1);
        expect(groups[0].label).toBeUndefined();
        expect(groups[0].rows.some((row) => row.label === 'roundingAdjustment')).toBe(false);
    });
});
