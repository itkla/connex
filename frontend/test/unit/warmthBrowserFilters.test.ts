import { readFileSync } from "node:fs";
import path from "node:path";
import { afterEach, describe, expect, it, vi } from "vitest";

import {
    WARMTH_FILTER_KEY,
    WARMTH_HORIZON_FILTER_KEY,
    WARMTH_HORIZON_MAX_DAYS,
    WARMTH_HORIZON_MIN_DAYS,
    WARMTH_NONE_FACET_KEY,
    WARMTH_SORT_KEY,
    hasWarmthFilter,
    parseWarmthHorizon,
    selectedWarmthBands,
    warmthFacetOptions,
    warmthHorizonContactsHref,
    warmthRequestParams,
    withValidWarmthHorizon,
    withoutWarmth,
    withoutWarmthHorizon,
} from "@/app/components/records/warmthFilters";
import { FILTER_EMPTY, countActiveFilters, type FilterState } from "@/app/components/records/types";
import { PEEK_PARAM } from "@/app/hooks/useRecordPeek";
import { SAVED_VIEW_URL_KEY, SERVER_RECORDS_URL_KEYS } from "@/app/hooks/listStateUrl";
import {
    exportCompaniesCsv,
    exportContactsCsv,
    getCompaniesPage,
    getCompanyFacets,
    getCompanyIds,
    getContactIds,
    getContactsPage,
    getPersonFacets,
    getRadarForSubject,
} from "@/app/lib/api";
import type { CompaniesPageParams, ContactsPageParams, FacetCount } from "@/app/lib/types";

const SIGNALS_PANEL = "app/components/records/RecordSignalsPanel.tsx";
const CONTACTS_BROWSER = "app/components/records/contacts/ContactsBrowser.tsx";
const COMPANIES_BROWSER = "app/components/records/companies/CompaniesBrowser.tsx";

function source(relativePath: string): string {
    return readFileSync(path.resolve(process.cwd(), relativePath), "utf8");
}

/** Records every request URL api.ts issues, with the browser globals its request builders read. */
function stubBrowser(): { urls: string[] } {
    const urls: string[] = [];
    const anchor = { href: "", download: "", click: () => undefined, remove: () => undefined };
    vi.stubGlobal("document", {
        cookie: "connex_workspace=5; NEXT_LOCALE=en",
        createElement: () => anchor,
        body: { appendChild: () => undefined },
    });
    vi.stubGlobal("URL", class TestUrl extends URL {
        static createObjectURL(): string {
            return "blob:test";
        }
        static revokeObjectURL(): void {
            return undefined;
        }
    });
    vi.stubGlobal("fetch", (input: string | URL | Request) => {
        urls.push(input instanceof Request ? input.url : String(input));
        return Promise.resolve(
            new Response("[]", { status: 200, headers: { "content-type": "application/json" } }),
        );
    });
    return { urls };
}

/** The warmth query params one request carried, so two surfaces can be compared key for key. */
function warmthParams(url: string): Record<string, string[]> {
    const search = new URL(url, "https://connex.test").searchParams;
    const out: Record<string, string[]> = {};
    for (const key of ["warmthBands", "noWarmth", "goesColdWithinDays"]) {
        const values = search.getAll(key);
        if (values.length) out[key] = values;
    }
    return out;
}

describe("the warmth dimension a records browser reads from its filter state", () => {
    it("keeps the canonical band order however the URL listed the bands", () => {
        const state: FilterState = { [WARMTH_FILTER_KEY]: ["cold", "hot", "cool"] };

        expect(selectedWarmthBands(state)).toEqual(["hot", "cool", "cold"]);
    });

    it("ignores a band the model does not define rather than asking the server about it", () => {
        const state: FilterState = { [WARMTH_FILTER_KEY]: ["hot", "lukewarm"] };

        expect(warmthRequestParams(state)).toEqual({ warmthBands: ["hot"] });
    });

    it("asks for the no-history bucket as noWarmth, not as a band", () => {
        const state: FilterState = { [WARMTH_FILTER_KEY]: ["cold", FILTER_EMPTY] };

        expect(warmthRequestParams(state)).toEqual({ warmthBands: ["cold"], noWarmth: true });
    });

    it("sends nothing warmth-related when nothing warmth-related is selected", () => {
        expect(warmthRequestParams({ company: ["Acme"] })).toEqual({});
        expect(hasWarmthFilter({ company: ["Acme"] })).toBe(false);
    });

    it("treats a band, the no-history bucket, and a horizon alike as a warmth restriction", () => {
        expect(hasWarmthFilter({ [WARMTH_FILTER_KEY]: ["hot"] })).toBe(true);
        expect(hasWarmthFilter({ [WARMTH_FILTER_KEY]: [FILTER_EMPTY] })).toBe(true);
        expect(hasWarmthFilter({ [WARMTH_HORIZON_FILTER_KEY]: ["30"] })).toBe(true);
    });
});

