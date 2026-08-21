import type { RadarFamily, RadarSubject, RadarSubjectType } from '@/app/lib/types';
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

/** URL query key Radar reads as an exact subject refinement from a record page. */
export const RADAR_SUBJECT_FILTER_KEY = 'subject';

/** Exact record identity carried by a record-to-Radar link without becoming visible search copy. */
export type RadarSubjectFilter = Pick<RadarSubject, 'type' | 'id'>;

/** The complete filter state Radar owns in the browser address. */
export type RadarOwnedUrlState = {
    family: RadarFamilyFilter;
    state: RadarStateFilter;
    query: string;
    horizon: RadarHorizonBand | null;
    subject: RadarSubjectFilter | null;
};

/** Parses an exact subject refinement from the URL, rejecting unknown types and invalid ids. */
export function parseRadarSubjectFilter(value: string | null): RadarSubjectFilter | null {
    if (value === null) return null;
    const [type, idText, extra] = value.split(':');
    const id = Number(idText);
    const validType: RadarSubjectType | null = type === 'person' || type === 'company' || type === 'deal'
        ? type
        : null;
    return extra === undefined
        && validType !== null
        && typeof idText === 'string'
        && /^[1-9]\d*$/.test(idText)
        && Number.isSafeInteger(id)
        ? { type: validType, id }
        : null;
}

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
        [RADAR_SUBJECT_FILTER_KEY]: state.subject === null
            ? undefined
            : `${state.subject.type}:${state.subject.id}`,
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
 * @param subject - the exact record identity plus the safe localized label shown in Radar's search
 */
export function radarSubjectHref(subject: RadarSubject): string {
    return `${RADAR_PATH}?${RADAR_QUERY_FILTER_KEY}=${encodeURIComponent(subject.label)}`
        + `&${RADAR_STATE_FILTER_KEY}=all`
        + `&${RADAR_SUBJECT_FILTER_KEY}=${subject.type}%3A${subject.id}`;
}
