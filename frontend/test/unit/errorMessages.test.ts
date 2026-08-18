import { readFileSync } from "node:fs";
import { join } from "node:path";

import { createTranslator } from "next-intl";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { ApiError } from "@/app/lib/api";
import { type MessageTranslator, toastApiError, userMessageFor } from "@/app/lib/errorMessages";
import { isAuthPath, redirectToSignIn, signInHref } from "@/app/lib/sessionExpiry";
import { toastError } from "@/app/lib/toast";

vi.mock("@/app/lib/toast", () => ({
    toastError: vi.fn(),
    toastSuccess: vi.fn(),
    toastWarn: vi.fn(),
    toastInfo: vi.fn(),
}));

const MESSAGES_ROOT = join(process.cwd(), "messages");
const POISONED_MESSAGE = "Requires the RULE_MANAGE permission for tenant 42";

type MessageValue = string | { [key: string]: MessageValue };

function isMessageTree(value: unknown): value is { [key: string]: MessageValue } {
    return typeof value === "object" && value !== null && !Array.isArray(value);
}

function errorMessages(locale: string): { [key: string]: MessageValue } {
    const parsed: unknown = JSON.parse(readFileSync(join(MESSAGES_ROOT, locale, "errors.json"), "utf8"));
    if (!isMessageTree(parsed)) throw new Error(`messages/${locale}/errors.json is not a JSON object`);
    return parsed;
}

function flattenKeys(tree: { [key: string]: MessageValue }, prefix = ""): string[] {
    return Object.entries(tree).flatMap(([key, value]) =>
        isMessageTree(value) ? flattenKeys(value, `${prefix}${key}.`) : [`${prefix}${key}`],
    );
}

function translator(locale: string): MessageTranslator {
    const messages: { [key: string]: MessageValue } = {
        ...errorMessages(locale),
        Contacts: { toastFailedSave: "Couldn't save the contact" },
    };
    return createTranslator({ locale, messages });
}

const t = translator("en");
const ja = translator("ja");

function describedBy(error: unknown, fallbackKey?: string): string {
    const message = userMessageFor(error, t, fallbackKey);
    if (message === null) throw new Error("expected user-facing copy, got a sign-in redirect");
    return message.description;
}

const assign = vi.fn();

beforeEach(() => {
    vi.stubGlobal("window", {
        location: { pathname: "/records/contacts", search: "?view=all", assign },
    });
});

afterEach(() => {
    vi.unstubAllGlobals();
    vi.clearAllMocks();
});

describe("userMessageFor status mapping", () => {
    it.each([
        [400, "Some of what you entered can't be accepted. Check the details and try again."],
        [422, "Some of what you entered can't be accepted. Check the details and try again."],
        [403, "You don't have permission to do that here. Ask a workspace admin."],
        [404, "That record is no longer available."],
        [410, "That record is no longer available."],
        [409, "Someone else changed this just now. Reload and try again."],
        [429, "That's a lot of requests at once. Wait a moment and try again."],
        [500, "Something went wrong on our side. Nothing was lost — try again in a moment."],
        [503, "Something went wrong on our side. Nothing was lost — try again in a moment."],
    ])("describes %i in the product's error dialect", (status, expected) => {
        expect(describedBy(new ApiError(POISONED_MESSAGE, status))).toBe(expected);
    });

    it("fails safe to generic copy on a status it has no rule for", () => {
        expect(describedBy(new ApiError(POISONED_MESSAGE, 418))).toBe(
            "Something went wrong. Nothing was lost — try again.",
        );
    });

    it("reads a network failure as being offline", () => {
        expect(describedBy(new TypeError("Failed to fetch"))).toBe(
            "We couldn't reach Connex. Check your connection and try again.",
        );
    });

    it("fails safe to generic copy on something that is not an error at all", () => {
        expect(describedBy("boom")).toBe("Something went wrong. Nothing was lost — try again.");
    });
});

describe("userMessageFor code mapping", () => {
    it("prefers a registered code over the status rule", () => {
        const error = new ApiError(POISONED_MESSAGE, 409, "IDENTITY_COLLISION_REPORT_TIMEOUT");

        expect(describedBy(error)).toBe(
            "That report is taking longer than expected. Try again in a moment.",
        );
    });

    it("falls back to the status rule on a code it does not know", () => {
        const error = new ApiError(POISONED_MESSAGE, 409, "SOME_FUTURE_BACKEND_CODE");

        expect(describedBy(error)).toBe("Someone else changed this just now. Reload and try again.");
    });
});

describe("userMessageFor support reference", () => {
    it("appends the reference so support can find the incident", () => {
        const error = new ApiError(POISONED_MESSAGE, 500, undefined, undefined, "b23f91");

        expect(describedBy(error)).toBe(
            "Something went wrong on our side. Nothing was lost — try again in a moment. Reference: b23f91",
        );
    });

    it("uses the Japanese reference wording when the user reads Japanese", () => {
        const error = new ApiError(POISONED_MESSAGE, 500, undefined, undefined, "b23f91");
        const message = userMessageFor(error, ja);

        expect(message?.description).toContain("参照コード: b23f91");
    });

    it("says nothing about a reference when the failure carries none", () => {
        expect(describedBy(new ApiError(POISONED_MESSAGE, 500))).not.toContain("Reference");
    });
});

