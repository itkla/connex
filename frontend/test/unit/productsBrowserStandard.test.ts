import { readFileSync } from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';

import { formatEffectiveRange, formatTaxRate } from '@/app/components/records/products/productDisplay';
import type { Product } from '@/app/lib/types';

const BROWSER = 'app/components/records/products/ProductsBrowser.tsx';
const REFERENCE = 'app/components/records/contacts/ContactsBrowser.tsx';

function source(relativePath: string): string {
    return readFileSync(path.resolve(process.cwd(), relativePath), 'utf8');
}

function product(overrides: Partial<Product> = {}): Product {
    return {
        id: 1,
        name: 'Implementation',
        sku: 'IMP-1',
        description: null,
        unit: null,
        unitPrice: 1000,
        currency: 'USD',
        billingFrequency: 'one_time',
        taxRate: null,
        active: true,
        effectiveStart: null,
        effectiveEnd: null,
        ...overrides,
    } as Product;
}

const t = (key: string, values?: Record<string, string>) =>
    values ? `${key}:${Object.values(values).join('|')}` : key;

describe('the products catalog follows the Records-browser standard', () => {
    it('reuses the shared browser scaffolding rather than hand-rolling it', () => {
        const browser = source(BROWSER);

        for (const piece of [
            'useRecordsBrowser',
            'RecordsRenderView',
            'FilterBar',
            'RecordsFilterPills',
            'RecordsFilterSheet',
            'RecordsSortMenu',
            'SegmentedControl',
            'DensityToggle',
            'ColumnVisibilityMenu',
            'useColumnVisibility',
            'useRecordDensity',
        ]) {
            expect(browser, `${piece} is part of the standard the reference browsers set`).toContain(piece);
            expect(source(REFERENCE)).toContain(piece);
        }
    });

    it('leaves loading to the shared skeletons, not a searching text card', () => {
        const browser = source(BROWSER);

        expect(browser).not.toContain('searchRunning');
        expect(browser).not.toContain('searchFailed');
        expect(browser).not.toContain('retrySearch');
        expect(browser).not.toContain('<Skeleton');
    });

    it('opens a product in place, because products have no detail route', () => {
        const browser = source(BROWSER);

        expect(browser).toContain('onRowClick={editOne}');
        expect(browser).not.toContain('detailPath');
        expect(browser).not.toContain('useRecordPeekController');
    });

    it('states a tax rate as a percentage and an absent one as an em dash', () => {
        expect(formatTaxRate(product({ taxRate: 10 }), 'en')).toBe('10%');
        expect(formatTaxRate(product(), 'en')).toBe('—');
    });

    it('names an availability window by which of its ends the catalog sets', () => {
        expect(formatEffectiveRange(product(), 'en', t)).toBe('noDateLimit');
        expect(formatEffectiveRange(product({ effectiveStart: '2026-01-01' }), 'en', t)).toContain('effectiveFrom');
        expect(formatEffectiveRange(product({ effectiveEnd: '2026-12-31' }), 'en', t)).toContain('effectiveUntil');
        expect(
            formatEffectiveRange(
                product({ effectiveStart: '2026-01-01', effectiveEnd: '2026-12-31' }),
                'en',
                t,
            ),
        ).toContain('effectiveRange');
    });
});
