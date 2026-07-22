import {
    type DealRisk,
    type DealRiskFactorCode,
    type DealRiskSeverity,
    type TaskStatus,
    type TemperatureBand,
    type TemperatureTrend,
} from '@/app/lib/types';

export const WARMTH_BANDS: readonly TemperatureBand[] = ['hot', 'warm', 'cool', 'cold'];

/** CSS custom-property references for each warmth band, for recharts fills and inline bar colours. */
export const WARMTH_VAR: Record<TemperatureBand, string> = {
    hot: 'var(--warmth-hot)',
    warm: 'var(--warmth-warm)',
    cool: 'var(--warmth-cool)',
    cold: 'var(--warmth-cold)',
};

export const TREND_ORDER: readonly TemperatureTrend[] = ['rising', 'steady', 'cooling'];

export const RISK_LEVELS: readonly DealRiskSeverity[] = ['high', 'medium', 'low'];

/** CSS custom-property references for each deal-risk severity, mirroring {@link WARMTH_VAR}. */
export const RISK_VAR: Record<DealRiskSeverity, string> = {
    high: 'var(--risk-high)',
    medium: 'var(--risk-medium)',
    low: 'var(--risk-low)',
};

export const TASK_STATUSES: readonly TaskStatus[] = ['todo', 'in_progress', 'done'];

export type DecayBucketKey = 'soon' | 'mid' | 'later';

/** Forward-looking decay horizon: how soon a still-warm relationship is predicted to go cold. */
export const DECAY_BUCKETS: readonly { key: DecayBucketKey; maxDays: number }[] = [
    { key: 'soon', maxDays: 30 },
    { key: 'mid', maxDays: 60 },
    { key: 'later', maxDays: 90 },
];

/** The set of deal ids the risk engine currently flags as at risk. */
export function atRiskDealIds(risks: DealRisk[]): Set<number> {
    const out = new Set<number>();
    for (const risk of risks) {
        if (risk.level !== 'none') out.add(risk.dealId);
    }
    return out;
}

/** Count of at-risk deals by severity level. */
export function riskLevelCounts(risks: DealRisk[]): Record<DealRiskSeverity, number> {
    const out: Record<DealRiskSeverity, number> = { high: 0, medium: 0, low: 0 };
    for (const risk of risks) {
        if (risk.level !== 'none') out[risk.level] += 1;
    }
    return out;
}

/** How often each risk factor fires across the at-risk deals, most common first. */
export function riskFactorCounts(risks: DealRisk[]): { code: DealRiskFactorCode; count: number }[] {
    const counts = new Map<DealRiskFactorCode, number>();
    for (const risk of risks) {
        for (const factor of risk.factors) {
            counts.set(factor.code, (counts.get(factor.code) ?? 0) + 1);
        }
    }
    return Array.from(counts.entries())
        .map(([code, count]) => ({ code, count }))
        .sort((a, b) => b.count - a.count);
}
