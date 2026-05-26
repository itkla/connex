// TODO: split these apart and put them into types.ts and utils.ts

export type StageClass = 'won' | 'lost' | 'normal';
export type DealOutcome = 'open' | 'won' | 'lost' | 'closed';

export function classifyStage(name?: string | null): StageClass {
    if (!name) return 'normal';
    const n = name.toLowerCase();
    if (/(?:\bwon\b|renew|complet)/.test(n)) return 'won';
    if (/(?:lost|churn)/.test(n)) return 'lost';
    return 'normal';
}

export function dealOutcome(closed: boolean, currentStageName?: string | null): DealOutcome {
    if (!closed) return 'open';
    const c = classifyStage(currentStageName);
    return c === 'normal' ? 'closed' : c;
}