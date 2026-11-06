import { beforeEach, describe, expect, it, vi } from "vitest";

const { requestCookies, requestHeaders, profile, workspaces } = vi.hoisted(() => ({
    requestCookies: vi.fn(),
    requestHeaders: vi.fn(),
    profile: vi.fn(),
    workspaces: vi.fn(),
}));

vi.mock("@/app/lib/api", () => ({
    getCurrentUserResultFromCookie: profile,
    getMyWorkspacesResultFromCookie: workspaces,
}));

vi.mock("next/headers", () => ({
    cookies: requestCookies,
    headers: requestHeaders,
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
    requestHeaders.mockResolvedValue(new Headers({ "Accept-Language": "ja-JP,ja;q=0.9,en;q=0.8" }));
    profile.mockResolvedValue({ ok: true, data: { locale: "en", timezone: "Asia/Tokyo" } });
    workspaces.mockResolvedValue({ ok: false });
});

describe("next-intl request locale", () => {
    it("loads an explicit server translation locale without consulting the cookie", async () => {
        const config = await requestConfig({
            locale: "ja",
            requestLocale: Promise.resolve("en"),
        });

        expect(config.locale).toBe("ja");
        expect(config.timeZone).toBe("UTC");
        expect(JSON.stringify(config.messages)).toContain("ドキュメントの確認");
        expect(requestCookies).not.toHaveBeenCalled();
        expect(profile).not.toHaveBeenCalled();
    });

    it("honors requestLocale when no explicit server locale is present", async () => {
        const config = await requestConfig({
            requestLocale: Promise.resolve("ja"),
        });

        expect(config.locale).toBe("ja");
        expect(JSON.stringify(config.messages)).toContain("ドキュメントの確認");
        expect(requestCookies).not.toHaveBeenCalled();
        expect(profile).not.toHaveBeenCalled();
    });

    it("falls back to the locale cookie only when next-intl requested no locale", async () => {
        const config = await requestConfig({
            requestLocale: Promise.resolve(undefined),
        });

        expect(config.locale).toBe("en");
        expect(requestCookies).toHaveBeenCalledTimes(1);
        expect(profile).not.toHaveBeenCalled();
    });

    it.each([
        ["ja-JP,ja;q=0.9,en;q=0.8", "ja"],
        ["ja;q=0.3,en-GB;q=0.9", "en"],
        ["fr-FR,ja-JP;q=0.8", "ja"],
        ["fr-FR,de;q=0.8", "en"],
        ["ja;q=0,en;q=0.5", "en"],
        ["ja;q=oops", "en"],
        ["*", "en"],
        ["", "en"],
    ])("resolves a first visit with Accept-Language %j to %s", async (acceptLanguage, locale) => {
        requestCookies.mockResolvedValue({ get: () => undefined });
        requestHeaders.mockResolvedValue(new Headers({ "Accept-Language": acceptLanguage }));

        const config = await requestConfig({ requestLocale: Promise.resolve(undefined) });

        expect(config.locale).toBe(locale);
        expect(config.timeZone).toBe("UTC");
        expect(profile).not.toHaveBeenCalled();
        expect(config.now).toBeInstanceOf(Date);
        expect(JSON.stringify(config.messages)).toContain(locale === "ja" ? "配信停止" : "Unsubscribe");
    });

    it("uses the browser language when the locale cookie is unsupported", async () => {
        requestCookies.mockResolvedValue({ get: () => ({ value: "fr" }) });

        const config = await requestConfig({ requestLocale: Promise.resolve(undefined) });

        expect(config.locale).toBe("ja");
    });

    it("keeps an explicit Japanese cookie ahead of an English browser", async () => {
        requestCookies.mockResolvedValue({ get: () => ({ value: "ja" }) });
        requestHeaders.mockResolvedValue(new Headers({ "Accept-Language": "en-US" }));

        const config = await requestConfig({ requestLocale: Promise.resolve(undefined) });

        expect(config.locale).toBe("ja");
        expect(profile).not.toHaveBeenCalled();
    });

    it("keeps an explicit English server locale ahead of every Japanese preference", async () => {
        requestCookies.mockResolvedValue({ get: () => ({ value: "ja" }) });

        const config = await requestConfig({ locale: "en", requestLocale: Promise.resolve("ja") });

        expect(config.locale).toBe("en");
        expect(requestCookies).not.toHaveBeenCalled();
        expect(profile).not.toHaveBeenCalled();
    });

    it("uses English when the locale cookie and browser language are both absent", async () => {
        requestCookies.mockResolvedValue({ get: () => undefined });
        requestHeaders.mockResolvedValue(new Headers());

        const config = await requestConfig({ requestLocale: Promise.resolve(undefined) });

        expect(config.locale).toBe("en");
    });

    it("keeps the English fallback for an unsupported explicit server locale", async () => {
        const config = await requestConfig({ locale: "fr", requestLocale: Promise.resolve(undefined) });

        expect(config.locale).toBe("en");
        expect(requestCookies).not.toHaveBeenCalled();
        expect(profile).not.toHaveBeenCalled();
    });

    it.each(["/unsubscribe", "/invite", "/invite-link"])(
        "uses the signed-in English profile before a Japanese browser on %s with one profile read",
        async (pathname) => {
            requestCookies.mockResolvedValue({ get: () => undefined });
            requestHeaders.mockResolvedValue(new Headers({
                cookie: "JSESSIONID=session",
                "accept-language": "ja-JP",
                "x-pathname": pathname,
            }));

            const config = await requestConfig({ requestLocale: Promise.resolve(undefined) });

            expect(config.locale).toBe("en");
            expect(config.timeZone).toBe("Asia/Tokyo");
            expect(profile).toHaveBeenCalledExactlyOnceWith("JSESSIONID=session");
            expect(workspaces).not.toHaveBeenCalled();
        },
    );

    it.each([{ ok: false }, { ok: true, data: null }])(
        "uses the browser language when the session profile is absent or unavailable: %j",
        async (result) => {
            requestCookies.mockResolvedValue({ get: () => undefined });
            requestHeaders.mockResolvedValue(new Headers({ cookie: "JSESSIONID=session", "accept-language": "ja-JP" }));
            profile.mockResolvedValue(result);

            const config = await requestConfig({ requestLocale: Promise.resolve(undefined) });

            expect(config.locale).toBe("ja");
            expect(config.timeZone).toBe("UTC");
            expect(profile).toHaveBeenCalledTimes(1);
            expect(workspaces).not.toHaveBeenCalled();
        },
    );

    it("uses the browser language without throwing when the profile read rejects", async () => {
        requestCookies.mockResolvedValue({ get: () => undefined });
        requestHeaders.mockResolvedValue(new Headers({ cookie: "JSESSIONID=session", "accept-language": "ja-JP" }));
        profile.mockRejectedValue(new TypeError("Profile unavailable"));

        const config = await requestConfig({ requestLocale: Promise.resolve(undefined) });

        expect(config.locale).toBe("ja");
        expect(config.timeZone).toBe("UTC");
        expect(profile).toHaveBeenCalledTimes(1);
    });

    it.each([
        { explicit: "en", cookie: undefined, saved: "ja", browser: "ja-JP", expected: "en" },
        { explicit: undefined, cookie: "ja", saved: "en", browser: "en-US", expected: "ja" },
        { explicit: undefined, cookie: "en", saved: "ja", browser: "ja-JP", expected: "en" },
        { explicit: undefined, cookie: undefined, saved: "ja", browser: "en-US", expected: "ja" },
        { explicit: undefined, cookie: undefined, saved: "fr", browser: "ja-JP", expected: "ja" },
    ])("preserves authenticated locale precedence: %j", async ({ explicit, cookie, saved, browser, expected }) => {
        requestCookies.mockResolvedValue({ get: () => cookie === undefined ? undefined : { value: cookie } });
        requestHeaders.mockResolvedValue(new Headers({ cookie: "JSESSIONID=session", "accept-language": browser }));
        profile.mockResolvedValue({ ok: true, data: { locale: saved, timezone: "Asia/Tokyo" } });

        const config = await requestConfig({ locale: explicit, requestLocale: Promise.resolve(undefined) });

        expect(config.locale).toBe(expected);
        expect(config.timeZone).toBe("Asia/Tokyo");
        expect(profile).toHaveBeenCalledTimes(1);
    });

    it("retains the profile language if workspace time-zone fallback rejects", async () => {
        requestCookies.mockResolvedValue({ get: () => undefined });
        requestHeaders.mockResolvedValue(new Headers({ cookie: "JSESSIONID=session", "accept-language": "ja-JP" }));
        profile.mockResolvedValue({ ok: true, data: { locale: "en", timezone: "invalid" } });
        workspaces.mockRejectedValue(new TypeError("Workspaces unavailable"));

        const config = await requestConfig({ requestLocale: Promise.resolve(undefined) });

        expect(config.locale).toBe("en");
        expect(config.timeZone).toBe("UTC");
        expect(profile).toHaveBeenCalledTimes(1);
        expect(workspaces).toHaveBeenCalledTimes(1);
    });
});
