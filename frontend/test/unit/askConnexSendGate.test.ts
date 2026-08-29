import { describe, expect, it } from "vitest";

import { askConnexSendable } from "@/app/lib/askConnex";

const READY = { available: true, composer: "", composerTooLong: false };

describe("what Ask Connex accepts as a sendable question", () => {
    it("sends a suggested follow-up while the composer is empty", () => {
        expect(askConnexSendable({ ...READY, suggestion: "Draft the follow-up" })).toBe(true);
    });

    it("refuses an empty composer when nothing else supplies the words", () => {
        expect(askConnexSendable({ ...READY })).toBe(false);
        expect(askConnexSendable({ ...READY, composer: "   " })).toBe(false);
    });

    it("sends what the member typed", () => {
        expect(askConnexSendable({ ...READY, composer: "Who owns this deal?" })).toBe(true);
    });

    it("refuses a suggestion with no words of its own", () => {
        expect(askConnexSendable({ ...READY, suggestion: "  " })).toBe(false);
    });

    it("holds every send while the conversation cannot accept one", () => {
        expect(askConnexSendable({
            available: false,
            composer: "Who owns this deal?",
            composerTooLong: false,
        })).toBe(false);
        expect(askConnexSendable({
            available: false,
            composer: "",
            suggestion: "Draft the follow-up",
            composerTooLong: false,
        })).toBe(false);
    });

    it("keeps the composer's length ceiling off the assistant's own phrase", () => {
        expect(askConnexSendable({
            available: true,
            composer: "a".repeat(20_000),
            composerTooLong: true,
        })).toBe(false);
        expect(askConnexSendable({
            available: true,
            composer: "a".repeat(20_000),
            suggestion: "Draft the follow-up",
            composerTooLong: true,
        })).toBe(true);
    });
});
