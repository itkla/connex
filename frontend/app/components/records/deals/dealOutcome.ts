// TODO: split these apart and put them into types.ts and utils.ts

import { type Deal, type Stage } from '@/app/lib/types';

export type StageClass = 'won' | 'lost' | 'normal';
export type DealOutcome = 'open' | 'won' | 'lost';

type StageFlags = Pick<Stage, 'success' | 'failure'>;

/**
 * Classifies a STAGE by its flags — for stage-centric visuals (funnel coloring,
 * stage distribution, lifecycle markers). This is about the stage, not a deal's
 * outcome; for a deal's outcome use dealOutcome(deal.won).
 */
export function classifyStage(stage?: StageFlags | null): StageClass {
    if (!stage) return 'normal';
    if (stage.success) return 'won';
    if (stage.failure) return 'lost';
    return 'normal';
}

/**
 * A deal's outcome from its explicit `won` field: true = won, false = lost,
 * null/undefined = open. Independent of the deal's stage — a deal can be won or
 * lost at any stage, including an in-progress one.
 */
export function dealOutcome(won?: boolean | null): DealOutcome {
    if (won == null) return 'open';
    return won ? 'won' : 'lost';
}

/**
 * Whether a deal is closed (won or lost). A deal is closed exactly when it has an
 * explicit outcome: the DB enforces `(won IS NULL) = (closed_at IS NULL)`, so `won`
 * is the timezone-independent source of truth. Prefer this over comparing the
 * `closedAt` timestamp to `Date.now()` — that's a display value subject to clock and
 * timezone skew, and a future-dated `closedAt` would wrongly read as open.
 */
export function isDealClosed(deal: Pick<Deal, 'won'>): boolean {
    return deal.won != null;
}