// TODO: split these apart and put them into types.ts and utils.ts

import { type Stage } from '@/app/lib/types';

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