import { readFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";

import { dealDocumentsHref } from "@/app/components/records/deals/dealLinks";
import { resolveShippedRoute } from "@/app/lib/routeManifest";

const LIBRARY = "app/components/library/documents/DocumentsLibrary.tsx";
const INDEX = "app/components/library/documents/GeneratedDocumentsBrowser.tsx";
const TEMPLATES = "app/components/library/documents/DocumentTemplatesBrowser.tsx";

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
        expect(dealDocumentsHref(42)).toBe("/records/deals/42#deal-detail-files");
        expect(resolveShippedRoute(dealDocumentsHref(42))).toBe("/records/deals/[id]");
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

    it("keeps every generated-documents string translated in both catalogs", () => {
        const en = messages("en");
        const ja = messages("ja");

        for (const namespace of ["DocumentsLibrary", "GeneratedDocuments"]) {
            expect(Object.keys(ja[namespace]).sort()).toEqual(Object.keys(en[namespace]).sort());
        }
    });
});
