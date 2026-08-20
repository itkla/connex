import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
    MAX_URL_PAGE_SIZE,
    SERVER_RECORDS_URL_KEYS,
    parseDeepLinkId,
    parseListInt,
    parseListQuery,
    writeListQueryToUrl,
    writeListStateToUrl,
    writeOwnedParamsToUrl,
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

    function stubLocation(search: string, state: unknown = null, hash = ""): void {
        vi.stubGlobal("window", {
            location: { search, hash },
            history: { replaceState, state },
        });
    }

    beforeEach(() => replaceState.mockClear());
    afterEach(() => vi.unstubAllGlobals());

    it("carries the reader's fragment through every writer, so a deep link survives a list-state write", () => {
        const writers: ReadonlyArray<[string, () => void]> = [
            ["writeListStateToUrl", () => writeListStateToUrl("/settings/workspace/people", { q: "a", sort: null, dir: "asc", page: 1, size: 25 }, 25)],
            ["writeListQueryToUrl", () => writeListQueryToUrl("/settings/workspace/people", "a")],
            ["writeSavedViewToUrl", () => writeSavedViewToUrl("/settings/workspace/people", "1:2")],
            ["writeOwnedParamsToUrl", () => writeOwnedParamsToUrl("/settings/workspace/people", { task: "7" })],
        ];

        for (const [name, write] of writers) {
            replaceState.mockClear();
            stubLocation("?view=table", null, "#directory");
            write();
            expect(replaceState, `${name} wrote nothing to assert on`).toHaveBeenCalledTimes(1);
            expect(
                String(replaceState.mock.calls[0][2]),
                `${name} dropped the fragment the reader arrived with`,
            ).toContain("#directory");
        }
    });

    it("writes no fragment when the reader carries none", () => {
        stubLocation("?view=table");
        writeListQueryToUrl("/records/contacts", "acme");

        expect(String(replaceState.mock.calls[0][2])).toBe("/records/contacts?view=table&q=acme");
    });

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

    it("writeOwnedParamsToUrl sets, deletes, and preserves foreign keys", () => {
        stubLocation("?q=foo&status=open&task=3");
        writeOwnedParamsToUrl("/activity/tasks", { task: "7", kind: undefined });
        const url = new URL(`http://x${replaceState.mock.calls[0][2]}`);
        expect(url.searchParams.get("task")).toBe("7");
        expect(url.searchParams.get("q")).toBe("foo");
        expect(url.searchParams.get("status")).toBe("open");
        expect(url.searchParams.has("kind")).toBe(false);
    });

    it("writeOwnedParamsToUrl no-ops when the query string already matches", () => {
        stubLocation("?q=foo&task=7");
        writeOwnedParamsToUrl("/activity/tasks", { task: "7" });
        expect(replaceState).not.toHaveBeenCalled();
    });

    it("writeOwnedParamsToUrl drops the path query when it owns the last key", () => {
        stubLocation("?task=7");
        writeOwnedParamsToUrl("/activity/tasks", { task: undefined });
        expect(replaceState).toHaveBeenCalledWith(null, "", "/activity/tasks");
    });

    it("writeOwnedParamsToUrl preserves the record-return marker in history state", () => {
        stubLocation("?q=foo", { connexRecordReturn: "5f0c" });
        writeOwnedParamsToUrl("/activity/all", { activity: "9" });
        expect(replaceState.mock.calls[0][0]).toEqual({ connexRecordReturn: "5f0c" });
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

describe("parseDeepLinkId", () => {
    it("accepts plain positive integers only", () => {
        expect(parseDeepLinkId("42")).toBe(42);
        expect(parseDeepLinkId(null)).toBeNull();
        expect(parseDeepLinkId("0")).toBeNull();
        expect(parseDeepLinkId("-1")).toBeNull();
        expect(parseDeepLinkId("1.5")).toBeNull();
        expect(parseDeepLinkId("1e3")).toBeNull();
        expect(parseDeepLinkId(" 7 ")).toBeNull();
        expect(parseDeepLinkId("99999999999999999999")).toBeNull();
    });
});

describe("shared-link survival across a browser session", () => {
    function mountUrl(initial: string, fragment = ""): { search: () => string; hash: () => string } {
        const state = { search: initial, hash: fragment };
        vi.stubGlobal("window", {
            location: {
                get search() {
                    return state.search;
                },
                get hash() {
                    return state.hash;
                },
            },
            history: {
                state: null,
                replaceState: (_data: unknown, _title: string, url: string) => {
                    const parsed = new URL(`http://x${url}`);
                    state.search = parsed.search;
                    state.hash = parsed.hash;
                },
            },
        });
        return { search: () => state.search, hash: () => state.hash };
    }

    afterEach(() => vi.unstubAllGlobals());

    it("keeps every foreign key through mount, filter change, task open, and task close", () => {
        const url = mountUrl("?q=foo&status=open&sv=3");
        const foreignKeysIntact = () => {
            const params = new URLSearchParams(url.search());
            expect(params.get("q")).toBe("foo");
            expect(params.get("status")).toBe("open");
            expect(params.get("sv")).toBe("3");
        };

        writeOwnedParamsToUrl("/activity/tasks", { task: undefined });
        foreignKeysIntact();
        expect(new URLSearchParams(url.search()).has("task")).toBe(false);

        writeListQueryToUrl("/activity/tasks", "foo");
        foreignKeysIntact();

        writeOwnedParamsToUrl("/activity/tasks", { task: "7" });
        foreignKeysIntact();
        expect(new URLSearchParams(url.search()).get("task")).toBe("7");

        writeOwnedParamsToUrl("/activity/tasks", { task: undefined });
        foreignKeysIntact();
        expect(new URLSearchParams(url.search()).has("task")).toBe(false);
    });

    it("lets a files filter write leave pagination and foreign keys intact", () => {
        const url = mountUrl("?page=3&size=50&sv=2%3A9");

        writeOwnedParamsToUrl("/library/files", {
            q: "invoice",
            kind: "image",
            source: undefined,
            sort: "name",
            tags: "1,2",
            orphaned: "1",
            file: undefined,
        });

        const params = new URLSearchParams(url.search());
        expect(params.get("page")).toBe("3");
        expect(params.get("size")).toBe("50");
        expect(params.get("sv")).toBe("2:9");
        expect(params.get("q")).toBe("invoice");
        expect(params.get("kind")).toBe("image");
        expect(params.get("sort")).toBe("name");
        expect(params.get("tags")).toBe("1,2");
        expect(params.get("orphaned")).toBe("1");
        expect(params.has("source")).toBe(false);
        expect(params.has("file")).toBe(false);
    });

    it("lets the files writer sync page and size without disturbing its own sort value space", () => {
        const url = mountUrl("?sort=largest&kind=pdf&sv=2%3A9");

        writeOwnedParamsToUrl("/library/files", {
            q: undefined,
            kind: "pdf",
            source: undefined,
            sort: "largest",
            tags: undefined,
            orphaned: undefined,
            page: "3",
            size: "50",
            file: undefined,
        });

        const afterPage = new URLSearchParams(url.search());
        expect(afterPage.get("page")).toBe("3");
        expect(afterPage.get("size")).toBe("50");
        expect(afterPage.get("sort")).toBe("largest");
        expect(afterPage.get("kind")).toBe("pdf");
        expect(afterPage.get("sv")).toBe("2:9");
        expect(afterPage.has("dir")).toBe(false);

        writeOwnedParamsToUrl("/library/files", {
            q: undefined,
            kind: "pdf",
            source: undefined,
            sort: "largest",
            tags: undefined,
            orphaned: undefined,
            page: undefined,
            size: undefined,
            file: undefined,
        });

        const afterDefaults = new URLSearchParams(url.search());
        expect(afterDefaults.has("page")).toBe(false);
        expect(afterDefaults.has("size")).toBe(false);
        expect(afterDefaults.get("sort")).toBe("largest");
        expect(afterDefaults.get("kind")).toBe("pdf");
        expect(afterDefaults.get("sv")).toBe("2:9");
    });

    it("keeps files pagination when a deep-linked file opens and closes", () => {
        const url = mountUrl("?page=2&size=50&sort=name");

        writeOwnedParamsToUrl("/library/files", { file: "42" });
        expect(new URLSearchParams(url.search()).get("page")).toBe("2");
        expect(new URLSearchParams(url.search()).get("size")).toBe("50");
        expect(new URLSearchParams(url.search()).get("sort")).toBe("name");
        expect(new URLSearchParams(url.search()).get("file")).toBe("42");

        writeOwnedParamsToUrl("/library/files", { file: undefined });
        expect(new URLSearchParams(url.search()).get("page")).toBe("2");
        expect(new URLSearchParams(url.search()).get("size")).toBe("50");
        expect(new URLSearchParams(url.search()).get("sort")).toBe("name");
        expect(new URLSearchParams(url.search()).has("file")).toBe(false);
    });

    it("clears only the analytics writer's own keys when they return to defaults", () => {
        const url = mountUrl("?range=30d&granularity=week&currency=JPY&owner=me&peek=deal%3A4");

        writeOwnedParamsToUrl("/overview/analytics", {
            range: undefined,
            granularity: undefined,
            currency: undefined,
            owner: undefined,
        });

        expect(url.search()).toBe("?peek=deal%3A4");
    });
});
