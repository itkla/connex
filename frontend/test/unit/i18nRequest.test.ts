import { beforeEach, describe, expect, it, vi } from "vitest";

const requestCookies = vi.hoisted(() => vi.fn());

vi.mock("next/headers", () => ({
    cookies: requestCookies,
}));

vi.mock("next-intl/server", () => ({
    getRequestConfig: <Configuration>(configuration: Configuration) => configuration,
}));

import requestConfig from "@/i18n/request";

beforeEach(() => {
    vi.clearAllMocks();
    requestCookies.mockResolvedValue({
        get: () => ({ value: "en" }),
    });
});

describe("next-intl request locale", () => {
    it("loads an explicit server translation locale without consulting the cookie", async () => {
        const config = await requestConfig({
            locale: "ja",
            requestLocale: Promise.resolve("en"),
        });

        expect(config.locale).toBe("ja");
        expect(JSON.stringify(config.messages)).toContain("ドキュメントの確認");
        expect(requestCookies).not.toHaveBeenCalled();
    });

    it("honors requestLocale when no explicit server locale is present", async () => {
        const config = await requestConfig({
            requestLocale: Promise.resolve("ja"),
        });

        expect(config.locale).toBe("ja");
        expect(JSON.stringify(config.messages)).toContain("ドキュメントの確認");
        expect(requestCookies).not.toHaveBeenCalled();
    });

    it("falls back to the locale cookie only when next-intl requested no locale", async () => {
        const config = await requestConfig({
            requestLocale: Promise.resolve(undefined),
        });

        expect(config.locale).toBe("en");
        expect(requestCookies).toHaveBeenCalledTimes(1);
    });
});
