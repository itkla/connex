import type { Deal } from '@/app/lib/types';

/**
 * The board's deals with `moved` — the deal exactly as the server returned it from the move — placed
 * at `index` among its new stage's deals. Applying this locally lets the board keep the placement the
 * drag already showed while the authoritative board reloads behind it, instead of reverting to a
 * loading state and re-reading the move it just made.
 *
 * The server reconciles outcome fields when a deal lands on a terminal stage, so the returned deal
 * replaces the local one wholesale rather than having its stage and position patched in isolation.
 *
 * Positions are renumbered only within the destination stage; the gap left behind in the source stage
 * is harmless because every consumer orders by position rather than trusting it to be contiguous.
 */
export function withDealMoved(deals: Deal[], moved: Deal, index: number): Deal[] {
    if (!deals.some((deal) => deal.id === moved.id)) return deals;

    const destination = deals
        .filter((deal) => deal.id !== moved.id && deal.stage === moved.stage)
        .sort((left, right) => left.position - right.position || left.id - right.id);
    destination.splice(Math.max(0, Math.min(index, destination.length)), 0, moved);

    const positionById = new Map(destination.map((deal, position) => [deal.id, position]));
    return deals.map((deal) => {
        const position = positionById.get(deal.id);
        if (position === undefined) return deal;
        return deal.id === moved.id ? { ...moved, position } : { ...deal, position };
    });
}
