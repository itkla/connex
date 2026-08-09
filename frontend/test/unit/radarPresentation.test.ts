import { describe, expect, it } from 'vitest';

import {
    classifyRadarSurface,
    classifyRadarReadFailure,
    filterRadarSignals,
    replaceRadarSignal,
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

    it('distinguishes filtered no-results and replaces only the mutated signal', () => {
        const first = signal();
        const second = signal({ id: 2, subject: { type: 'person', id: 11, label: 'Grace Hopper' } });
        const current = payload([first, second]);
        const followed = { ...first, state: 'followed' as const, version: '1:1' };

        expect(classifyRadarSurface(current, [])).toBe('no_results');
        expect(replaceRadarSignal(current.items, followed)).toEqual([followed, second]);
    });
});
