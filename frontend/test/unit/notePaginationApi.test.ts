import { afterEach, describe, expect, it, vi } from "vitest";
import { getNotesForDeal, getNotesForPerson, getNotesReferencing, getUserNotes, getUserNotesPage, getUserNoteActivityResultFromCookie } from "@/app/lib/api";

afterEach(() => vi.unstubAllGlobals());

describe("note page API parameters", () => {
    it("transmits the update-time and id cursor on every timeline route", async () => {
        const fetch = vi.fn().mockResolvedValue(new Response("[]", {
            status: 200, headers: { "content-type": "application/json" },
        }));
        vi.stubGlobal("fetch", fetch);
        const cursor = { page: 2, size: 25, beforeAt: "2026-09-14 13:14:15", beforeId: 29 };
        for (const read of [getNotesForPerson, getNotesForDeal, getUserNotes]) {
            fetch.mockResolvedValueOnce(new Response("[]", { headers: { "content-type": "application/json" } }));
            await read(31, cursor);
            const url = new URL(String(fetch.mock.lastCall?.[0]), "http://localhost");
            expect(url.searchParams.get("beforeAt")).toBe(cursor.beforeAt);
            expect(url.searchParams.get("beforeId")).toBe("29");
            expect(url.searchParams.get("page")).toBe("2");
        }
    });

    it("reads the body-free pulse aggregate separately with the authenticated workspace cookie", async () => {
        const fetch = vi.fn().mockResolvedValue(new Response('[{"date":"2026-09-15","count":105}]', {
            headers: { "content-type": "application/json" },
        }));
        vi.stubGlobal("fetch", fetch);
        expect(await getUserNoteActivityResultFromCookie(51, "connex_workspace=7"))
            .toEqual({ ok: true, data: [{ date: "2026-09-15", count: 105 }] });
        expect(fetch).toHaveBeenCalledWith(expect.stringContaining("/api/users/51/notes/pulse"),
            expect.objectContaining({ method: "GET", cache: "no-store" }));
    });

    it("forwards the page, size, and request scope to all collection routes", async () => {
        const fetch = vi.fn().mockImplementation(() => Promise.resolve(new Response("[]", {
            status: 200, headers: { "content-type": "application/json" },
        })));
        vi.stubGlobal("fetch", fetch);
        const init = { headers: { "X-Workspace-Id": "7" } };
        await getNotesForPerson(31, { page: 81, size: 25 }, init);
        await getNotesForDeal(41, { page: 2, size: 100 }, init);
        await getNotesReferencing("person", 31, { page: 2, size: 25 }, init);
        await getUserNotes(51, { page: 5, size: 25 }, init);
        await getUserNotesPage(51, { page: 2, size: 100 }, init);
        expect(fetch).toHaveBeenNthCalledWith(4, expect.stringContaining("/api/users/51/notes?page=5&size=25"), expect.objectContaining({ method: "GET" }));
        expect(fetch).toHaveBeenNthCalledWith(5, expect.stringContaining("/api/users/51/notes/page?page=2&size=100"), expect.objectContaining({ method: "GET" }));
        expect(fetch).toHaveBeenNthCalledWith(1, expect.stringContaining("/api/persons/31/notes?page=81&size=25"), expect.objectContaining({ method: "GET" }));
        expect(fetch).toHaveBeenNthCalledWith(2, expect.stringContaining("/api/deals/41/notes?page=2&size=100"), expect.objectContaining({ method: "GET" }));
        expect(fetch).toHaveBeenNthCalledWith(3, expect.stringContaining("/api/notes/referencing?refType=person&refId=31&page=2&size=25"), expect.objectContaining({ method: "GET" }));
    });
});
