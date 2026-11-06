import { afterEach, describe, expect, it, vi } from "vitest";

import { getContactsFromCookie, getContactTemperaturesFromCookie, getDealsFromCookie, getMyWorkReadResultFromCookie, getMyWorkResultFromCookie } from "@/app/lib/api";

const COOKIE = "connex_workspace=7";

afterEach(() => vi.unstubAllGlobals());

describe("My Work request path", () => {
    it.each([401, 403, 503])("preserves a %s section failure without inventing history", async (status) => {
        vi.stubGlobal("fetch", vi.fn(async () => new Response("{}", { status })));
        expect(await getMyWorkReadResultFromCookie("history", COOKIE)).toEqual({ ok: false, status });
    });

    it("bounds requests independently of the report-sized workspace collections", async () => {
        const paths: string[] = [];
        vi.stubGlobal("fetch", vi.fn(async (input: string | URL | Request) => {
            const url = new URL(String(input), "http://localhost:8080");
            paths.push(url.pathname);
            const total = url.pathname === "/api/persons/page" ? 5000 : 2000;
            const page = Number(url.searchParams.get("page") ?? 1);
            const body = url.pathname.endsWith("/page")
                ? { total, items: Array.from({ length: 100 }, (_, index) => ({ id: (page - 1) * 100 + index + 1 })) }
                : [];
            return new Response(JSON.stringify(body), { headers: { "Content-Type": "application/json" } });
        }));
        const [contacts] = await Promise.all([getContactsFromCookie(COOKIE), getDealsFromCookie(COOKIE)]);
        await getContactTemperaturesFromCookie(COOKIE, contacts.map((contact) => contact.id));
        const legacyRequests = paths.length;
        expect(paths.filter((path) => path === "/api/persons/page")).toHaveLength(50);
        expect(paths.filter((path) => path === "/api/deals/page")).toHaveLength(20);
        expect(legacyRequests).toBe(120);
        paths.length = 0;
        await Promise.all([
            getMyWorkReadResultFromCookie("relationships", COOKIE),
            getMyWorkReadResultFromCookie("history", COOKIE),
            getMyWorkReadResultFromCookie("pulse", COOKIE),
            getMyWorkReadResultFromCookie("deal-kpis", COOKIE, "JPY"),
            getMyWorkReadResultFromCookie("risks", COOKIE),
            getMyWorkResultFromCookie(COOKIE),
        ]);
        expect(paths).toHaveLength(6);
        expect(paths.every((path) => path.startsWith("/api/my-work"))).toBe(true);
    });
});
