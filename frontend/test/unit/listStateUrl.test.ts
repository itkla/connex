import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
    MAX_URL_PAGE_SIZE,
    SERVER_RECORDS_URL_KEYS,
    parseListInt,
    parseListQuery,
    writeListQueryToUrl,
    writeListStateToUrl,
    writeSavedViewToUrl,
} from "@/app/hooks/listStateUrl";

describe("parseListQuery", () => {
    it("canonicalizes null and whitespace-only values to the empty string", () => {
        expect(parseListQuery(null)).toBe("");
        expect(parseListQuery("   ")).toBe("");
        expect(parseListQuery(" acme ")).toBe("acme");
    });
});

describe("parseListInt", () => {
    it("returns the fallback for null, non-integer, and non-positive input", () => {
        expect(parseListInt(null, 1)).toBe(1);
        expect(parseListInt("abc", 1)).toBe(1);
        expect(parseListInt("3.5", 1)).toBe(1);
        expect(parseListInt("0", 1)).toBe(1);
        expect(parseListInt("-2", 7)).toBe(7);
    });

    it("passes valid integers through and clamps to max", () => {
        expect(parseListInt("4", 1)).toBe(4);
        expect(parseListInt("99999", 25, MAX_URL_PAGE_SIZE)).toBe(MAX_URL_PAGE_SIZE);
    });
});

describe("URL writers (multi-writer coexistence contract)", () => {
    const replaceState = vi.fn();

    function stubLocation(search: string): void {
        vi.stubGlobal("window", {
            location: { search },
            history: { replaceState },
        });
    }

    beforeEach(() => replaceState.mockClear());
    afterEach(() => vi.unstubAllGlobals());

    it("declares exactly the keys the server-records writer owns", () => {
        expect([...SERVER_RECORDS_URL_KEYS]).toEqual(["q", "sort", "dir", "page", "size"]);
    });

    it("preserves params owned by other writers when writing list state", () => {
        stubLocation("?view=table&peek=person%3A7&sv=1%3A2");
        writeListStateToUrl("/records/contacts", { q: "acme", sort: "name", dir: "asc", page: 2, size: 25 }, 25);
        expect(replaceState).toHaveBeenCalledTimes(1);
        const url = new URL(`http://x${replaceState.mock.calls[0][2]}`);
        expect(url.pathname).toBe("/records/contacts");
        expect(url.searchParams.get("view")).toBe("table");
        expect(url.searchParams.get("peek")).toBe("person:7");
        expect(url.searchParams.get("sv")).toBe("1:2");
        expect(url.searchParams.get("q")).toBe("acme");
        expect(url.searchParams.get("sort")).toBe("name");
        expect(url.searchParams.get("page")).toBe("2");
    });

    it("drops default values from the URL", () => {
        stubLocation("?q=old&sort=name&dir=desc&page=3&size=50");
        writeListStateToUrl("/records/contacts", { q: "", sort: null, dir: "asc", page: 1, size: 25 }, 25);
        expect(replaceState).toHaveBeenCalledWith(null, "", "/records/contacts");
    });

    it("omits dir=asc and only writes dir when a sort is active", () => {
        stubLocation("");
        writeListStateToUrl("/records/contacts", { sort: "name", dir: "asc", page: 1, size: 25 }, 25);
        const url = new URL(`http://x${replaceState.mock.calls[0][2]}`);
        expect(url.searchParams.get("sort")).toBe("name");
        expect(url.searchParams.has("dir")).toBe(false);
    });

    it("leaves q untouched when the state omits it", () => {
        stubLocation("?q=owned-elsewhere");
        writeListStateToUrl("/records/deals", { sort: "value", dir: "desc", page: 1, size: 25 }, 25);
        const url = new URL(`http://x${replaceState.mock.calls[0][2]}`);
        expect(url.searchParams.get("q")).toBe("owned-elsewhere");
        expect(url.searchParams.get("dir")).toBe("desc");
    });

    it("no-ops when the query string already matches", () => {
        stubLocation("?sort=name");
        writeListStateToUrl("/records/contacts", { sort: "name", dir: "asc", page: 1, size: 25 }, 25);
        expect(replaceState).not.toHaveBeenCalled();
    });

    it("writeListQueryToUrl only touches q", () => {
        stubLocation("?sort=name&peek=person%3A3");
        writeListQueryToUrl("/records/contacts", "acme");
        const url = new URL(`http://x${replaceState.mock.calls[0][2]}`);
        expect(url.searchParams.get("q")).toBe("acme");
        expect(url.searchParams.get("sort")).toBe("name");
        expect(url.searchParams.get("peek")).toBe("person:3");
    });

    it("writeSavedViewToUrl sets and clears only sv", () => {
        stubLocation("?q=acme");
        writeSavedViewToUrl("/records/contacts", "5:9");
        let url = new URL(`http://x${replaceState.mock.calls[0][2]}`);
        expect(url.searchParams.get("sv")).toBe("5:9");
        expect(url.searchParams.get("q")).toBe("acme");

        replaceState.mockClear();
        stubLocation("?q=acme&sv=5%3A9");
        writeSavedViewToUrl("/records/contacts", null);
        url = new URL(`http://x${replaceState.mock.calls[0][2]}`);
        expect(url.searchParams.has("sv")).toBe(false);
        expect(url.searchParams.get("q")).toBe("acme");
    });
});
