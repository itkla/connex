import { readdirSync, readFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";

import { DEAL_DOCUMENTS_ANCHOR, dealDocumentsHref } from "@/app/components/records/deals/dealLinks";
import { resolveShippedRoute } from "@/app/lib/routeManifest";

const LIBRARY = "app/components/library/documents/DocumentsLibrary.tsx";
const INDEX = "app/components/library/documents/GeneratedDocumentsBrowser.tsx";
const TEMPLATES = "app/components/library/documents/DocumentTemplatesBrowser.tsx";
const DEAL_LINKS = "app/components/records/deals/dealLinks.ts";
const DEAL_DOCUMENTS = "app/components/records/deals/DealDocuments.tsx";
const MY_WORK_QUEUE = "app/components/me/MyWorkQueue.tsx";

function source(relativePath: string): string {
    return readFileSync(path.resolve(process.cwd(), relativePath), "utf8");
}

function messages(locale: string): Record<string, Record<string, string>> {
    return JSON.parse(
        readFileSync(path.resolve(process.cwd(), "messages", locale, "document-templates.json"), "utf8"),
    );
}

describe("the library holds generated documents beside the templates that produce them", () => {
    it("sends a document back to its parent deal, which is the only surface that holds it", () => {
        expect(dealDocumentsHref(42)).toBe("/records/deals/42#deal-documents");
        expect(resolveShippedRoute(dealDocumentsHref(42))).toBe("/records/deals/[id]");
    });

    it("addresses the panel that already exists rather than laying a second anchor over it", () => {
        expect(DEAL_DOCUMENTS_ANCHOR).toBe("deal-documents");
        expect(dealDocumentsHref(42).endsWith(`#${DEAL_DOCUMENTS_ANCHOR}`)).toBe(true);
        expect(source(DEAL_DOCUMENTS)).toContain("<section id={DEAL_DOCUMENTS_ANCHOR}");
        expect(source(MY_WORK_QUEUE)).toContain("dealDocumentsHref(item.context.id)");
    });

    it("holds exactly one spelling of the anchor, so no producer can drift off it", () => {
        const strays = readdirSync(path.resolve(process.cwd(), "app"), { recursive: true, encoding: "utf8" })
            .filter((entry) => entry.endsWith(".ts") || entry.endsWith(".tsx"))
            .map((entry) => path.join("app", entry))
            .filter((file) => file !== DEAL_LINKS && source(file).includes("#deal-documents"));

        expect(strays, "every producer must go through dealDocumentsHref").toEqual([]);
    });

    it("scrolls itself to the panel, because a client-side navigation resolves the fragment against the loading fallback and discards it", () => {
        const panel = source(DEAL_DOCUMENTS);

        expect(panel).toContain("hash !== `#${DEAL_DOCUMENTS_ANCHOR}` || scrolledForHash.current === hash");
        expect(panel).toContain("section.scrollIntoView(");
        expect(panel, "the guard resets so a later navigation to the same anchor scrolls again")
            .toContain("if (hash !== `#${DEAL_DOCUMENTS_ANCHOR}`) scrolledForHash.current = null;");
        expect(panel, "reduced motion is honored").toContain("reduceMotion ? 'auto' : 'smooth'");
    });

    it("names the two lists apart instead of letting one stand for both", () => {
        const library = source(LIBRARY);

        expect(library).toContain("SegmentedControl");
        expect(library).toContain("t('viewDocuments')");
        expect(library).toContain("t('viewTemplates')");
        for (const locale of ["en", "ja"]) {
            const catalog = messages(locale).DocumentsLibrary;
            expect(catalog.viewDocuments).toBeTruthy();
            expect(catalog.viewTemplates).toBeTruthy();
            expect(catalog.viewDocuments).not.toBe(catalog.viewTemplates);
        }
        expect(messages("en").DocumentsLibrary.viewTemplates).toBe("Document templates");
    });

    it("offers the template create action only on the templates view, so each view keeps one primary action", () => {
        const library = source(LIBRARY);

        expect(library).toContain("view === 'templates' ? (");
        expect(library).toContain("t('newTemplate')");
    });

    it("reads the cross-deal index rather than a per-deal list", () => {
        const index = source(INDEX);

        expect(index).toContain("getGeneratedDocuments");
        expect(index).toContain("useServerRecords");
        expect(index).toContain("dealDocumentsHref(document.dealId)");
        expect(source("app/lib/api.ts")).toContain("`/api/documents${buildQuery(params)}`");
    });

    it("carries the status, deal, owner, and date a reader needs to recognize a document", () => {
        const index = source(INDEX);

        for (const column of ["columnDocument", "columnDeal", "columnStatus", "columnOwner", "columnGenerated"]) {
            expect(index).toContain(`t('${column}')`);
            for (const locale of ["en", "ja"]) {
                expect(messages(locale).GeneratedDocuments[column]).toBeTruthy();
            }
        }
    });

    it("leaves the page shell to the parent, so the two views never stack two page headers", () => {
        const templates = source(TEMPLATES);

        expect(templates).not.toContain("PageShell");
        expect(templates).not.toContain("PageHeader");
        expect(source(LIBRARY)).toContain("PageShell");
    });

    it("puts the chosen half in the URL on a key no other writer owns, so both are linkable", () => {
        const library = source(LIBRARY);

        expect(library).toContain("const VIEW_URL_KEY = 'list'");
        expect(library, "`view` belongs to the records browsers").not.toContain("VIEW_URL_KEY = 'view'");
        expect(library).toContain("useOwnedUrlParams({ [VIEW_URL_KEY]: view === 'documents' ? undefined : view })");
        expect(library).toContain("normalizeView(searchParams.get(VIEW_URL_KEY))");
    });

    it("teaches the first-run empty state where documents come from", () => {
        const index = source(INDEX);

        expect(index).toContain("router.push('/records/deals')");
        expect(index).toContain("t('emptyAction')");
        for (const locale of ["en", "ja"]) {
            expect(messages(locale).GeneratedDocuments.emptyAction).toBeTruthy();
        }
        expect(resolveShippedRoute("/records/deals")).toBe("/records/deals");
    });

    it("keeps every generated-documents string translated in both catalogs", () => {
        const en = messages("en");
        const ja = messages("ja");

        for (const namespace of ["DocumentsLibrary", "GeneratedDocuments"]) {
            expect(Object.keys(ja[namespace]).sort()).toEqual(Object.keys(en[namespace]).sort());
        }
    });
});