describe("userMessageFor titles", () => {
    it("uses the caller's operation-specific title", () => {
        const message = userMessageFor(new ApiError(POISONED_MESSAGE, 403), t, "Contacts.toastFailedSave");

        expect(message?.title).toBe("Couldn't save the contact");
    });

    it("falls back to the generic title rather than echoing a key that does not resolve", () => {
        const message = userMessageFor(new ApiError(POISONED_MESSAGE, 403), t, "Contacts.notAKey");

        expect(message?.title).toBe("Couldn't complete that");
    });

    it("uses the generic title when the caller names no operation", () => {
        expect(userMessageFor(new ApiError(POISONED_MESSAGE, 403), t)?.title).toBe("Couldn't complete that");
    });
});

describe("backend text containment", () => {
    const statuses = [400, 401, 403, 404, 409, 410, 418, 422, 429, 500, 503];

    it.each(statuses)("never lets the backend's own %i text reach the user", (status) => {
        const error = new ApiError(POISONED_MESSAGE, status, "SOME_FUTURE_BACKEND_CODE", undefined, "b23f91");
        const message = userMessageFor(error, t, "Contacts.toastFailedSave");
        const rendered = message === null ? "" : `${message.title} ${message.description}`;

        expect(rendered).not.toContain(POISONED_MESSAGE);
        expect(rendered).not.toContain("RULE_MANAGE");
        expect(rendered).not.toContain("tenant");
        expect(rendered).not.toContain(String(status));
    });

    it("never echoes an unmapped code", () => {
        const error = new ApiError(POISONED_MESSAGE, 409, "SOME_FUTURE_BACKEND_CODE");

        expect(describedBy(error)).not.toContain("SOME_FUTURE_BACKEND_CODE");
    });
});

describe("expired sessions", () => {
    it("takes the user to sign in instead of reporting a failure", () => {
        expect(userMessageFor(new ApiError("Request failed (401)", 401, undefined, undefined, undefined, true), t))
            .toBeNull();
        expect(assign).toHaveBeenCalledWith("/auth/login?redirect=%2Frecords%2Fcontacts%3Fview%3Dall");
    });

    it("reads a bodyless 403 as a missing session too", () => {
        expect(userMessageFor(new ApiError("Request failed (403)", 403, undefined, undefined, undefined, true), t))
            .toBeNull();
        expect(assign).toHaveBeenCalledTimes(1);
    });

    it("still explains a 403 that carries a genuine refusal", () => {
        expect(describedBy(new ApiError(POISONED_MESSAGE, 403))).toBe(
            "You don't have permission to do that here. Ask a workspace admin.",
        );
        expect(assign).not.toHaveBeenCalled();
    });

    it("does not toast an expired session", () => {
        toastApiError(new ApiError("Request failed (401)", 401), t, "Contacts.toastFailedSave");

        expect(toastError).not.toHaveBeenCalled();
        expect(assign).toHaveBeenCalledTimes(1);
    });
});

describe("redirectToSignIn", () => {
    it("remembers the path and query the user was on", () => {
        expect(signInHref("/records/deals", "?stage=won")).toBe(
            "/auth/login?redirect=%2Frecords%2Fdeals%3Fstage%3Dwon",
        );
    });

    it("does not interrupt an authentication page, so it cannot loop", () => {
        vi.stubGlobal("window", { location: { pathname: "/auth/login", search: "", assign } });

        expect(redirectToSignIn()).toBe(false);
        expect(assign).not.toHaveBeenCalled();
        expect(isAuthPath("/auth/reset-password")).toBe(true);
        expect(isAuthPath("/records/contacts")).toBe(false);
    });

    it("does nothing while rendering on the server", () => {
        vi.stubGlobal("window", undefined);

        expect(redirectToSignIn()).toBe(false);
    });
});

describe("toastApiError", () => {
    it("renders the mapped title and description through the branded toast", () => {
        toastApiError(new ApiError(POISONED_MESSAGE, 409), t, "Contacts.toastFailedSave");

        expect(toastError).toHaveBeenCalledWith("Couldn't save the contact", {
            description: "Someone else changed this just now. Reload and try again.",
        });
    });
});

describe("ApiErrors message catalogue", () => {
    it("ships the same keys in English and Japanese", () => {
        const english = errorMessages("en").ApiErrors;
        const japanese = errorMessages("ja").ApiErrors;
        if (!isMessageTree(english) || !isMessageTree(japanese)) throw new Error("ApiErrors namespace is missing");

        expect(flattenKeys(japanese).sort()).toEqual(flattenKeys(english).sort());
    });

    it("is written natively in Japanese rather than left in English", () => {
        const japanese = errorMessages("ja").ApiErrors;
        if (!isMessageTree(japanese)) throw new Error("ApiErrors namespace is missing");

        const untranslated = Object.entries(japanese)
            .filter(([key]) => key !== "reference")
            .filter(([, value]) => typeof value === "string" && !/[ぁ-んァ-ヶ一-龯]/.test(value))
            .map(([key]) => key);

        expect(untranslated).toEqual([]);
    });
});
