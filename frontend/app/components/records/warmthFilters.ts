import { FILTER_EMPTY, type FilterOption, type FilterState } from '@/app/components/records/types';
import { WARMTH_BANDS } from '@/app/components/overview/analytics/relationshipMetrics';
import type { FacetCount, TemperatureBand } from '@/app/lib/types';

/** Path of the contacts browser — the only shipped route that lists contacts. */
const CONTACTS_BROWSER_PATH = '/records/contacts';

/**
 * URL and filter-state key the contact and company browsers read as their warmth-band facet. A link
 * that wants a browser to open pre-filtered to one or more bands emits this key with the band names,
 * comma-joined, because {@link import('@/app/hooks/useRecordsBrowser').useRecordsBrowser} round-trips
 * every non-reserved query param through the browser's filter state in that form.
 */
export const WARMTH_FILTER_KEY = 'warmth';

/**
 * URL and filter-state key both browsers read as the decay horizon, in whole days. It carries the
 * backend's own parameter name so a link, the browser's filter state, and the request that serves it
 * all spell the horizon the same way.
 */
export const WARMTH_HORIZON_FILTER_KEY = 'goesColdWithinDays';

/**
 * Facet key the backend counts records with no interaction history under. It is deliberately
 * disjoint from `cold`, so the band counts partition the workspace and each count predicts exactly
 * what selecting that band returns.
 */
export const WARMTH_NONE_FACET_KEY = '__none__';

/** Narrowest horizon the backend accepts; anything shorter is rejected as a bad request. */
export const WARMTH_HORIZON_MIN_DAYS = 1;

/** Widest horizon the backend accepts; anything longer is rejected as a bad request. */
export const WARMTH_HORIZON_MAX_DAYS = 3650;

/**
 * The warmth params a records request carries. Every surface that answers "which records match this
 * filter" — the page, the select-all-matching id read, and the CSV export — must be given the same
 * object, or a bulk action would reach records the list never showed.
 */
export type WarmthRequestParams = {
    warmthBands?: TemperatureBand[];
    noWarmth?: boolean;
    goesColdWithinDays?: number;
};

/**
 * Reads a filter state's warmth-band selection back as the canonical band list.
 *
 * @param state - the browser's current filter state
 * @returns the selected bands in the canonical hot→cold order, ignoring anything unrecognized
 */
export function selectedWarmthBands(state: FilterState): TemperatureBand[] {
    const selected = state[WARMTH_FILTER_KEY] ?? [];
    return WARMTH_BANDS.filter((band) => selected.includes(band));
}

/**
 * Parses the horizon a filter state carries, rejecting anything that is not a plain integer inside
 * the range the backend accepts, so a crafted `?goesColdWithinDays=0` or `=abc` never reaches a
 * fetcher that would only be answered with a 400.
 *
 * @param values - the raw filter-state values for {@link WARMTH_HORIZON_FILTER_KEY}
 * @returns the horizon in whole days, or undefined when none was requested or the value is invalid
 */
export function parseWarmthHorizon(values: readonly string[] | undefined): number | undefined {
    const raw = values?.[0];
    if (raw === undefined || !/^\d+$/.test(raw)) return undefined;
    const days = Number(raw);
    if (!Number.isSafeInteger(days)) return undefined;
    if (days < WARMTH_HORIZON_MIN_DAYS || days > WARMTH_HORIZON_MAX_DAYS) return undefined;
    return days;
}

/**
 * Derives the warmth params a records request should carry from the browser's filter state. The
 * no-history bucket is requested as `noWarmth` rather than as a band, mirroring how the lifecycle
 * and lead-source facets spell their own "none" selection.
 *
 * @param state - the browser's current filter state
 * @returns the warmth params, with every key omitted when nothing warmth-related is selected
 */
export function warmthRequestParams(state: FilterState): WarmthRequestParams {
    const params: WarmthRequestParams = {};
    const bands = selectedWarmthBands(state);
    if (bands.length) params.warmthBands = bands;
    if ((state[WARMTH_FILTER_KEY] ?? []).includes(FILTER_EMPTY)) params.noWarmth = true;
    const horizon = parseWarmthHorizon(state[WARMTH_HORIZON_FILTER_KEY]);
    if (horizon !== undefined) params.goesColdWithinDays = horizon;
    return params;
}

/**
 * Whether a filter state restricts the visible rows by warmth. A selection that does cannot be
 * expressed in the workflow engine's `filter_match` scope, which knows nothing about warmth, so the
 * browsers fall back to the explicit id set they already resolved rather than handing the engine a
 * filter that would match more records than the user selected.
 *
 * @param state - the browser's current filter state
 */
export function hasWarmthFilter(state: FilterState): boolean {
    const params = warmthRequestParams(state);
    return params.warmthBands !== undefined
        || params.noWarmth !== undefined
        || params.goesColdWithinDays !== undefined;
}

/**
 * Removes the decay horizon from a filter state, leaving every other filter untouched — what the
 * horizon's own filter chip does when it is dismissed.
 *
 * @param state - the browser's current filter state
 */
export function withoutWarmthHorizon(state: FilterState): FilterState {
    return Object.fromEntries(
        Object.entries(state).filter(([key]) => key !== WARMTH_HORIZON_FILTER_KEY),
    );
}

/**
 * Builds the warmth facet's options from the counts the backend returned, in the canonical hot→cold
 * order. A band is offered when the workspace holds records in it or when it is already selected, so
 * a shared link never silently drops a filter whose bucket has since emptied — the same rule the
 * lifecycle and lead-source facets follow.
 *
 * @param counts - the `warmthBands` facet counts, or undefined when the facet was not requested
 * @param selected - the currently selected option keys
 * @param bandLabel - resolves a band's user-facing label
 * @param noHistoryLabel - the label for records with no interaction history
 */
export function warmthFacetOptions(
    counts: readonly FacetCount[] | undefined,
    selected: readonly string[] | undefined,
    bandLabel: (band: TemperatureBand) => string,
    noHistoryLabel: string,
): FilterOption[] {
    if (!counts) return [];
    const byKey = new Map(counts.map((facet) => [facet.key, facet.count]));
    const chosen = new Set(selected ?? []);
    const options: FilterOption[] = WARMTH_BANDS
        .filter((band) => (byKey.get(band) ?? 0) > 0 || chosen.has(band))
        .map((band) => ({ key: band as string, label: bandLabel(band) }));
    if ((byKey.get(WARMTH_NONE_FACET_KEY) ?? 0) > 0 || chosen.has(FILTER_EMPTY)) {
        options.push({ key: FILTER_EMPTY, label: noHistoryLabel });
    }
    return options;
}

/**
 * Href for the contacts browser pre-filtered to the contacts predicted to go cold within a horizon,
 * so a decay figure lands on exactly the contacts it counted.
 *
 * @param days - the horizon in whole days
 */
export function warmthHorizonContactsHref(days: number): string {
    return `${CONTACTS_BROWSER_PATH}?${WARMTH_HORIZON_FILTER_KEY}=${days}`;
}