describe("the decay horizon a shared link can carry", () => {
    it("accepts a plain integer inside the range the backend accepts", () => {
        expect(parseWarmthHorizon(["30"])).toBe(30);
        expect(parseWarmthHorizon([String(WARMTH_HORIZON_MIN_DAYS)])).toBe(WARMTH_HORIZON_MIN_DAYS);
        expect(parseWarmthHorizon([String(WARMTH_HORIZON_MAX_DAYS)])).toBe(WARMTH_HORIZON_MAX_DAYS);
    });

    it("refuses a crafted value rather than spending a request on a guaranteed 400", () => {
        expect(parseWarmthHorizon(undefined)).toBeUndefined();
        expect(parseWarmthHorizon([])).toBeUndefined();
        expect(parseWarmthHorizon(["0"])).toBeUndefined();
        expect(parseWarmthHorizon(["-1"])).toBeUndefined();
        expect(parseWarmthHorizon(["1e3"])).toBeUndefined();
        expect(parseWarmthHorizon(["30.5"])).toBeUndefined();
        expect(parseWarmthHorizon(["abc"])).toBeUndefined();
        expect(parseWarmthHorizon([String(WARMTH_HORIZON_MAX_DAYS + 1)])).toBeUndefined();
    });

    it("clears only itself, leaving every other filter the user set in place", () => {
        const state: FilterState = {
            [WARMTH_HORIZON_FILTER_KEY]: ["60"],
            [WARMTH_FILTER_KEY]: ["cool"],
            company: ["Acme"],
        };

        expect(withoutWarmthHorizon(state)).toEqual({
            [WARMTH_FILTER_KEY]: ["cool"],
            company: ["Acme"],
        });
    });

    it("links a decay figure at the contacts browser with the horizon it counted", () => {
        expect(warmthHorizonContactsHref(60)).toBe("/records/contacts?goesColdWithinDays=60");
    });

    it("is dropped outright when invalid, so it cannot count as a filter it never applied", () => {
        const invalid: FilterState = { [WARMTH_HORIZON_FILTER_KEY]: ["0"], company: ["Acme"] };
        const sanitized = withValidWarmthHorizon(invalid);

        expect(sanitized).toEqual({ company: ["Acme"] });
        expect(countActiveFilters(sanitized)).toBe(1);
        expect(hasWarmthFilter(sanitized)).toBe(false);
    });

    it("survives untouched when valid, so a browser can memoize on the result", () => {
        const valid: FilterState = { [WARMTH_HORIZON_FILTER_KEY]: ["30"] };

        expect(withValidWarmthHorizon(valid)).toBe(valid);
        expect(withValidWarmthHorizon({ company: ["Acme"] })).toEqual({ company: ["Acme"] });
    });
});

describe("a surface that cannot apply warmth at all", () => {
    it("strips every warmth key so nothing counts, chips, or sends it", () => {
        const state: FilterState = {
            [WARMTH_FILTER_KEY]: ["hot"],
            [WARMTH_HORIZON_FILTER_KEY]: ["30"],
            industry: ["Software"],
        };
        const stripped = withoutWarmth(state);

        expect(stripped).toEqual({ industry: ["Software"] });
        expect(warmthRequestParams(stripped)).toEqual({});
        expect(hasWarmthFilter(stripped)).toBe(false);
        expect(countActiveFilters(stripped)).toBe(1);
    });
});

describe("the warmth filter keys a records browser round-trips through the URL", () => {
    it("are none of the reserved keys another list-state writer owns", () => {
        const reserved = new Set<string>([
            "view",
            PEEK_PARAM,
            SAVED_VIEW_URL_KEY,
            ...SERVER_RECORDS_URL_KEYS,
        ]);

        expect(reserved.has(WARMTH_FILTER_KEY)).toBe(false);
        expect(reserved.has(WARMTH_HORIZON_FILTER_KEY)).toBe(false);
    });

    it("spell the horizon exactly as the backend param it becomes", () => {
        expect(WARMTH_HORIZON_FILTER_KEY).toBe("goesColdWithinDays");
    });
});

