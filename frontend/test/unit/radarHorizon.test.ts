import { describe, expect, it } from 'vitest';

import {
    RADAR_HORIZON_BANDS,
    RADAR_HORIZON_MARK_LIMIT,
    isRadarHorizonBand,
    radarConnectors,
    radarDecayFacts,
    radarDecaySummary,
    radarHorizonColumns,
    radarHorizonPlacement,
    radarMarkTone,
    radarPathBridges,
    radarRiskReasons,
} from '@/app/components/radar/radarHorizon';
import {
    radarRecordHref,
    radarReferenceLinks,
    radarSignalNames,
    radarSubjectRecordHref,
} from '@/app/components/radar/radarReferences';
import {
    RADAR_FAMILY_FILTER_KEY,
    RADAR_HORIZON_FILTER_KEY,
    RADAR_QUERY_FILTER_KEY,
    RADAR_STATE_FILTER_KEY,
    radarFamilyHref,
    radarOwnedUrlParams,
} from '@/app/components/radar/radarLinks';
import { radarRecordLabel } from '@/app/components/radar/radarLabels';
import { resolveShippedRoute } from '@/app/lib/routeManifest';
import type { RadarEvidence, RadarSignal } from '@/app/lib/types';

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
        evidence: [],
        rank: {
            position: 1,
            rule: 'priority_then_source_strength_then_subject',
            factors: [],
        },
        ...overrides,
    };
}

function decay(parameters: Record<string, unknown>, overrides: Partial<RadarSignal> = {}): RadarSignal {
    return signal({
        evidence: [{
            type: 'relationship_temperature',
            parameters,
            references: [{ type: 'person', id: overrides.subject?.id ?? 10 }],
        }],
        ...overrides,
    });
}

function risk(evidence: RadarEvidence[], overrides: Partial<RadarSignal> = {}): RadarSignal {
    return signal({
        family: 'deal_risk',
        subject: { type: 'deal', id: 20, label: 'Apollo renewal' },
        priority: 'high',
        evidence,
        ...overrides,
    });
}

function path(
    bridges: { id: number; name: string }[],
    overrides: Partial<RadarSignal> = {},
): RadarSignal {
    return signal({
        family: 'warm_path',
        priority: 'opportunity',
        evidence: bridges.map((bridge) => ({
            type: 'warm_path',
            parameters: {
                bridgePersonId: bridge.id,
                bridgeName: bridge.name,
                reachType: 'reach',
                pathScore: 40,
            },
            references: [
                { type: 'person', id: overrides.subject?.id ?? 10 },
                { type: 'person', id: bridge.id },
            ],
        })),
        ...overrides,
    });
}

describe('the Radar horizon', () => {
    it('places a cooling relationship by the days left before it goes cold', () => {
        expect(radarHorizonPlacement(decay({ band: 'cool', daysUntilCold: 3 })))
            .toEqual({ band: 'week', days: 3 });
        expect(radarHorizonPlacement(decay({ band: 'cool', daysUntilCold: 7 })).band).toBe('week');
        expect(radarHorizonPlacement(decay({ band: 'cool', daysUntilCold: 8 })).band).toBe('month');
        expect(radarHorizonPlacement(decay({ band: 'warm', daysUntilCold: 31 })).band).toBe('later');
        expect(radarHorizonPlacement(decay({ band: 'cool', daysUntilCold: -1 })).band).toBe('overdue');
    });

    it('treats a relationship that already reads cold as spent, dated or not', () => {
        expect(radarHorizonPlacement(decay({ band: 'cold' })).band).toBe('overdue');
        expect(radarHorizonPlacement(decay({ band: 'cold', daysUntilCold: 12 })).band).toBe('overdue');
    });

    it('leaves an undated signal undated rather than inventing a position for it', () => {
        expect(radarHorizonPlacement(decay({ band: 'cool' }))).toEqual({ band: 'undated', days: null });
        expect(radarHorizonPlacement(path([{ id: 7, name: 'Kenji Sato' }])))
            .toEqual({ band: 'undated', days: null });
        expect(radarHorizonPlacement(risk([
            { type: 'no_stakeholders', parameters: { severity: 'low' }, references: [] },
        ])).band).toBe('undated');
    });

    it('places a deal by its most urgent dated reason', () => {
        const overdue = risk([
            { type: 'closing_soon_quiet', parameters: { severity: 'high', daysUntilClose: 4 }, references: [] },
            { type: 'close_overdue', parameters: { severity: 'high', daysOverdue: 12 }, references: [] },
        ]);

        expect(radarHorizonPlacement(overdue)).toEqual({ band: 'overdue', days: -12 });
        expect(radarHorizonPlacement(risk([
            { type: 'closing_soon_quiet', parameters: { severity: 'high', daysUntilClose: 20 }, references: [] },
        ])).band).toBe('month');
    });

    it('keeps every column and the backend rank order inside each one', () => {
        const columns = radarHorizonColumns([
            decay({ band: 'cool', daysUntilCold: 2 }, { id: 1 }),
            decay({ band: 'cold' }, { id: 2 }),
            decay({ band: 'cool', daysUntilCold: 4 }, { id: 3 }),
        ]);

        expect(columns.map((column) => column.band)).toEqual([...RADAR_HORIZON_BANDS]);
        expect(columns.find((column) => column.band === 'week')?.signals.map((item) => item.id))
            .toEqual([1, 3]);
        expect(radarHorizonColumns([]).every((column) => column.signals.length === 0)).toBe(true);
    });

    it('caps a column below the volume the detectors can actually deliver', () => {
        const overdue = Array.from({ length: 45 }, (unused, index) => decay(
            { band: 'cold' },
            { id: index + 1, subject: { type: 'person', id: index + 1, label: `Contact ${index + 1}` } },
        ));
        const column = radarHorizonColumns(overdue).find((entry) => entry.band === 'overdue');

        expect(column?.signals).toHaveLength(45);
        expect(RADAR_HORIZON_MARK_LIMIT).toBeLessThan(45);
        expect((column?.signals.length ?? 0) - RADAR_HORIZON_MARK_LIMIT).toBe(15);
    });

    it('accepts only a real column name from the URL', () => {
        expect(isRadarHorizonBand('overdue')).toBe(true);
        expect(isRadarHorizonBand('undated')).toBe(true);
        expect(isRadarHorizonBand('someday')).toBe(false);
        expect(isRadarHorizonBand(null)).toBe(false);
    });
});

