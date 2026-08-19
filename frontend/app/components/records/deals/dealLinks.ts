import { recordDetailSectionId } from '@/app/components/records/recordDetailGrammar';

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

/** URL query key the deals browser reads as its stage facet, resolved the same way. */
export const DEAL_STAGE_FILTER_KEY = 'stage';

/** URL query key the deals browser reads as its risk facet — the severities the engine assigned. */
export const DEAL_RISK_FILTER_KEY = 'risk';

/** URL query key the deals browser reads as its open/closed facet. */
export const DEAL_STATUS_FILTER_KEY = 'status';

/** The risk severities a drill-through can select, in the order the browser lists them. */
export const DEAL_RISK_LEVELS = ['high', 'medium', 'low'] as const;

/** One risk severity a deals drill-through can filter by. */
export type DealRiskFilterLevel = (typeof DEAL_RISK_LEVELS)[number];

/**
 * Href for the deals browser pre-filtered to one company's deals.
 *
 * @param companyId - the company whose deals should be listed, or null/undefined for a contact with
 *   no company, which yields no link at all — an unfiltered browser would show the whole workspace's
 *   deals under a tile that counted only this record's
 */
export function companyDealsHref(companyId: number | null | undefined): string | undefined {
    if (companyId == null) return undefined;
    return `${DEALS_BROWSER_PATH}?${DEAL_COMPANY_FILTER_KEY}=${companyId}`;
}

/** Href for the deals browser pre-filtered to one pipeline's deals. */
export function pipelineDealsHref(pipelineId: number): string {
    return `${DEALS_BROWSER_PATH}?${DEAL_PIPELINE_FILTER_KEY}=${pipelineId}`;
}

/**
 * Href for the deals browser pre-filtered to the deals the risk engine flagged at the given
 * severities, so a risk figure lands on exactly the deals it counted.
 *
 * @param levels - the severities to select; an empty list would mean "every deal", so it yields the
 *   whole flagged set instead of an unfiltered browser
 */
export function riskDealsHref(levels: readonly DealRiskFilterLevel[]): string {
    const selected = levels.length > 0 ? levels : DEAL_RISK_LEVELS;
    return `${DEALS_BROWSER_PATH}?${DEAL_RISK_FILTER_KEY}=${selected.join(',')}`;
}

/**
 * Href for the deals browser pre-filtered to one pipeline stage's open deals — the set a funnel bar
 * measures. Closed deals are excluded because a funnel counts only what is still in flight.
 */
export function stageDealsHref(pipelineId: number, stageId: number): string {
    return `${DEALS_BROWSER_PATH}?${DEAL_PIPELINE_FILTER_KEY}=${pipelineId}`
        + `&${DEAL_STAGE_FILTER_KEY}=${stageId}`
        + `&${DEAL_STATUS_FILTER_KEY}=open`;
}

/**
 * Href for a generated document's canonical surface. A generated document has no page of its own —
 * it is authored, approved, and superseded inside its parent deal — so every surface that finds one
 * without its deal (global search, the library index) lands on that deal's documents section rather
 * than inventing a destination.
 *
 * @param dealId - the parent deal the document belongs to
 */
export function dealDocumentsHref(dealId: number): string {
    return `${DEALS_BROWSER_PATH}/${dealId}#${recordDetailSectionId('deal', 'files')}`;
}