describe("the warmth facet options a browser offers", () => {
    const counts: FacetCount[] = [
        { key: "hot", count: 3 },
        { key: "cold", count: 7 },
        { key: WARMTH_NONE_FACET_KEY, count: 2 },
    ];
    const label = (band: string) => `band:${band}`;

    it("offers the bands the workspace holds, in the canonical order", () => {
        const options = warmthFacetOptions(counts, [], label, "No history");

        expect(options).toEqual([
            { key: "hot", label: "band:hot" },
            { key: "cold", label: "band:cold" },
            { key: FILTER_EMPTY, label: "No history" },
        ]);
    });

    it("keeps a selected band whose bucket has since emptied, so a shared link never drops a filter", () => {
        const options = warmthFacetOptions([{ key: "hot", count: 1 }], ["cool"], label, "No history");

        expect(options.map((option) => option.key)).toEqual(["hot", "cool"]);
    });

    it("offers nothing when the counts are unknown and nothing is selected", () => {
        expect(warmthFacetOptions(undefined, [], label, "No history")).toEqual([]);
        expect(warmthFacetOptions(undefined, undefined, label, "No history")).toEqual([]);
    });

    it("still offers a selection when the counts are unknown, so a live filter stays removable", () => {
        const options = warmthFacetOptions(undefined, ["cool", FILTER_EMPTY], label, "No history");

        expect(options).toEqual([
            { key: "cool", label: "band:cool" },
            { key: FILTER_EMPTY, label: "No history" },
        ]);
    });

    it("omits the no-history bucket when the workspace has none and none is selected", () => {
        const options = warmthFacetOptions([{ key: "hot", count: 1 }], [], label, "No history");

        expect(options.map((option) => option.key)).toEqual(["hot"]);
    });
});

describe("the warmth params every records surface sends", () => {
    afterEach(() => {
        vi.unstubAllGlobals();
    });

    it("reach the contacts page, its id read, and its export identically", async () => {
        const { urls } = stubBrowser();
        const filter: ContactsPageParams = {
            companies: ["Acme"],
            warmthBands: ["hot", "cool"],
            noWarmth: true,
            goesColdWithinDays: 30,
        };

        await getContactsPage(filter);
        await getContactIds(filter);
        await exportContactsCsv(filter);

        expect(urls).toHaveLength(3);
        const [page, ids, csv] = urls;
        expect(page).toContain("/api/persons/page");
        expect(ids).toContain("/api/persons/ids");
        expect(csv).toContain("/api/exports/persons");
        const expected = {
            warmthBands: ["hot", "cool"],
            noWarmth: ["true"],
            goesColdWithinDays: ["30"],
        };
        expect(warmthParams(page)).toEqual(expected);
        expect(warmthParams(ids)).toEqual(expected);
        expect(warmthParams(csv)).toEqual(expected);
    });

    it("reach the companies page, its id read, and its export identically", async () => {
        const { urls } = stubBrowser();
        const filter: CompaniesPageParams = {
            industry: ["Software"],
            warmthBands: ["cold"],
            goesColdWithinDays: 90,
        };

        await getCompaniesPage(filter);
        await getCompanyIds(filter);
        await exportCompaniesCsv(filter);

        const [page, ids, csv] = urls;
        const expected = { warmthBands: ["cold"], goesColdWithinDays: ["90"] };
        expect(warmthParams(page)).toEqual(expected);
        expect(warmthParams(ids)).toEqual(expected);
        expect(warmthParams(csv)).toEqual(expected);
    });

    it("omit noWarmth entirely when it is off, on the page as well as the ids and the export", async () => {
        const { urls } = stubBrowser();
        const filter: ContactsPageParams = { warmthBands: ["hot"], noWarmth: false };

        await getContactsPage(filter);
        await getContactIds(filter);
        await exportContactsCsv(filter);
        await getCompaniesPage({ warmthBands: ["hot"], noWarmth: false });

        for (const url of urls) {
            expect(url).not.toContain("noWarmth");
            expect(warmthParams(url)).toEqual({ warmthBands: ["hot"] });
        }
    });

    it("stay off a request that filters by nothing warmth-related", async () => {
        const { urls } = stubBrowser();

        await getContactIds({ companies: ["Acme"] });
        await exportContactsCsv({ companies: ["Acme"] });

        expect(warmthParams(urls[0])).toEqual({});
        expect(warmthParams(urls[1])).toEqual({});
    });
});

describe("the warmth facet counts a browser asks for", () => {
    afterEach(() => {
        vi.unstubAllGlobals();
    });

    it("are opt-in, because they cost a full-workspace aggregate", async () => {
        const { urls } = stubBrowser();

        await getPersonFacets();
        await getCompanyFacets();

        expect(urls[0]).not.toContain("warmth");
        expect(urls[1]).not.toContain("warmth");
    });

    it("are requested only where the facet is shown", async () => {
        const { urls } = stubBrowser();

        await getPersonFacets({ warmth: true });
        await getCompanyFacets({ warmth: true });

        expect(urls[0]).toContain("/api/persons/facets?warmth=true");
        expect(urls[1]).toContain("/api/companies/facets?warmth=true");
    });
});

