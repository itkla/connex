import type { RadarFamily } from '@/app/lib/types';

/** Path of the Radar surface — the workspace's signal triage list. */
export const RADAR_PATH = '/radar';

/** URL query key Radar reads as its signal-family filter (`RadarBoard`). */
export const RADAR_FAMILY_FILTER_KEY = 'family';

/** URL query key Radar reads as its lifecycle-state filter. */
export const RADAR_STATE_FILTER_KEY = 'state';

/**
 * Href for Radar pre-filtered to one family of signals, so a cooling or risk figure lands on the
 * triage rows it summarizes rather than on the unfiltered board.
 */
export function radarFamilyHref(family: RadarFamily): string {
    return `${RADAR_PATH}?${RADAR_FAMILY_FILTER_KEY}=${family}`;
}
