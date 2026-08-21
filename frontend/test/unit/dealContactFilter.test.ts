import { readFileSync } from "node:fs";
import path from "node:path";
import { afterEach, describe, expect, it, vi } from "vitest";

import {
    exportDealSegmentCsv,
    exportDealsCsv,
    getDealIds,
    getDealMetrics,
    getDealSegmentIds,
    getDealSegmentMetrics,
    getDealsPage,
    getDealsSegmentPage,
} from "@/app/lib/api";
import type { DealFilterParams, DealSegmentPageParams, DealsPageParams } from "@/app/lib/types";

type CapturedRequest = {
    url: string;
    body: string | null;
};

function source(relativePath: string): string {
    return readFileSync(path.resolve(process.cwd(), relativePath), "utf8");
}

/** Records API request identities and payloads with the browser globals used by CSV downloads. */
function stubBrowser(): CapturedRequest[] {
    const requests: CapturedRequest[] = [];
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
    vi.stubGlobal("fetch", (input: string | URL | Request, init?: RequestInit) => {
        requests.push({
            url: input instanceof Request ? input.url : String(input),
            body: typeof init?.body === "string" ? init.body : null,
        });
        return Promise.resolve(
            new Response("{}", { status: 200, headers: { "content-type": "application/json" } }),
        );
    });
    return requests;
}

function personIds(url: string): string[] {
    return new URL(url, "https://connex.test").searchParams.getAll("personId");
}

describe("the stakeholder-contact filter every Deals operation applies", () => {
    afterEach(() => {
        vi.unstubAllGlobals();
    });

    it("reaches the page, metrics, id selection, and CSV export identically", async () => {
        const requests = stubBrowser();
        const filter: DealFilterParams = { personId: [7, 9], status: ["open"] };
        const pageFilter: DealsPageParams = { ...filter, page: 2, size: 25 };

        await getDealsPage(pageFilter);
        await getDealMetrics(filter);
        await getDealIds(pageFilter);
        await exportDealsCsv(filter);

        expect(requests.map(({ url }) => new URL(url).pathname)).toEqual([
            "/api/deals/page",
            "/api/deals/metrics",
            "/api/deals/ids",
            "/api/exports/deals",
        ]);
        for (const request of requests) {
            expect(personIds(request.url)).toEqual(["7", "9"]);
        }
    });

    it("survives every segment-backed page, metric, id, and export payload", async () => {
        const requests = stubBrowser();
        const filter: DealSegmentPageParams = {
            personId: [7, 9],
            page: 1,
            size: 25,
            definition: { match: "all", conditions: [] },
        };

        await getDealsSegmentPage(filter);
        await getDealSegmentMetrics(filter);
        await getDealSegmentIds(filter);
        await exportDealSegmentCsv(filter);

        expect(requests.map(({ url }) => new URL(url).pathname)).toEqual([
            "/api/deals/segment/page",
            "/api/deals/segment/metrics",
            "/api/deals/segment/ids",
            "/api/exports/deals/segment",
        ]);
        for (const request of requests) {
            expect(request.body).not.toBeNull();
            const body: unknown = JSON.parse(request.body ?? "null");
            expect(body).toMatchObject({ personId: [7, 9] });
        }
    });
});

describe("the Deals browser keeps contact identity through downstream actions", () => {
    const browser = source("app/components/records/deals/DealsBrowser.tsx");

    it("resolves the contact URL facet to the backend personId contract", () => {
        expect(browser).toContain(
            "personId: resolveEntityFacetIds(activeFilterState.contact, dealFacets.people)",
        );
    });

    it("carries the same person ids into filter-match manual workflow scope", () => {
        expect(browser).toContain("personIds: currentDealFilters.personId");
    });

    it("does not offer Kanban when a stakeholder filter cannot be represented by its pipeline board", () => {
        expect(browser).toContain("if (serverFilters.personId?.length && displayMode === 'kanban')");
        expect(browser).toContain("...(serverFilters.personId?.length");
    });
});