describe("the browsers' warmth wiring", () => {
    const browsers = [
        { name: "contacts", file: CONTACTS_BROWSER },
        { name: "companies", file: COMPANIES_BROWSER },
    ];

    it.each(browsers)("derives $name warmth params from the filter state once", ({ file }) => {
        expect(source(file)).toContain("warmthRequestParams(filterState)");
    });

    it.each(browsers)("feeds that one $name filter object to the page, ids, and export alike", ({ file }) => {
        const browser = source(file);
        const spreads = browser.match(/\.\.\.filterParams/g) ?? [];

        expect(browser).toContain("useServerRecords");
        expect(spreads.length).toBeGreaterThanOrEqual(2);
    });

    it.each(browsers)("asks the $name facets endpoint for the warmth counts it shows", ({ file }) => {
        expect(source(file)).toMatch(/get(Person|Company)Facets\(\{ warmth: (true|!segmentScoped) \}\)/);
    });

    it.each(browsers)("lets a reader order the $name list by warmth", ({ file }) => {
        const browser = source(file);

        expect(browser).toContain("sortable: !showArchived");
        expect(browser).not.toContain("sortKey === 'warmth' ? null");
    });

    it.each(browsers)("hands the $name workflow engine explicit ids when warmth narrowed the list", ({ file }) => {
        const browser = source(file);

        expect(browser).toContain("const warmthNarrowed = hasWarmthFilter(filterState);");
        expect(browser).toContain("explicit_selection");
        expect(browser).not.toContain("page_selection");
    });

    it.each(browsers)("keeps the $name saved-view scope off a warmth-narrowed selection too", ({ file }) => {
        expect(source(file)).toContain(
            "allMatchingActive && activeSavedViewId !== null && !warmthNarrowed",
        );
    });

    it.each(browsers)("drops an unhonourable $name horizon before anything can count it", ({ file }) => {
        const browser = source(file);

        expect(browser).toContain("withValidWarmthHorizon(urlFilterState)");
        expect(browser).not.toMatch(/filterState,\s*\n\s*setFilterState,/);
    });

    it.each(browsers)("degrades the $name facet bar to its base facets when warmth counts fail", ({ file }) => {
        expect(source(file)).toMatch(/\.catch\(\(\) => get(Person|Company)Facets\(\)\)/);
    });

    it.each(browsers)("clears a warmth sort the $name archived scope cannot honour", ({ file }) => {
        expect(source(file)).toContain("sortKey === WARMTH_SORT_KEY");
    });
});

describe("the companies segment path, which the backend applies no warmth to", () => {
    const browser = source(COMPANIES_BROWSER);

    it("strips warmth from the filter state the whole surface reads", () => {
        expect(browser).toContain("const segmentScoped = !showArchived && hasSegments;");
        expect(browser).toContain("segmentScoped ? withoutWarmth(validatedFilterState) : validatedFilterState");
    });

    it("offers no warmth facet to select, because selecting one would filter nothing", () => {
        expect(browser).toContain("const warmthOptions = segmentScoped ? [] : warmthFacetOptions(");
    });

    it("offers no warmth sort either, because the segment query orders by name instead", () => {
        expect(browser).toContain("sortable: !showArchived && !segmentScoped");
        expect(browser).toContain("hasSegmentConditions(evaluableSegmentDefinition(next))");
    });

    it("does not pay for warmth facet counts it will not show", () => {
        expect(browser).toContain("getCompanyFacets({ warmth: !segmentScoped })");
    });

    it("leaves the segment page, ids, and export reading one warmth-free filter object", () => {
        expect(browser).toContain("warmthRequestParams(filterState)");
        expect(browser).toContain("getCompaniesSegmentPage({ ...params, definition: evaluable })");
        expect(browser).toContain("getCompanySegmentIds({ ...params, definition: evaluable })");
    });
});

describe("a companies request built while a segment is active", () => {
    afterEach(() => {
        vi.unstubAllGlobals();
    });

    it("carries no warmth keys on the export, so the CSV is not secretly narrower than the list", async () => {
        const { urls } = stubBrowser();
        const segmentFilter: CompaniesPageParams = { industry: ["Software"] };

        await getCompanyIds(segmentFilter);
        await exportCompaniesCsv({ ...segmentFilter, ids: [7, 9] });

        for (const url of urls) {
            expect(warmthParams(url)).toEqual({});
        }
    });
});

describe("the Radar feed a record page reads", () => {
    afterEach(() => {
        vi.unstubAllGlobals();
    });

    it("is narrowed to the record by the server, not by the client", async () => {
        const { urls } = stubBrowser();

        await getRadarForSubject("person", 42);

        const search = new URL(urls[0], "https://connex.test").searchParams;
        expect(search.get("subjectType")).toBe("person");
        expect(search.get("subjectId")).toBe("42");
    });

    it("leaves the panel with no client-side subject filter to fall back on", () => {
        const panel = source(SIGNALS_PANEL);

        expect(panel).toContain("getRadarForSubject(subject.type, subject.id)");
        expect(panel).not.toContain("signal.subject.type === subject.type");
    });
});
