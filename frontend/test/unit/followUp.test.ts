import { describe, expect, it } from "vitest";

import { followUpDueDate } from "@/app/lib/followUp";

const DAY_MS = 86_400_000;

/** A fixed UTC noon, so a due date can never straddle a day boundary by a few hours. */
const NOW = Date.UTC(2026, 5, 10, 12);

/** The `YYYY-MM-DD` the helper produces, read on the stable UTC calendar. */
function localDate(at: number): string {
    return new Date(at).toISOString().slice(0, 10);
}

/** A MySQL-shaped UTC datetime `days` from {@link NOW}, the form warmth reports a cold date in. */
function goesColdIn(days: number): string {
    return new Date(NOW + days * DAY_MS).toISOString().slice(0, 19).replace("T", " ");
}

describe("the follow-up a cooling signal schedules", () => {
    it("lands five days before a distant predicted cold date", () => {
        expect(followUpDueDate(goesColdIn(30), NOW)).toBe(localDate(NOW + 25 * DAY_MS));
    });

    it("falls back to a short horizon when warmth predicts no cold date", () => {
        expect(followUpDueDate(null, NOW)).toBe(localDate(NOW + 3 * DAY_MS));
        expect(followUpDueDate(undefined, NOW)).toBe(localDate(NOW + 3 * DAY_MS));
    });

    it("falls back rather than trusting an unparseable cold date", () => {
        expect(followUpDueDate("not a date", NOW)).toBe(localDate(NOW + 3 * DAY_MS));
        expect(followUpDueDate("", NOW)).toBe(localDate(NOW + 3 * DAY_MS));
    });

    it("never schedules the follow-up before tomorrow", () => {
        expect(followUpDueDate(goesColdIn(5), NOW)).toBe(localDate(NOW + DAY_MS));
        expect(followUpDueDate(goesColdIn(1), NOW)).toBe(localDate(NOW + DAY_MS));
    });

    it("still schedules a follow-up when the relationship is already predicted cold", () => {
        expect(followUpDueDate(goesColdIn(-40), NOW)).toBe(localDate(NOW + DAY_MS));
    });

    it("keeps the six-day mark as the first date the buffer alone can reach", () => {
        expect(followUpDueDate(goesColdIn(6), NOW)).toBe(localDate(NOW + DAY_MS));
        expect(followUpDueDate(goesColdIn(7), NOW)).toBe(localDate(NOW + 2 * DAY_MS));
    });

    it("emits a zero-padded calendar date the task composer accepts", () => {
        const early = Date.UTC(2026, 0, 2, 12);

        expect(followUpDueDate(null, early)).toMatch(/^\d{4}-\d{2}-\d{2}$/);
        expect(followUpDueDate(null, early)).toBe("2026-01-05");
    });
});
