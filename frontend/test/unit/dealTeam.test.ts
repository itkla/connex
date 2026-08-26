import { describe, expect, it } from "vitest";

import { pendingDealTeamWrites } from "@/app/lib/dealTeam";

describe("pending deal team writes", () => {
    it("asks for no request at all when the dialog is saved untouched", () => {
        const state = { ownerId: 7, collaboratorIds: [8, 9] };

        expect(pendingDealTeamWrites(state, { ...state })).toEqual({ owner: false, collaboratorIds: null });
    });

    it("treats a reordered collaborator list as unchanged", () => {
        expect(pendingDealTeamWrites(
            { ownerId: 7, collaboratorIds: [8, 9] },
            { ownerId: 7, collaboratorIds: [9, 8] },
        )).toEqual({ owner: false, collaboratorIds: null });
    });

    it("writes only the owner when the collaborators are untouched", () => {
        expect(pendingDealTeamWrites(
            { ownerId: 7, collaboratorIds: [8, 9] },
            { ownerId: 11, collaboratorIds: [8, 9] },
        )).toEqual({ owner: true, collaboratorIds: null });
    });

    it("writes only the collaborators when the owner is untouched", () => {
        expect(pendingDealTeamWrites(
            { ownerId: 7, collaboratorIds: [8] },
            { ownerId: 7, collaboratorIds: [8, 9] },
        )).toEqual({ owner: false, collaboratorIds: [8, 9] });
    });

    it("writes an empty collaborator list when the last collaborator is removed", () => {
        expect(pendingDealTeamWrites(
            { ownerId: 7, collaboratorIds: [8] },
            { ownerId: 7, collaboratorIds: [] },
        )).toEqual({ owner: false, collaboratorIds: [] });
    });

    it("drops the drafted owner from the collaborators it sends", () => {
        expect(pendingDealTeamWrites(
            { ownerId: 7, collaboratorIds: [8, 9] },
            { ownerId: 9, collaboratorIds: [8, 9] },
        )).toEqual({ owner: true, collaboratorIds: [8] });
    });

    it("does not re-send collaborators that only differ by the owner the server already removed", () => {
        expect(pendingDealTeamWrites(
            { ownerId: 7, collaboratorIds: [7, 8] },
            { ownerId: 7, collaboratorIds: [8] },
        )).toEqual({ owner: false, collaboratorIds: null });
    });

    it("handles an unassigned owner on both sides", () => {
        expect(pendingDealTeamWrites(
            { ownerId: null, collaboratorIds: [8] },
            { ownerId: null, collaboratorIds: [8] },
        )).toEqual({ owner: false, collaboratorIds: null });
    });

    it("writes the owner when a deal is unassigned", () => {
        expect(pendingDealTeamWrites(
            { ownerId: 7, collaboratorIds: [8] },
            { ownerId: null, collaboratorIds: [8] },
        )).toEqual({ owner: true, collaboratorIds: null });
    });

    it("ignores a duplicated collaborator id", () => {
        expect(pendingDealTeamWrites(
            { ownerId: 7, collaboratorIds: [8] },
            { ownerId: 7, collaboratorIds: [8, 8] },
        )).toEqual({ owner: false, collaboratorIds: null });
    });
});
