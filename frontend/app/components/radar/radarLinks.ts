import type { RadarFamily } from '@/app/lib/types';
import type { RadarHorizonBand } from '@/app/components/radar/radarHorizon';
import type { RadarFamilyFilter, RadarStateFilter } from '@/app/lib/radar';

/** Path of the Radar surface — the workspace's signal triage list. */
export const RADAR_PATH = '/radar';

/** URL query key Radar reads as its signal-family filter (`RadarBoard`). */
export const RADAR_FAMILY_FILTER_KEY = 'family';

/** URL query key Radar reads as its lifecycle-state filter. */
export const RADAR_STATE_FILTER_KEY = 'state';

/** URL query key Radar reads as its subject search. */
export const RADAR_QUERY_FILTER_KEY = 'q';

/** URL query key Radar reads as the selected deadline column. */
export const RADAR_HORIZON_FILTER_KEY = 'when';

/** The complete filter state Radar owns in the browser address. */
export type RadarOwnedUrlState = {
    family: RadarFamilyFilter;
    state: RadarStateFilter;
    query: string;
    horizon: RadarHorizonBand | null;
};

/**
 * Converts Radar's filter state into the complete owned-query contract.
 *
 * Default values become undefined so shared links stay concise, while every non-default refinement
 * survives refresh and can be handed to another member unchanged.
 */
export function radarOwnedUrlParams(state: RadarOwnedUrlState): Record<string, string | undefined> {
    return {
        [RADAR_FAMILY_FILTER_KEY]: state.family === 'all' ? undefined : state.family,
        [RADAR_STATE_FILTER_KEY]: state.state === 'attention' ? undefined : state.state,
        [RADAR_QUERY_FILTER_KEY]: state.query.trim() || undefined,
        [RADAR_HORIZON_FILTER_KEY]: state.horizon ?? undefined,
    };
}

/**
 * Href for Radar pre-filtered to one family of signals, so a cooling or risk figure lands on the
 * triage rows it summarizes rather than on the unfiltered board.
 */
export function radarFamilyHref(family: RadarFamily): string {
    return `${RADAR_PATH}?${RADAR_FAMILY_FILTER_KEY}=${family}`;
}

/**
 * Href for Radar narrowed to one record's signals, in every lifecycle state — the round trip from a
 * record's Signals block, where a signal snoozed on the record must still be findable on Radar.
 *
 * @param subjectLabel - the record's name, which Radar matches against its signal subjects
 */
export function radarSubjectHref(subjectLabel: string): string {
    return `${RADAR_PATH}?${RADAR_QUERY_FILTER_KEY}=${encodeURIComponent(subjectLabel)}`
        + `&${RADAR_STATE_FILTER_KEY}=all`;
}
