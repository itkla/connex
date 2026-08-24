import { describe, expect, it } from "vitest";

import { withDealMoved } from "@/app/lib/dealBoard";
import type { Deal } from "@/app/lib/types";

function deal(id: number, stage: number, position: number): Deal {
    return { id, stage, position, name: `Deal ${id}`, pipeline: 1 } as Deal;
}

/** The board ordered the way every consumer reads it: by stage, then position, then id. */
function ordered(deals: Deal[], stage: number): number[] {
    return deals
        .filter((entry) => entry.stage === stage)
        .sort((left, right) => left.position - right.position || left.id - right.id)
        .map((entry) => entry.id);
}

describe("moving a deal on the board", () => {
    const board = [deal(1, 10, 0), deal(2, 10, 1), deal(3, 10, 2), deal(9, 20, 0)];

    it("moves a card to another stage at the requested index", () => {
        const next = withDealMoved(board, 2, 20, 0);

        expect(ordered(next, 20)).toEqual([2, 9]);
        expect(ordered(next, 10)).toEqual([1, 3]);
    });

    it("appends to the destination when the index is past its end", () => {
        const next = withDealMoved(board, 1, 20, 99);

        expect(ordered(next, 20)).toEqual([9, 1]);
    });

    it("reorders within the same stage", () => {
        const next = withDealMoved(board, 3, 10, 0);

        expect(ordered(next, 10)).toEqual([3, 1, 2]);
    });

    it("reorders a card downward within its own stage", () => {
        const next = withDealMoved(board, 1, 10, 2);

        expect(ordered(next, 10)).toEqual([2, 3, 1]);
    });

    it("renumbers the destination stage contiguously from zero", () => {
        const next = withDealMoved(board, 2, 20, 1);

        expect(next.filter((entry) => entry.stage === 20).map((entry) => entry.position).sort())
            .toEqual([0, 1]);
    });

    it("leaves the moved card's stage set to the destination", () => {
        const next = withDealMoved(board, 2, 20, 0);

        expect(next.find((entry) => entry.id === 2)?.stage).toBe(20);
    });

    it("leaves untouched stages alone", () => {
        const next = withDealMoved([...board, deal(7, 30, 5)], 2, 20, 0);

        expect(next.find((entry) => entry.id === 7)).toEqual(deal(7, 30, 5));
    });

    it("returns the board unchanged when the card is not on it", () => {
        expect(withDealMoved(board, 404, 20, 0)).toBe(board);
    });

    it("treats a negative index as the head of the destination", () => {
        expect(ordered(withDealMoved(board, 1, 20, -3), 20)).toEqual([1, 9]);
    });

    it("does not mutate the board it was given", () => {
        const snapshot = board.map((entry) => ({ ...entry }));
        withDealMoved(board, 2, 20, 0);

        expect(board).toEqual(snapshot);
    });

    it("orders a destination stage whose positions are sparse", () => {
        const sparse = [deal(1, 10, 0), deal(4, 20, 3), deal(5, 20, 9)];

        expect(ordered(withDealMoved(sparse, 1, 20, 1), 20)).toEqual([4, 1, 5]);
    });
});
