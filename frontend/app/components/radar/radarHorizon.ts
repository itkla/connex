import type {
    RadarEvidence,
    RadarSignal,
    TemperatureBand,
    TemperatureTrend,
} from '@/app/lib/types';

/**
 * Radar's own axis: how long until each signal costs the workspace something.
 *
 * Every signal Radar holds carries a deadline somewhere in its evidence — a relationship's
 * predicted cold date, a deal's close date — and that deadline is the one fact no other surface in
 * the product can assemble across families. Placing the whole flagged set on this axis is what lets
 * Radar answer "where is attention bleeding" in a glance instead of a scroll.
 *
 * `undated` is a first-class band rather than a dumping ground: an intro path genuinely has no
 * deadline, and a cooling relationship the model cannot date yet genuinely has no date. Both are
 * honest members of the last column, and each row still explains its own case.
 */
export const RADAR_HORIZON_BANDS = ['overdue', 'week', 'month', 'later', 'undated'] as const;

/** One column of {@link RADAR_HORIZON_BANDS}. */
export type RadarHorizonBand = (typeof RADAR_HORIZON_BANDS)[number];

/** Whether a URL-supplied value names a horizon column. */
export function isRadarHorizonBand(value: string | null): value is RadarHorizonBand {
    return value !== null && RADAR_HORIZON_BANDS.some((band) => band === value);
}

/** Where one signal sits on the horizon, with the day count that put it there. */
export type RadarHorizonPlacement = {
    band: RadarHorizonBand;
    days: number | null;
};

/** One horizon column and the signals standing in it, in the backend's rank order. */
export type RadarHorizonColumn = {
    band: RadarHorizonBand;
    signals: RadarSignal[];
};

/** Warmth bands and trends across the cooling relationships Radar is currently holding. */
export type RadarDecaySummary = {
    bands: Record<TemperatureBand, number>;
    trends: Record<TemperatureTrend, number>;
    total: number;
};

/** How often one deal-risk reason fires across the flagged deals, at its worst severity. */
export type RadarRiskReason = {
    code: string;
    count: number;
    severity: RadarRiskSeverity;
};

/** A person who can open more than one door, and how many they open. */
export type RadarConnector = {
    personId: number;
    name: string;
    reach: number;
};

/** Severity of one deal-risk reason. */
export type RadarRiskSeverity = 'high' | 'medium' | 'low';

/** The warmth reading behind one cooling-relationship signal. */
export type RadarDecayFacts = {
    band: TemperatureBand | null;
    trend: TemperatureTrend | null;
    score: number | null;
    daysSinceTouch: number | null;
    daysUntilCold: number | null;
    goesColdAt: string | null;
    touchCount: number | null;
};

/** One reason a deal is flagged, with whichever dates and readings the reason carries. */
export type RadarRiskFacts = {
    code: string;
    severity: RadarRiskSeverity;
    daysOverdue: number | null;
    daysUntilClose: number | null;
    daysSinceTouch: number | null;
    band: TemperatureBand | null;
    role: string | null;
};

/** One connector on an intro path, with the evidence that the path is real. */
export type RadarPathFacts = {
    bridgePersonId: number;
    bridgeName: string;
    evidenceType: string | null;
    reachType: string | null;
    evidenceCompany: string | null;
    overlapStartYear: number | null;
    overlapEndYear: number | null;
    pathScore: number | null;
};

const WITHIN_A_WEEK = 7;
const WITHIN_A_MONTH = 30;

const WARMTH_BANDS: readonly TemperatureBand[] = ['hot', 'warm', 'cool', 'cold'];
const WARMTH_TRENDS: readonly TemperatureTrend[] = ['rising', 'steady', 'cooling'];
const RISK_SEVERITIES: readonly RadarRiskSeverity[] = ['high', 'medium', 'low'];

