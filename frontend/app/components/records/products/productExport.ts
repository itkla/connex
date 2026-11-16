import type { FilterState } from '@/app/components/records/types';
import type { ProductExportParams } from '@/app/lib/types';

/** Filter-state key of the products browser's Billing facet; also the column key. */
export const PRODUCT_BILLING_FILTER_KEY = 'billingFrequency';

/** Filter-state key of the products browser's Availability facet; also the column key. */
export const PRODUCT_AVAILABILITY_FILTER_KEY = 'active';

/** Filter-state key of the products browser's Effective dates facet; also the column key. */
export const PRODUCT_EFFECTIVE_DATES_FILTER_KEY = 'effectiveStart';

/** Effective dates facet value for a product with a start date, an end date, or both. */
export const PRODUCT_BOUNDED_FACET_KEY = 'bounded';

/**
 * The `GET /api/exports/products` params for exactly what the products browser lists: its search
 * plus the Billing, Availability, and Effective dates facets. Facet keys are sent exactly as the
 * browser matches them, including the shared empty-value key for "No date limit". The server
 * accepts only keys the browser itself produces, so a hand-edited URL key that matches no product
 * here fails the export instead of silently widening it beyond the view.
 *
 * @param query - the browser's raw search text
 * @param filterState - the browser's facet selections
 * @returns the export params, omitting every facet with no selection
 */
export function productExportParams(query: string, filterState: FilterState): ProductExportParams {
    const params: ProductExportParams = {};
    const trimmed = query.trim();
    if (trimmed) params.q = trimmed;
    const billing = filterState[PRODUCT_BILLING_FILTER_KEY] ?? [];
    if (billing.length) params.billingFrequency = billing;
    const availability = filterState[PRODUCT_AVAILABILITY_FILTER_KEY] ?? [];
    if (availability.length) params.active = availability;
    const dates = filterState[PRODUCT_EFFECTIVE_DATES_FILTER_KEY] ?? [];
    if (dates.length) params.effectiveDates = dates;
    return params;
}
