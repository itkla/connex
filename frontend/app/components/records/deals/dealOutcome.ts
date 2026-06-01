// TODO: split these apart and put them into types.ts and utils.ts

import { type Stage } from '@/app/lib/types';

export type StageClass = 'won' | 'lost' | 'normal';
export type DealOutcome = 'open' | 'won' | 'lost' | 'closed';

type StageFlags = Pick<Stage, 'success' | 'failure'>;

export function classifyStage(stage?: StageFlags | null): StageClass {
    if (!stage) return 'normal';
    if (stage.success) return 'won';
    if (stage.failure) return 'lost';
    return 'normal';
}


export function dealOutcome(currentStage?: StageFlags | null): DealOutcome {
    const c = classifyStage(currentStage);
    return c === 'normal' ? 'open' : c;
}