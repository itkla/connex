import type {
    RadarFamily,
    RadarFamilyState,
    RadarLifecycleState,
    RadarPayload,
    RadarSignal,
} from '@/app/lib/types';

export const RADAR_FAMILIES = ['all', 'relationship_decay', 'deal_risk', 'warm_path'] as const;
export const RADAR_STATES = ['attention', 'active', 'followed', 'snoozed', 'dismissed', 'all'] as const;

export type RadarFamilyFilter = (typeof RADAR_FAMILIES)[number];
export type RadarStateFilter = (typeof RADAR_STATES)[number];

export type RadarFilters = {
    family: RadarFamilyFilter;
    state: RadarStateFilter;
    query: string;
};

export type RadarSurfaceState = 'ready' | 'empty' | 'no_results' | 'partial' | 'unavailable';
export type RadarReadFailureState = 'unauthenticated' | 'denied' | 'unavailable';

export function classifyRadarReadFailure(status: number): RadarReadFailureState {
    if (status === 401) return 'unauthenticated';
    if (status === 403) return 'denied';
    return 'unavailable';
}

export function isRadarFamilyFilter(value: string | null): value is RadarFamilyFilter {
    return value !== null && RADAR_FAMILIES.some((candidate) => candidate === value);
}

export function isRadarStateFilter(value: string | null): value is RadarStateFilter {
    return value !== null && RADAR_STATES.some((candidate) => candidate === value);
}

function matchesState(state: RadarLifecycleState, filter: RadarStateFilter): boolean {
    if (filter === 'all') return true;
    if (filter === 'attention') return state === 'active' || state === 'followed';
    return state === filter;
}

function searchableText(signal: RadarSignal): string {
    return [
        signal.subject.label,
        signal.family,
        signal.priority,
        ...signal.evidence.flatMap((evidence) => [
            evidence.type,
            ...evidence.references.map((reference) => `${reference.type} ${reference.id}`),
        ]),
        ...signal.rank.factors.map((factor) => factor.key),
    ].join(' ').toLocaleLowerCase();
}

/** Applies the shareable Radar filters without changing the backend's canonical rank order. */
export function filterRadarSignals(signals: readonly RadarSignal[], filters: RadarFilters): RadarSignal[] {
    const query = filters.query.trim().toLocaleLowerCase();
    return signals.filter((signal) => (
        (filters.family === 'all' || signal.family === filters.family)
        && matchesState(signal.state, filters.state)
        && (query.length === 0 || searchableText(signal).includes(query))
    ));
}

export function unavailableRadarFamilies(families: readonly RadarFamilyState[]): RadarFamily[] {
    return families
        .filter((family) => family.status === 'unavailable')
        .map((family) => family.family);
}

/** Classifies route data into honest empty, filtered, partial, and unavailable states. */
export function classifyRadarSurface(
    payload: RadarPayload,
    visibleSignals: readonly RadarSignal[],
): RadarSurfaceState {
    const unavailable = unavailableRadarFamilies(payload.families);
    if (payload.families.length > 0 && unavailable.length === payload.families.length) return 'unavailable';
    if (payload.items.length === 0) return unavailable.length > 0 ? 'partial' : 'empty';
    if (visibleSignals.length === 0) return 'no_results';
    return unavailable.length > 0 ? 'partial' : 'ready';
}

export function replaceRadarSignal(signals: readonly RadarSignal[], replacement: RadarSignal): RadarSignal[] {
    return signals.map((signal) => signal.id === replacement.id ? replacement : signal);
}
