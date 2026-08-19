import { readFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";

import { buildSearchGroups } from "@/app/lib/search/resultGroups";
import type { SearchResults } from "@/app/lib/types";

const EMPTY: SearchResults = {
    companies: [],
    people: [],
    deals: [],
    pipelines: [],
    tags: [],
    activities: [],
    notes: [],
    tasks: [],
    users: [],
    attachments: [],
    products: [],
    campaigns: [],
    reports: [],
    documentTemplates: [],
    documents: [],
    workflows: [],
};

const t = (key: string) => key;

function source(relativePath: string): string {
    return readFileSync(path.resolve(process.cwd(), relativePath), "utf8");
}

function groups(results: Partial<SearchResults>) {
    return buildSearchGroups({ ...EMPTY, ...results }, t);
}

function onlyGroup(results: Partial<SearchResults>) {
    const built = groups(results);
    expect(built).toHaveLength(1);
    return built[0];
}

describe("global search reaches every first-class sidebar object", () => {
    it("lands a product on the catalog that manages it, because a product has no page of its own", () => {
        const group = onlyGroup({
            products: [{ id: 3, name: "Onboarding", sku: "ONB-1", active: true }],
        });

        expect(group.key).toBe("products");
        expect(group.heading).toBe("groupProducts");
        expect(group.rows).toEqual([
            expect.objectContaining({
                key: "product-3",
                href: "/records/products",
                label: "Onboarding",
                subtitle: "ONB-1",
            }),
        ]);
    });

    it("lands a campaign on its own detail page", () => {
        const group = onlyGroup({
            campaigns: [{ id: 8, name: "Spring outreach", type: "email", status: "active" }],
        });

        expect(group.key).toBe("campaigns");
        expect(group.heading).toBe("groupCampaigns");
        expect(group.rows[0]).toMatchObject({
            key: "campaign-8",
            href: "/marketing/campaigns/8",
            label: "Spring outreach",
        });
    });

    it("lands a report on the report it names", () => {
        const group = onlyGroup({
            reports: [{ id: 12, name: "Quarterly review", description: "Won deals by owner", cadence: "monthly" }],
        });

        expect(group.key).toBe("reports");
        expect(group.heading).toBe("groupReports");
        expect(group.rows[0]).toMatchObject({
            key: "report-12",
            href: "/overview/reports/12",
            label: "Quarterly review",
            subtitle: "Won deals by owner",
        });
    });

    it("lands a generated document on its parent deal's documents section, which is the only surface that holds it", () => {
        const group = onlyGroup({
            documents: [{
                id: 41,
                dealId: 9,
                dealName: "Acme renewal",
                type: "quote",
                status: "final",
                version: 2,
                title: "Quote for Acme",
            }],
        });

        expect(group.key).toBe("documents");
        expect(group.heading).toBe("groupDocuments");
        expect(group.rows[0]).toMatchObject({
            key: "document-41",
            href: "/records/deals/9#deal-detail-files",
            label: "Quote for Acme",
            subtitle: "Acme renewal",
        });
    });

    it("names a generated document by its deal when the document has no resolved title", () => {
        const group = onlyGroup({
            documents: [{ id: 42, dealId: 9, dealName: "Acme renewal", type: "proposal", status: "draft", version: 1 }],
        });

        expect(group.rows[0]).toMatchObject({ label: "Acme renewal", subtitle: "Acme renewal" });
    });

    it("lands a document template on the builder that edits it", () => {
        const group = onlyGroup({
            documentTemplates: [{ id: 5, name: "Standard quote", type: "quote", locale: "en", active: true }],
        });

        expect(group.key).toBe("documentTemplates");
        expect(group.heading).toBe("groupDocumentTemplates");
        expect(group.rows[0]).toMatchObject({
            key: "document-template-5",
            href: "/library/documents/5",
            label: "Standard quote",
            subtitle: "EN",
        });
    });

    it("lands a workflow on its editor", () => {
        const group = onlyGroup({
            workflows: [{ id: 17, name: "Stale deal nudge", description: "Nudges an owner", enabled: true, recordType: "deal" }],
        });

        expect(group.key).toBe("workflows");
        expect(group.heading).toBe("groupWorkflows");
        expect(group.rows[0]).toMatchObject({
            key: "workflow-17",
            href: "/workflows/17",
            label: "Stale deal nudge",
            subtitle: "Nudges an owner",
        });
    });

    it("keeps one flat row index across the record groups and the builder-object groups, because the inline dropdown roves by it", () => {
        const built = groups({
            products: [{ id: 1, name: "Onboarding", active: true }],
            campaigns: [{ id: 2, name: "Spring outreach", type: "email", status: "active" }],
            workflows: [{ id: 3, name: "Stale deal nudge", enabled: true }],
        });

        expect(built.flatMap((group) => group.rows).map((row) => row.index)).toEqual([0, 1, 2]);
    });

    it("serves a denied group empty rather than absent, so no group is rendered for it", () => {
        expect(groups({})).toEqual([]);
    });

    it("keeps every empty-results literal carrying the same groups as the response type", () => {
        const keys = [...source("app/lib/types.ts")
            .split("export type SearchResults = {")[1]
            .split("};")[0]
            .matchAll(/^\s{4}(\w+):/gm)]
            .map((match) => match[1]);

        expect(keys).toContain("documentTemplates");
        for (const literal of [
            source("app/lib/api.ts").split("const EMPTY_SEARCH_RESULTS: Types.SearchResults = {")[1].split("};")[0],
            source("app/components/GlobalSearch.tsx").split("const EMPTY_RESULTS: SearchResults = {")[1].split("};")[0],
        ]) {
            for (const key of keys) {
                expect(literal, `${key} is missing from an empty-results literal`).toContain(`${key}: []`);
            }
        }
    });
});
