import { describe, expect, it } from "vitest";

import { withDealMoved } from "@/app/lib/dealBoard";
import type { Deal } from "@/app/lib/types";

function deal(id: number, stage: number, position: number, extra: Partial<Deal> = {}): Deal {
    return {
        id,
        name: `Deal ${id}`,
        value: 1000,
        actualValue: 0,
        currency: "JPY",
        pipeline: 1,
        stage,
        position,
        company: null,
        createdAt: "2026-01-01 00:00:00",
        updatedAt: "2026-01-01 00:00:00",
        ...extra,
    };
}

/** The board ordered the way every consumer reads it: by stage, then position, then id. */
function ordered(deals: Deal[], stage: number): number[] {
    return deals
        .filter((entry) => entry.stage === stage)
        .sort((left, right) => left.position - right.position || left.id - right.id)
        .map((entry) => entry.id);
}

const board = [deal(1, 10, 0), deal(2, 10, 1), deal(3, 10, 2), deal(9, 20, 0)];

/** The deal as the server returns it from a move: already carrying its new stage. */
function movedTo(id: number, stage: number, extra: Partial<Deal> = {}): Deal {
    const current = board.find((entry) => entry.id === id);
    if (!current) throw new Error(`no deal ${id} on the fixture board`);
    return { ...current, stage, ...extra };
}

describe("moving a deal on the board", () => {
    it("moves a card to another stage at the requested index", () => {
        const next = withDealMoved(board, movedTo(2, 20), 0);

        expect(ordered(next, 20)).toEqual([2, 9]);
        expect(ordered(next, 10)).toEqual([1, 3]);
    });

    it("appends to the destination when the index is past its end", () => {
        expect(ordered(withDealMoved(board, movedTo(1, 20), 99), 20)).toEqual([9, 1]);
    });

    it("reorders within the same stage", () => {
        expect(ordered(withDealMoved(board, movedTo(3, 10), 0), 10)).toEqual([3, 1, 2]);
    });

    it("reorders a card downward within its own stage", () => {
        expect(ordered(withDealMoved(board, movedTo(1, 10), 2), 10)).toEqual([2, 3, 1]);
    });

    it("renumbers the destination stage contiguously from zero", () => {
        const next = withDealMoved(board, movedTo(2, 20), 1);

        expect(next.filter((entry) => entry.stage === 20).map((entry) => entry.position).sort())
            .toEqual([0, 1]);
    });

    it("takes the outcome the server reconciled rather than the card's stale one", () => {
        const next = withDealMoved(board, movedTo(2, 20, { won: true, closedAt: "2026-08-23" }), 0);
        const moved = next.find((entry) => entry.id === 2);

        expect(moved?.won).toBe(true);
        expect(moved?.closedAt).toBe("2026-08-23");
        expect(moved?.stage).toBe(20);
    });

    it("leaves untouched stages alone", () => {
        const extended = [...board, deal(7, 30, 5)];

        expect(withDealMoved(extended, movedTo(2, 20), 0).find((entry) => entry.id === 7))
            .toEqual(deal(7, 30, 5));
    });

    it("returns the board unchanged when the card is not on it", () => {
        expect(withDealMoved(board, deal(404, 20, 0), 0)).toBe(board);
    });

    it("treats a negative index as the head of the destination", () => {
        expect(ordered(withDealMoved(board, movedTo(1, 20), -3), 20)).toEqual([1, 9]);
    });

    it("does not mutate the board it was given", () => {
        const snapshot = board.map((entry) => ({ ...entry }));
        withDealMoved(board, movedTo(2, 20), 0);

        expect(board).toEqual(snapshot);
    });

    it("orders a destination stage whose positions are sparse", () => {
        const sparse = [deal(1, 10, 0), deal(4, 20, 3), deal(5, 20, 9)];

        expect(ordered(withDealMoved(sparse, { ...sparse[0], stage: 20 }, 1), 20)).toEqual([4, 1, 5]);
    });
});
