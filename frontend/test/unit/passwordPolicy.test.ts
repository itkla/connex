import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { missingPasswordRequirements } from "@/app/lib/passwordPolicy";
import policy from "@/app/lib/password-policy.json";
import en from "@/messages/en/auth.json";
import ja from "@/messages/ja/auth.json";

describe("shared password policy", () => {
    it("uses a byte-identical copy of the canonical server policy", () => {
        expect(readFileSync("app/lib/password-policy.json", "utf8"))
            .toBe(readFileSync("../backend/src/main/resources/password-policy.json", "utf8"));
        for (const messages of [en, ja]) {
            for (const key of ["length", "byteLength", ...policy.rules.map((rule) => rule.key)]) {
                expect(Reflect.get(messages.PasswordPolicy, key)).toBeTruthy();
            }
        }
    });

    const symbols = [...Array.from({ length: 95 }, (_, index) => String.fromCharCode(index + 32))
        .filter((symbol) => !/[a-zA-Z0-9]/.test(symbol)), "£", "€", "。", "★", "　"];

    it.each(symbols)("accepts %j as the only special character", (symbol) => {
        expect(missingPasswordRequirements(`Aa12345${symbol}`)).toEqual([]);
    });

    it.each([
        ["abcdef1#", ["uppercase"]],
        ["ABCDEF1#", ["lowercase"]],
        ["Abcdefg#", ["number"]],
        ["Abcdefg1", ["specialCharacter"]],
        ["Aa1#abc", ["length"]],
        [`Aa1#${"a".repeat(69)}`, ["length", "byteLength"]],
        [`Aa1#${"€".repeat(23)}`, ["byteLength"]],
        ["Aa12345\n", ["printable", "specialCharacter"]],
        ["Aa12345\t", ["printable", "specialCharacter"]],
        ["Aa12345\u0000", ["printable", "specialCharacter"]],
        ["Aa12345\u200b", ["printable", "specialCharacter"]],
        ["Aa12345\u2028", ["printable", "specialCharacter"]],
        ["Aa12345\ud800", ["printable", "specialCharacter"]],
    ] as const)("refuses %j with its actual missing requirements", (password, missing) => {
        expect(missingPasswordRequirements(password)).toEqual(missing);
    });

    it("preserves boundary length and leading/trailing spaces", () => {
        expect(missingPasswordRequirements(`Aa1#${"a".repeat(68)}`)).toEqual([]);
        expect(missingPasswordRequirements(`Aa1#${"€".repeat(22)}aa`)).toEqual([]);
        expect(missingPasswordRequirements(" Aa12345")).toEqual([]);
        expect(missingPasswordRequirements("Aa12345 ")).toEqual([]);
    });
});
