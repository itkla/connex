import { describe, expect, it } from 'vitest';

import {
    classifyRadarSurface,
    classifyRadarReadFailure,
    createRadarTaskSignalStore,
    filterRadarSignals,
    groupRadarSignalsByBand,
    isRadarEvidenceStale,
    radarEvidenceRefreshDelay,
    radarFamilyCounts,
    replaceRadarSignal,
    submitRadarTaskWithCurrentSignal,
} from '@/app/lib/radar';
import type { RadarFamilyState, RadarPayload, RadarSignal } from '@/app/lib/types';

function signal(overrides: Partial<RadarSignal> = {}): RadarSignal {
    return {
        id: 1,
        family: 'relationship_decay',
        subject: { type: 'person', id: 10, label: 'Ada Lovelace' },
        priority: 'cooling',
        state: 'active',
        snoozeUntil: null,
        taskId: null,
        version: '1:0',
        evidenceAsOf: '2026-08-08T12:00:00Z',
        stale: false,
        evidence: [{
            type: 'relationship_temperature',
            parameters: { trend: 'cooling' },
            references: [{ type: 'person', id: 10 }],
        }],
        rank: {
            position: 1,
            rule: 'priority_then_source_strength_then_subject',
            factors: [{ key: 'priority', direction: 'ascending', value: 'high' }],
        },
        ...overrides,
    };
}

const availableFamilies: RadarFamilyState[] = [
    { family: 'relationship_decay', status: 'available' },
    { family: 'deal_risk', status: 'available' },
    { family: 'warm_path', status: 'available' },
];

function payload(items: RadarSignal[], families = availableFamilies): RadarPayload {
    return {
        items,
        families,
        counts: { total: items.length },
        asOf: '2026-08-08T12:00:00Z',
        partialFailure: families.some((family) => family.status === 'unavailable'),
    };
}

describe('Radar presentation state', () => {
    it('keeps expired sessions, permission denial, and service failure distinct', () => {
        expect(classifyRadarReadFailure(401)).toBe('unauthenticated');
        expect(classifyRadarReadFailure(403)).toBe('denied');
        expect(classifyRadarReadFailure(503)).toBe('unavailable');
    });

    it('marks evidence stale only after the live fifteen-minute threshold', () => {
        const evidenceAsOf = '2026-08-08T12:00:00Z';
        const evidenceTime = Date.parse(evidenceAsOf);

        expect(isRadarEvidenceStale(evidenceAsOf, evidenceTime + 15 * 60 * 1000)).toBe(false);
        expect(isRadarEvidenceStale(evidenceAsOf, evidenceTime + 15 * 60 * 1000 + 1)).toBe(true);
        expect(isRadarEvidenceStale('not-a-date', evidenceTime + 16 * 60 * 1000)).toBe(false);
    });

    it('refreshes early enough for the backend to own the exact stale decision', () => {
        const asOf = '2026-08-08T12:00:00Z';

        expect(radarEvidenceRefreshDelay(asOf, asOf, 750)).toBe(15 * 60 * 1000 - 749);
        expect(radarEvidenceRefreshDelay(asOf, asOf, -10)).toBe(15 * 60 * 1000 + 1);
        expect(radarEvidenceRefreshDelay('not-a-date', asOf, 750)).toBeNaN();
    });

    it('submits with the refreshed version after an open dialog signal changes', async () => {
        const signalState = createRadarTaskSignalStore(signal());
        const submittedVersions: string[] = [];
        signalState.refresh(signal({ version: '2:0' }), 'current');

        await expect(submitRadarTaskWithCurrentSignal(signalState, async (version) => {
            submittedVersions.push(version);
            return version;
        })).resolves.toBe('2:0');
        expect(submittedVersions).toEqual(['2:0']);

        signalState.refresh(signal({ version: '3:0', stale: true }), 'current');
        await expect(submitRadarTaskWithCurrentSignal(signalState, async (version) => {
            submittedVersions.push(version);
            return version;
        })).rejects.toThrow('Radar task signal is not current');
        expect(submittedVersions).toEqual(['2:0']);
    });

    it('keeps backend rank order while applying family, state, and query filters', () => {
        const signals = [
            signal({ id: 1 }),
            signal({
                id: 2,
                family: 'deal_risk',
                subject: { type: 'deal', id: 20, label: 'Apollo renewal' },
                state: 'followed',
            }),
            signal({ id: 3, state: 'dismissed' }),
        ];

        expect(filterRadarSignals(signals, {
            family: 'all',
            state: 'attention',
            query: 'apollo',
        }).map((item) => item.id)).toEqual([2]);
        expect(filterRadarSignals(signals, {
            family: 'all',
            state: 'attention',
            query: '',
        }).map((item) => item.id)).toEqual([1, 2]);
    });

    it('does not present a failed family read as a true empty Radar', () => {
        const partial = payload([], [
            { family: 'relationship_decay', status: 'available' },
            { family: 'deal_risk', status: 'unavailable', errorCode: 'source_unavailable' },
            { family: 'warm_path', status: 'available' },
        ]);
        const unavailable = payload([], availableFamilies.map((family) => ({
            ...family,
            status: 'unavailable' as const,
        })));

        expect(classifyRadarSurface(partial, [])).toBe('partial');
        expect(classifyRadarSurface(unavailable, [])).toBe('unavailable');
        expect(classifyRadarSurface(payload([], availableFamilies), [])).toBe('empty');
    });

    it('groups signals into triage bands without disturbing the backend rank order', () => {
        const signals = [
            signal({ id: 1, priority: 'high' }),
            signal({ id: 2, priority: 'opportunity' }),
            signal({ id: 3, priority: 'cooling' }),
            signal({ id: 4, priority: 'high' }),
            signal({ id: 5, priority: 'medium' }),
        ];

        expect(groupRadarSignalsByBand(signals).map((group) => [group.band, group.signals.map((item) => item.id)]))
            .toEqual([['now', [1, 4]], ['soon', [3, 5]], ['later', [2]]]);
        expect(groupRadarSignalsByBand([signal({ priority: 'high' })]).map((group) => group.band))
            .toEqual(['now']);
        expect(groupRadarSignalsByBand([])).toEqual([]);
    });

    it('counts what each family chip would actually yield under the current state and query', () => {
        const signals = [
            signal({ id: 1 }),
            signal({
                id: 2,
                family: 'deal_risk',
                subject: { type: 'deal', id: 20, label: 'Apollo renewal' },
            }),
            signal({ id: 3, family: 'warm_path', state: 'dismissed' }),
            signal({ id: 4, family: 'deal_risk', state: 'followed' }),
        ];

        expect(radarFamilyCounts(signals, { state: 'attention', query: '' }))
            .toEqual({ all: 3, relationship_decay: 1, deal_risk: 2, warm_path: 0 });
        expect(radarFamilyCounts(signals, { state: 'attention', query: 'apollo' }))
            .toEqual({ all: 1, relationship_decay: 0, deal_risk: 1, warm_path: 0 });
        expect(radarFamilyCounts(signals, { state: 'all', query: '' }))
            .toEqual({ all: 4, relationship_decay: 1, deal_risk: 2, warm_path: 1 });
    });

    it('distinguishes filtered no-results and replaces only the mutated signal', () => {
        const first = signal();
        const second = signal({ id: 2, subject: { type: 'person', id: 11, label: 'Grace Hopper' } });
        const current = payload([first, second]);
        const followed = { ...first, state: 'followed' as const, version: '1:1' };

        expect(classifyRadarSurface(current, [])).toBe('no_results');
        expect(replaceRadarSignal(current.items, followed)).toEqual([followed, second]);
    });
});