function numberOf(value: unknown): number | null {
    return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

function textOf(value: unknown): string | null {
    if (typeof value !== 'string') return null;
    const trimmed = value.trim();
    return trimmed.length > 0 ? trimmed : null;
}

function warmthBandOf(value: unknown): TemperatureBand | null {
    return WARMTH_BANDS.find((band) => band === value) ?? null;
}

function warmthTrendOf(value: unknown): TemperatureTrend | null {
    return WARMTH_TRENDS.find((trend) => trend === value) ?? null;
}

function severityOf(value: unknown): RadarRiskSeverity {
    return RISK_SEVERITIES.find((severity) => severity === value) ?? 'low';
}

function decayEvidence(signal: RadarSignal): RadarEvidence | undefined {
    return signal.evidence.find((evidence) => evidence.type === 'relationship_temperature');
}

/**
 * The warmth reading behind a cooling-relationship signal, or null when the signal carries no
 * warmth evidence — which happens when the detector's evidence was filtered for visibility.
 */
export function radarDecayFacts(signal: RadarSignal): RadarDecayFacts | null {
    const evidence = decayEvidence(signal);
    if (!evidence) return null;
    const parameters = evidence.parameters;
    return {
        band: warmthBandOf(parameters.band),
        trend: warmthTrendOf(parameters.trend),
        score: numberOf(parameters.score),
        daysSinceTouch: numberOf(parameters.daysSinceTouch),
        daysUntilCold: numberOf(parameters.daysUntilCold),
        goesColdAt: textOf(parameters.goesColdAt),
        touchCount: numberOf(parameters.touchCount),
    };
}

/** Every reason one deal is flagged, in the order the detector recorded them. */
export function radarRiskFacts(signal: RadarSignal): RadarRiskFacts[] {
    return signal.evidence.map((evidence) => ({
        code: evidence.type,
        severity: severityOf(evidence.parameters.severity),
        daysOverdue: numberOf(evidence.parameters.daysOverdue),
        daysUntilClose: numberOf(evidence.parameters.daysUntilClose),
        daysSinceTouch: numberOf(evidence.parameters.daysSinceTouch),
        band: warmthBandOf(evidence.parameters.band),
        role: textOf(evidence.parameters.role),
    }));
}

/**
 * Every connector on an intro path. A bridge with no usable name is dropped rather than rendered
 * as an id, so nothing on this surface can fall back to "contact #42".
 */
export function radarPathBridges(signal: RadarSignal): RadarPathFacts[] {
    const bridges: RadarPathFacts[] = [];
    for (const evidence of signal.evidence) {
        if (evidence.type !== 'warm_path') continue;
        const bridgePersonId = numberOf(evidence.parameters.bridgePersonId);
        const bridgeName = textOf(evidence.parameters.bridgeName);
        if (bridgePersonId === null || !Number.isInteger(bridgePersonId) || bridgeName === null) {
            continue;
        }
        bridges.push({
            bridgePersonId,
            bridgeName,
            evidenceType: textOf(evidence.parameters.evidenceType),
            reachType: textOf(evidence.parameters.reachType),
            evidenceCompany: textOf(evidence.parameters.evidenceCompany),
            overlapStartYear: numberOf(evidence.parameters.overlapStartYear),
            overlapEndYear: numberOf(evidence.parameters.overlapEndYear),
            pathScore: numberOf(evidence.parameters.pathScore),
        });
    }
    return bridges;
}

function dealRiskDays(signal: RadarSignal): number | null {
    let soonest: number | null = null;
    for (const facts of radarRiskFacts(signal)) {
        const days = facts.daysOverdue !== null ? -facts.daysOverdue : facts.daysUntilClose;
        if (days === null) continue;
        if (soonest === null || days < soonest) soonest = days;
    }
    return soonest;
}

function bandOfDays(days: number | null): RadarHorizonBand {
    if (days === null) return 'undated';
    if (days < 0) return 'overdue';
    if (days <= WITHIN_A_WEEK) return 'week';
    if (days <= WITHIN_A_MONTH) return 'month';
    return 'later';
}

/**
 * Places one signal on the horizon.
 *
 * A relationship already reading cold has spent its deadline whether or not the model still
 * publishes a date for it, so it lands in `overdue` on the strength of the band alone. Everything
 * else is placed by the day count its family carries, and a signal with no date lands in `undated`
 * rather than being invented a position.
 */
export function radarHorizonPlacement(signal: RadarSignal): RadarHorizonPlacement {
    if (signal.family === 'relationship_decay') {
        const facts = radarDecayFacts(signal);
        const days = facts?.daysUntilCold ?? null;
        if (facts?.band === 'cold') return { band: 'overdue', days };
        return { band: bandOfDays(days), days };
    }
    if (signal.family === 'deal_risk') {
        const days = dealRiskDays(signal);
        return { band: bandOfDays(days), days };
    }
    return { band: 'undated', days: null };
}

/**
 * Groups signals into every horizon column, empty ones included.
 *
 * The axis keeps its full width whatever the data does: a week with nothing overdue should read as
 * an empty first column, not as an axis that silently rescaled itself.
 */
export function radarHorizonColumns(signals: readonly RadarSignal[]): RadarHorizonColumn[] {
    return RADAR_HORIZON_BANDS.map((band) => ({
        band,
        signals: signals.filter((signal) => radarHorizonPlacement(signal).band === band),
    }));
}

/** How many signals stand in the busiest column, so the columns can share one vertical scale. */
export function radarHorizonPeak(columns: readonly RadarHorizonColumn[]): number {
    return columns.reduce((peak, column) => Math.max(peak, column.signals.length), 0);
}

/**
 * What one signal's mark is coloured by: its warmth band, its worst risk severity, or the intro
 * opportunity accent. A cooling relationship whose evidence carries no band reads as cold, which is
 * the reading that put it on Radar in the first place.
 */
export function radarMarkTone(signal: RadarSignal): TemperatureBand | RadarRiskSeverity | 'path' {
    if (signal.family === 'relationship_decay') {
        return radarDecayFacts(signal)?.band ?? 'cold';
    }
    if (signal.family === 'deal_risk') {
        return radarRiskFacts(signal).reduce<RadarRiskSeverity>(
            (worst, facts) => (
                RISK_SEVERITIES.indexOf(facts.severity) < RISK_SEVERITIES.indexOf(worst)
                    ? facts.severity
                    : worst
            ),
            'low',
        );
    }
    return 'path';
}

/** Warmth bands and trends across the cooling relationships in view. */
export function radarDecaySummary(signals: readonly RadarSignal[]): RadarDecaySummary {
    const bands: Record<TemperatureBand, number> = { hot: 0, warm: 0, cool: 0, cold: 0 };
    const trends: Record<TemperatureTrend, number> = { rising: 0, steady: 0, cooling: 0 };
    let total = 0;
    for (const signal of signals) {
        const facts = radarDecayFacts(signal);
        if (!facts) continue;
        if (facts.band) {
            bands[facts.band] += 1;
            total += 1;
        }
        if (facts.trend) trends[facts.trend] += 1;
    }
    return { bands, trends, total };
}

/**
 * Why the flagged deals are flagged, most common reason first, each at the worst severity it was
 * seen with. This is the deal-risk family's own shape: a count of deals says how much is wrong, a
 * tally of reasons says what is wrong.
 */
export function radarRiskReasons(signals: readonly RadarSignal[]): RadarRiskReason[] {
    const reasons = new Map<string, RadarRiskReason>();
    for (const signal of signals) {
        for (const facts of radarRiskFacts(signal)) {
            const existing = reasons.get(facts.code);
            if (!existing) {
                reasons.set(facts.code, { code: facts.code, count: 1, severity: facts.severity });
                continue;
            }
            existing.count += 1;
            if (RISK_SEVERITIES.indexOf(facts.severity) < RISK_SEVERITIES.indexOf(existing.severity)) {
                existing.severity = facts.severity;
            }
        }
    }
    return [...reasons.values()].sort((left, right) => (
        right.count - left.count
        || RISK_SEVERITIES.indexOf(left.severity) - RISK_SEVERITIES.indexOf(right.severity)
        || left.code.localeCompare(right.code)
    ));
}

/**
 * The people who can open the most doors, widest reach first.
 *
 * This is the only real cluster the Radar payload supports: intro-path evidence names its
 * connector, so one person recurring across several paths is a fact rather than an inference.
 */
export function radarConnectors(signals: readonly RadarSignal[]): RadarConnector[] {
    const connectors = new Map<number, RadarConnector>();
    for (const signal of signals) {
        const seen = new Set<number>();
        for (const bridge of radarPathBridges(signal)) {
            if (seen.has(bridge.bridgePersonId)) continue;
            seen.add(bridge.bridgePersonId);
            const existing = connectors.get(bridge.bridgePersonId);
            if (existing) existing.reach += 1;
            else connectors.set(bridge.bridgePersonId, {
                personId: bridge.bridgePersonId,
                name: bridge.bridgeName,
                reach: 1,
            });
        }
    }
    return [...connectors.values()].sort((left, right) => (
        right.reach - left.reach || left.name.localeCompare(right.name)
    ));
}
