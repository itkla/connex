import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const { serverLocale, requestHeaders } = vi.hoisted(() => ({
    serverLocale: vi.fn(),
    requestHeaders: vi.fn(),
}));

vi.mock("next-intl/server", () => ({
    getLocale: serverLocale,
    getRequestConfig: <Configuration>(configuration: Configuration) => configuration,
}));
vi.mock("next/headers", () => ({
    headers: requestHeaders,
    cookies: () => Promise.resolve({ get: () => undefined }),
}));

import {
    downloadCsv,
    getCapabilities,
    getEffectivePermissionsFromCookie,
    getEffectivePermissionsResultFromCookie,
    getUnsubscribeInfo,
    uploadCurrentUserProfilePicture,
} from "@/app/lib/api";
import requestConfig from "@/i18n/request";

function stubFetch(profileTimeZone = "Asia/Tokyo") {
    const requests: { path: string; headers: Headers }[] = [];
    const responses: Record<string, unknown> = {
        "/api/auth/me": { id: 1, locale: "ja", timezone: profileTimeZone },
        "/api/workspaces": { activeWorkspaceId: 2, workspaces: [{ id: 2, timezone: "UTC" }] },
        "/api/users/me/profile-picture": { profilePictureUrl: "/portrait" },
        "/api/delivery/unsubscribe": { flowId: "c".repeat(64), channel: "email", address: "masked", unsubscribed: false },
    };
    vi.stubGlobal("fetch", (input: RequestInfo | URL, init: RequestInit = {}) => {
        const path = new URL(input instanceof Request ? input.url : String(input), "https://connex.test").pathname;
        requests.push({ path, headers: new Headers(init.headers) });
        const body = responses[path] ?? [];
        return Promise.resolve(new Response(JSON.stringify(body), { status: 200 }));
    });
    return requests;
}

function browserDocument(lang: string, cookie: string) {
    const anchor = { href: "", download: "", click: vi.fn(), remove: vi.fn() };
    const document = {
        documentElement: { lang },
        cookie,
        createElement: () => anchor,
        body: { appendChild: vi.fn() },
    };
    vi.stubGlobal("document", document);
    vi.spyOn(URL, "createObjectURL").mockReturnValue("blob:download");
    vi.spyOn(URL, "revokeObjectURL").mockImplementation(() => undefined);
    return document;
}

beforeEach(() => {
    vi.clearAllMocks();
    serverLocale.mockResolvedValue("ja");
    requestHeaders.mockResolvedValue(new Headers({ cookie: "JSESSIONID=session", "accept-language": "ja-JP" }));
});

afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
});

describe("browser API language follows the rendered page", () => {
    it.each([
        { name: "Japanese profile without a locale cookie", lang: "ja", cookie: "JSESSIONID=session" },
        { name: "explicit English cookie", lang: "en", cookie: "JSESSIONID=session; NEXT_LOCALE=en" },
        { name: "explicit Japanese cookie", lang: "ja", cookie: "JSESSIONID=session; NEXT_LOCALE=ja" },
        { name: "signed-out Japanese browser inference", lang: "ja", cookie: "" },
        { name: "cookie changed before the UI refreshes", lang: "en", cookie: "NEXT_LOCALE=ja" },
    ])("sends the rendered language for $name without persisting inference", async ({ lang, cookie }) => {
        const document = browserDocument(lang, cookie);
        const requests = stubFetch();

        await getCapabilities();

        expect(requests[0]?.headers.get("Accept-Language")).toBe(lang);
        expect(document.cookie).toBe(cookie);
        expect(serverLocale).not.toHaveBeenCalled();
    });

    it("updates API language when the rendered page language changes", async () => {
        const document = browserDocument("ja", "JSESSIONID=session");
        const requests = stubFetch();
        await getCapabilities();
        document.documentElement.lang = "en";
        await getCapabilities();
        expect(requests.map(({ headers }) => headers.get("Accept-Language"))).toEqual(["ja", "en"]);
    });

    it.each(["csv", "multipart", "unsubscribe"])("uses the rendered language for %s requests", async (kind) => {
        browserDocument("ja", "JSESSIONID=session");
        const requests = stubFetch();

        if (kind === "csv") await downloadCsv("/api/exports/products", "products.csv");
        else if (kind === "multipart") await uploadCurrentUserProfilePicture(new File(["image"], "portrait.png"));
        else await getUnsubscribeInfo();

        expect(requests.length).toBeGreaterThan(0);
        expect(requests.every(({ headers }) => headers.get("Accept-Language") === "ja")).toBe(true);
    });
});

describe("server API language follows the request locale", () => {
    for (const cookie of ["JSESSIONID=session", "JSESSIONID=session; NEXT_LOCALE=ja"]) {
        it.each(["direct", "safe cookie read", "result cookie read"])(`uses next-intl for %s with ${cookie}`, async (kind) => {
            const requests = stubFetch();

            if (kind === "direct") await getCapabilities({ headers: { cookie } });
            else if (kind === "safe cookie read") await getEffectivePermissionsFromCookie(cookie);
            else await getEffectivePermissionsResultFromCookie(cookie);

            expect(requests[0]?.headers.get("Accept-Language")).toBe("ja");
            expect(requests[0]?.headers.get("cookie")).toBe(cookie);
        });
    }

    it("sends the browser-inferred locale for a signed-out server request", async () => {
        const requests = stubFetch();
        await getCapabilities();
        expect(requests[0]?.headers.get("Accept-Language")).toBe("ja");
    });

    it("keeps concurrent server request languages independent", async () => {
        const requests = stubFetch();
        serverLocale.mockResolvedValueOnce("ja").mockResolvedValueOnce("en");
        await Promise.all([getCapabilities(), getCapabilities()]);
        expect(requests.map(({ headers }) => headers.get("Accept-Language"))).toEqual(["ja", "en"]);
    });

    it("retains cookie fallback outside a next-intl request context", async () => {
        const requests = stubFetch();
        serverLocale.mockRejectedValue(new Error("No request context"));
        await getCapabilities({ headers: { cookie: "NEXT_LOCALE=ja" } });
        expect(requests[0]?.headers.get("Accept-Language")).toBe("ja");
    });

    it.each(["Asia/Tokyo", "invalid"])("resolves the profile once without recursion with zone %s", async (zone) => {
        const requests = stubFetch(zone);
        serverLocale.mockImplementation(async () => (await requestConfig({ requestLocale: Promise.resolve(undefined) })).locale);

        await getCapabilities();

        expect(requests.filter(({ path }) => path === "/api/auth/me")).toHaveLength(1);
        expect(requests.find(({ path }) => path === "/api/capabilities")?.headers.get("Accept-Language")).toBe("ja");
        expect(serverLocale).toHaveBeenCalledTimes(1);
        expect(requests.filter(({ path }) => path === "/api/workspaces")).toHaveLength(zone === "invalid" ? 1 : 0);
    });
});
