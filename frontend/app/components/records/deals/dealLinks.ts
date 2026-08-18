/** Path of the deals browser — the only shipped route that lists deals. */
const DEALS_BROWSER_PATH = '/records/deals';

/**
 * URL query key the deals browser reads as its company facet. A link that wants the browser to open
 * pre-filtered to one company must emit this key with the company id, because the browser resolves a
 * facet value to a `companyId` server filter (#1338).
 */
export const DEAL_COMPANY_FILTER_KEY = 'company';

/** URL query key the deals browser reads as its pipeline facet, resolved the same way. */
export const DEAL_PIPELINE_FILTER_KEY = 'pipeline';

/**
 * Href for the deals browser pre-filtered to one company's deals.
 *
 * @param companyId - the company whose deals should be listed, or null/undefined for a contact with
 *   no company, which lands on the unfiltered browser rather than a filter that matches nothing
 */
export function companyDealsHref(companyId: number | null | undefined): string {
    if (companyId == null) return DEALS_BROWSER_PATH;
    return `${DEALS_BROWSER_PATH}?${DEAL_COMPANY_FILTER_KEY}=${companyId}`;
}

/** Href for the deals browser pre-filtered to one pipeline's deals. */
export function pipelineDealsHref(pipelineId: number): string {
    return `${DEALS_BROWSER_PATH}?${DEAL_PIPELINE_FILTER_KEY}=${pipelineId}`;
}