describe('what each family layer says about itself', () => {
    it('summarises cooling relationships by warmth band and trend', () => {
        const summary = radarDecaySummary([
            decay({ band: 'cold', trend: 'cooling' }, { id: 1 }),
            decay({ band: 'cool', trend: 'cooling' }, { id: 2 }),
            decay({ band: 'cool', trend: 'steady' }, { id: 3 }),
            signal({ id: 4, evidence: [] }),
        ]);

        expect(summary.bands).toEqual({ hot: 0, warm: 0, cool: 2, cold: 1 });
        expect(summary.trends).toEqual({ rising: 0, steady: 1, cooling: 2 });
        expect(summary.total).toBe(3);
    });

    it('tallies deal-risk reasons by frequency, then by worst severity seen', () => {
        const reasons = radarRiskReasons([
            risk([
                { type: 'stalled', parameters: { severity: 'medium', daysSinceTouch: 30 }, references: [] },
                { type: 'stakeholder_cold', parameters: { severity: 'low', band: 'cool' }, references: [] },
            ], { id: 1 }),
            risk([
                { type: 'stalled', parameters: { severity: 'medium', daysSinceTouch: 45 }, references: [] },
                { type: 'stakeholder_cold', parameters: { severity: 'high', band: 'cold' }, references: [] },
                { type: 'no_stakeholders', parameters: { severity: 'low' }, references: [] },
            ], { id: 2 }),
        ]);

        expect(reasons.map((reason) => [reason.code, reason.count, reason.severity])).toEqual([
            ['stakeholder_cold', 2, 'high'],
            ['stalled', 2, 'medium'],
            ['no_stakeholders', 1, 'low'],
        ]);
    });

    it('ranks intro-path connectors by how many doors each one opens', () => {
        const connectors = radarConnectors([
            path([{ id: 7, name: 'Kenji Sato' }], { id: 1 }),
            path([{ id: 7, name: 'Kenji Sato' }, { id: 9, name: 'Mika Arai' }], {
                id: 2,
                subject: { type: 'person', id: 11, label: 'Grace Hopper' },
            }),
            path([{ id: 7, name: 'Kenji Sato' }, { id: 7, name: 'Kenji Sato' }], {
                id: 3,
                subject: { type: 'person', id: 12, label: 'Alan Turing' },
            }),
        ]);

        expect(connectors).toEqual([
            { personId: 7, name: 'Kenji Sato', reach: 3 },
            { personId: 9, name: 'Mika Arai', reach: 1 },
        ]);
    });

    it('drops a connector it cannot name rather than showing its id', () => {
        const unnamed = signal({
            family: 'warm_path',
            evidence: [{
                type: 'warm_path',
                parameters: { bridgePersonId: 7, pathScore: 20 },
                references: [{ type: 'person', id: 7 }],
            }],
        });

        expect(radarPathBridges(unnamed)).toEqual([]);
        expect(radarConnectors([unnamed])).toEqual([]);
    });

    it('colours a mark by its reading and falls back to the reading that raised it', () => {
        expect(radarMarkTone(decay({ band: 'cool', trend: 'cooling' }))).toBe('cool');
        expect(radarMarkTone(decay({}))).toBe('cold');
        expect(radarMarkTone(risk([
            { type: 'stalled', parameters: { severity: 'medium' }, references: [] },
            { type: 'close_overdue', parameters: { severity: 'high', daysOverdue: 2 }, references: [] },
        ]))).toBe('high');
        expect(radarMarkTone(path([{ id: 7, name: 'Kenji Sato' }]))).toBe('path');
    });

    it('reads the warmth behind a signal and reports absent values as absent', () => {
        expect(radarDecayFacts(decay({ band: 'cool', trend: 'cooling', score: 42, daysSinceTouch: 34 })))
            .toEqual({
                band: 'cool',
                trend: 'cooling',
                score: 42,
                daysSinceTouch: 34,
                daysUntilCold: null,
                goesColdAt: null,
                touchCount: null,
            });
        expect(radarDecayFacts(signal({ evidence: [] }))).toBeNull();
    });
});

