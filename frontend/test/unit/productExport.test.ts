import { readFileSync } from "node:fs";
import path from "node:path";
import { afterEach, describe, expect, it, vi } from "vitest";

import {
    PRODUCT_AVAILABILITY_FILTER_KEY,
    PRODUCT_BILLING_FILTER_KEY,
    PRODUCT_BOUNDED_FACET_KEY,
    PRODUCT_EFFECTIVE_DATES_FILTER_KEY,
    productExportParams,
} from "@/app/components/records/products/productExport";
import { FILTER_EMPTY } from "@/app/components/records/types";
import { exportProductsCsv } from "@/app/lib/api";

const PRODUCTS_BROWSER = "app/components/records/products/ProductsBrowser.tsx";

function source(relativePath: string): string {
    return readFileSync(path.resolve(process.cwd(), relativePath), "utf8");
}

/** Records every request URL api.ts issues, with the browser globals a CSV download reads. */
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
        return Promise.resolve(new Response("id\r\n", { status: 200, headers: { "content-type": "text/csv" } }));
    });
    return { urls };
}

describe("the params a products export is narrowed by", () => {
    it("are empty when the view is unfiltered, so the whole catalog is exported", () => {
        expect(productExportParams("   ", {})).toEqual({});
    });

    it("carry the trimmed search and every facet the view applies", () => {
        expect(productExportParams("  plan ", {
            [PRODUCT_BILLING_FILTER_KEY]: ["recurring"],
            [PRODUCT_AVAILABILITY_FILTER_KEY]: ["true", "false"],
            [PRODUCT_EFFECTIVE_DATES_FILTER_KEY]: [PRODUCT_BOUNDED_FACET_KEY, FILTER_EMPTY],
        })).toEqual({
            q: "plan",
            billingFrequency: ["recurring"],
            active: ["true", "false"],
            effectiveDates: ["bounded", "none"],
        });
    });

    it("name the no-date-limit bucket on the wire as none", () => {
        expect(productExportParams("", { [PRODUCT_EFFECTIVE_DATES_FILTER_KEY]: [FILTER_EMPTY] }))
            .toEqual({ effectiveDates: ["none"] });
    });

    it("ignore filter keys that are not product facets", () => {
        expect(productExportParams("", { owner: ["me"] })).toEqual({});
    });

    it("pass a value the server cannot honour through, so the export fails instead of widening", () => {
        expect(productExportParams("", { [PRODUCT_BILLING_FILTER_KEY]: ["weekly"] }))
            .toEqual({ billingFrequency: ["weekly"] });
    });
});

describe("the products export request", () => {
    afterEach(() => {
        vi.unstubAllGlobals();
    });

    it("sends each selected facet value as its own repeated query param", async () => {
        const { urls } = stubBrowser();

        await exportProductsCsv(productExportParams("plan", {
            [PRODUCT_BILLING_FILTER_KEY]: ["one_time", "recurring"],
            [PRODUCT_AVAILABILITY_FILTER_KEY]: ["false"],
            [PRODUCT_EFFECTIVE_DATES_FILTER_KEY]: [FILTER_EMPTY],
        }));

        const url = new URL(urls[0], "https://connex.test");
        expect(url.pathname).toBe("/api/exports/products");
        expect(url.searchParams.get("q")).toBe("plan");
        expect(url.searchParams.getAll("billingFrequency")).toEqual(["one_time", "recurring"]);
        expect(url.searchParams.getAll("active")).toEqual(["false"]);
        expect(url.searchParams.getAll("effectiveDates")).toEqual(["none"]);
    });
});

describe("the products browser's export action", () => {
    const browser = source(PRODUCTS_BROWSER);

    it("exports the live search and facet state rather than the search alone", () => {
        expect(browser).toContain("productExportParams(query, filterState)");
        expect(browser).not.toContain("{ q: trimmedQuery || undefined }");
    });

    it("keys its facet columns by the same constants the export reads", () => {
        expect(browser).toContain("key: PRODUCT_BILLING_FILTER_KEY,");
        expect(browser).toContain("key: PRODUCT_AVAILABILITY_FILTER_KEY,");
        expect(browser).toContain("key: PRODUCT_EFFECTIVE_DATES_FILTER_KEY,");
        expect(browser).toContain("PRODUCT_BOUNDED_FACET_KEY : null");
    });
});
