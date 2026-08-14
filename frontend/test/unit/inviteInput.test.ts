import { readFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";

import { parseInviteInput } from "@/app/lib/inviteInput";

const ONBOARDING_FORM = "app/onboarding/OnboardingForm.tsx";
const INVITE_PAGE = "app/invite/page.tsx";

const EMAILED_TOKEN = "emailed-invite-token_for_parser_tests_000001";
const SHAREABLE_TOKEN = "shareable-link-token_for_parser_tests_000002";
const ORIGIN = "https://connex.example.com";

function source(relativePath: string): string {
    return readFileSync(path.resolve(process.cwd(), relativePath), "utf8");
}

function isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isFlatNamespace(value: unknown): value is Record<string, string> {
    return isRecord(value) && Object.values(value).every((message) => typeof message === "string");
}

function onboardingCopy(locale: "en" | "ja"): Record<string, string> {
    const parsed: unknown = JSON.parse(source(`messages/${locale}/workspace.json`));
    if (!isRecord(parsed) || !isFlatNamespace(parsed.Onboarding)) {
        throw new Error(`messages/${locale}/workspace.json has no Onboarding namespace`);
    }
    return parsed.Onboarding;
}

describe("parseInviteInput", () => {
    it("preserves fragment-only invite URLs without moving the bearer into the path", () => {
        expect(parseInviteInput(`${ORIGIN}/invite#token=${EMAILED_TOKEN}`)).toEqual({
            kind: "invite",
            token: EMAILED_TOKEN,
            href: `/invite#token=${EMAILED_TOKEN}`,
        });
        expect(parseInviteInput(`${ORIGIN}/invite-link#token=${SHAREABLE_TOKEN}`)).toEqual({
            kind: "invite-link",
            token: SHAREABLE_TOKEN,
            href: `/invite-link#token=${SHAREABLE_TOKEN}`,
        });
    });

    it("routes an emailed invite URL to the invite page", () => {
        expect(parseInviteInput(`${ORIGIN}/invite#token=${EMAILED_TOKEN}`)).toEqual({
            kind: "invite",
            token: EMAILED_TOKEN,
            href: `/invite#token=${EMAILED_TOKEN}`,
        });
    });

    it("routes a shareable invite link to the invite-link page", () => {
        expect(parseInviteInput(`${ORIGIN}/invite-link#token=${SHAREABLE_TOKEN}`)).toEqual({
            kind: "invite-link",
            token: SHAREABLE_TOKEN,
            href: `/invite-link#token=${SHAREABLE_TOKEN}`,
        });
    });

    it("never mistakes the scheme of a shareable link for its token", () => {
        const parsed = parseInviteInput(`${ORIGIN}/invite-link#token=${SHAREABLE_TOKEN}`);

        expect(parsed?.token).not.toBe("https:");
        expect(parsed?.href).not.toContain("https");
    });

    it("accepts a bare token and redeems it as an emailed invite", () => {
        expect(parseInviteInput(EMAILED_TOKEN)).toEqual({
            kind: "invite",
            token: EMAILED_TOKEN,
            href: `/invite#token=${EMAILED_TOKEN}`,
        });
    });

    it("ignores surrounding whitespace, query strings, fragments, and trailing segments", () => {
        const expected = { kind: "invite-link", token: SHAREABLE_TOKEN, href: `/invite-link#token=${SHAREABLE_TOKEN}` };

        expect(parseInviteInput(`  ${ORIGIN}/invite-link#token=${SHAREABLE_TOKEN}  `)).toEqual(expected);
    });

    it("tolerates the punctuation a pasted link picks up in transit", () => {
        const expected = { kind: "invite-link", token: SHAREABLE_TOKEN, href: `/invite-link#token=${SHAREABLE_TOKEN}` };

        expect(parseInviteInput(`<${ORIGIN}/invite-link#token=${SHAREABLE_TOKEN}>`)).toEqual(expected);
        expect(parseInviteInput(`(${ORIGIN}/invite-link#token=${SHAREABLE_TOKEN})`)).toEqual(expected);
        expect(parseInviteInput(`${ORIGIN}/invite-link#token=${SHAREABLE_TOKEN}.`)).toEqual(expected);
        expect(parseInviteInput(`[Join](${ORIGIN}/invite-link#token=${SHAREABLE_TOKEN})`)).toEqual(expected);
        expect(parseInviteInput(`"${EMAILED_TOKEN}"`)).toEqual({
            kind: "invite",
            token: EMAILED_TOKEN,
            href: `/invite#token=${EMAILED_TOKEN}`,
        });
    });

    it("uses the first marker, so a query string cannot hijack the destination", () => {
        expect(
            parseInviteInput(`${ORIGIN}/invite#token=${EMAILED_TOKEN}/invite-link#token=${SHAREABLE_TOKEN}`),
        ).toEqual({ kind: "invite", token: EMAILED_TOKEN, href: `/invite#token=${EMAILED_TOKEN}` });
    });

    it("accepts tokens at the length bounds and refuses them just outside", () => {
        expect(parseInviteInput("a".repeat(16))?.token).toHaveLength(16);
        expect(parseInviteInput("a".repeat(512))?.token).toHaveLength(512);
        expect(parseInviteInput("a".repeat(15))).toBeNull();
        expect(parseInviteInput("a".repeat(513))).toBeNull();
    });

    it("refuses junk instead of navigating somewhere that can only report a missing invite", () => {
        expect(parseInviteInput("")).toBeNull();
        expect(parseInviteInput("   ")).toBeNull();
        expect(parseInviteInput("not an invite link")).toBeNull();
        expect(parseInviteInput(ORIGIN)).toBeNull();
        expect(parseInviteInput(`${ORIGIN}/dashboard`)).toBeNull();
        expect(parseInviteInput("abc123")).toBeNull();
    });

    it("refuses a link shape that carries no token", () => {
        expect(parseInviteInput(`${ORIGIN}/invite/`)).toBeNull();
        expect(parseInviteInput(`${ORIGIN}/invite-link/`)).toBeNull();
        expect(parseInviteInput(`${ORIGIN}/invite-link/?token=${SHAREABLE_TOKEN}`)).toBeNull();
    });

    it("cannot be steered to a path or origin of the pasted value's choosing", () => {
        const hostile = [
            `//evil.example.com/invite#token=${EMAILED_TOKEN}`,
            `https://evil.example.com/invite-link#token=${SHAREABLE_TOKEN}`,
            `${ORIGIN}/invite#token=${EMAILED_TOKEN}/../../settings/members`,
            `javascript:alert(1)//invite#token=${EMAILED_TOKEN}`,
            `${ORIGIN}/invite#token=${EMAILED_TOKEN}:evil`,
        ];

        for (const value of hostile) {
            const parsed = parseInviteInput(value);

            expect(parsed?.href).toMatch(/^\/(?:invite|invite-link)#token=[A-Za-z0-9_-]+$/);
        }
    });
});

describe("the onboarding join box", () => {
    it("parses the pasted value instead of hardcoding the emailed marker", () => {
        const page = source(ONBOARDING_FORM);

        expect(page).toContain("parseInviteInput(");
        expect(page).not.toContain('const marker = "/invite/"');
        expect(page).toContain("router.push(parsed.href)");
    });

    it("falls back to the shareable-link preview so a bare token of either kind resolves", () => {
        const page = source(INVITE_PAGE);

        expect(page).toContain("exchangeInviteLinkToken");
        expect(page).toContain('window.location.replace("/invite-link")');
    });

    it("localizes the rejection message in both supported locales", () => {
        for (const locale of ["en", "ja"] as const) {
            expect(onboardingCopy(locale).joinInvalid).toBeTruthy();
        }
    });
});