describe('every record Radar cites is a named link', () => {
    it('resolves each subject type to a shipped record route', () => {
        for (const subject of [
            { type: 'person', id: 42, label: 'Ada Lovelace' },
            { type: 'company', id: 42, label: 'Marubeni' },
            { type: 'deal', id: 42, label: 'Apollo renewal' },
        ] as const) {
            const href = radarSubjectRecordHref(subject);

            expect(href).not.toBeNull();
            expect(resolveShippedRoute(href ?? '')).not.toBeNull();
        }
        expect(radarRecordHref('person_edge', 42)).toBeNull();
        expect(radarRecordHref('person', 0)).toBeNull();
    });

    it('names the subject and the connectors a signal already proves', () => {
        const introduction = path([{ id: 7, name: 'Kenji Sato' }]);
        const links = radarReferenceLinks(
            introduction.evidence[0].references,
            radarSignalNames(introduction),
        );

        expect(links).toEqual([
            { href: '/records/contacts/10', label: 'Ada Lovelace' },
            { href: '/records/contacts/7', label: 'Kenji Sato' },
        ]);
    });

    it('renders nothing for a reference it can reach but cannot name', () => {
        const stakeholder = risk([{
            type: 'stakeholder_cold',
            parameters: { severity: 'high', band: 'cold' },
            references: [{ type: 'deal', id: 20 }, { type: 'person', id: 55 }],
        }]);
        const links = radarReferenceLinks(
            stakeholder.evidence[0].references,
            radarSignalNames(stakeholder),
        );

        expect(links).toEqual([{ href: '/records/deals/20', label: 'Apollo renewal' }]);
        expect(links.every((link) => !/#\d/.test(link.label))).toBe(true);
    });

    it('prefers a label the API supplies over anything the client inferred', () => {
        const stakeholder = risk([{
            type: 'stakeholder_cold',
            parameters: { severity: 'high' },
            references: [{ type: 'person', id: 55, label: 'Grace Hopper' }],
        }]);

        expect(radarReferenceLinks(stakeholder.evidence[0].references, radarSignalNames(stakeholder)))
            .toEqual([{ href: '/records/contacts/55', label: 'Grace Hopper' }]);
    });

    it('drops an unreachable reference type and de-duplicates repeats', () => {
        const introduction = path([{ id: 7, name: 'Kenji Sato' }]);
        const references = [
            ...introduction.evidence[0].references,
            { type: 'person_edge', id: 3 },
            { type: 'person', id: 7 },
        ];

        expect(radarReferenceLinks(references, radarSignalNames(introduction)).map((link) => link.href))
            .toEqual(['/records/contacts/10', '/records/contacts/7']);
    });

    it('rejects identifier-shaped API fallbacks for subjects, references, and connectors', () => {
        const unnamed = path([{ id: 7, name: '#7' }], {
            subject: { type: 'person', id: 10, label: '#10' },
        });
        const references = [{ type: 'person', id: 55, label: '#55' }];

        expect(radarRecordLabel('#42')).toBeNull();
        expect(radarRecordLabel('  # 42  ')).toBeNull();
        expect(radarSignalNames(unnamed).size).toBe(0);
        expect(radarReferenceLinks(references, radarSignalNames(unnamed))).toEqual([]);
        expect(radarPathBridges(unnamed)).toEqual([]);
    });
});

describe('the Radar deep link other surfaces produce', () => {
    it('lands a cooling figure on Radar filtered to that family', () => {
        const href = radarFamilyHref('relationship_decay');
        const query = new URLSearchParams(href.slice(href.indexOf('?') + 1));

        expect(resolveShippedRoute(href)).toBe('/radar');
        expect(query.get(RADAR_FAMILY_FILTER_KEY)).toBe('relationship_decay');
    });

    it('names the same query keys the board reads back out of the URL', () => {
        expect(RADAR_FAMILY_FILTER_KEY).toBe('family');
        expect(RADAR_STATE_FILTER_KEY).toBe('state');
        expect(RADAR_QUERY_FILTER_KEY).toBe('q');
        expect(RADAR_HORIZON_FILTER_KEY).toBe('when');
    });

    it('serializes every non-default filter and removes every default from a shared link', () => {
        expect(radarOwnedUrlParams({
            family: 'deal_risk',
            state: 'snoozed',
            query: ' Apollo ',
            horizon: 'month',
        })).toEqual({
            family: 'deal_risk',
            state: 'snoozed',
            q: 'Apollo',
            when: 'month',
        });
        expect(radarOwnedUrlParams({
            family: 'all',
            state: 'attention',
            query: ' ',
            horizon: null,
        })).toEqual({ family: undefined, state: undefined, q: undefined, when: undefined });
    });
});
