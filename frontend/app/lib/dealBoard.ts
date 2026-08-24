import type { Deal } from '@/app/lib/types';

/**
 * The board's deals with one card moved to `stageId` at `index` among that stage's deals, matching
 * what the server is being asked to persist. Applying this locally lets the board keep the placement
 * the drag already showed while the authoritative board reloads behind it, instead of reverting to a
 * loading state and re-reading the move it just made.
 *
 * Positions are renumbered only within the destination stage; the gap left behind in the source stage
 * is harmless because every consumer orders by position rather than trusting it to be contiguous.
 */
export function withDealMoved(deals: Deal[], dealId: number, stageId: number, index: number): Deal[] {
    const moved = deals.find((deal) => deal.id === dealId);
    if (!moved) return deals;

    const destination = deals
        .filter((deal) => deal.id !== dealId && deal.stage === stageId)
        .sort((left, right) => left.position - right.position || left.id - right.id);
    destination.splice(Math.max(0, Math.min(index, destination.length)), 0, moved);

    const positionById = new Map(destination.map((deal, position) => [deal.id, position]));
    return deals.map((deal) => {
        const position = positionById.get(deal.id);
        if (position === undefined) return deal;
        return deal.id === dealId ? { ...deal, stage: stageId, position } : { ...deal, position };
    });
}
