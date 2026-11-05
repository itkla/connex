/** @vitest-environment jsdom */
import { renderToStaticMarkup } from "react-dom/server";
import { NextIntlClientProvider } from "next-intl";
import { describe, expect, it } from "vitest";

import DocumentView from "@/app/components/records/documents/DocumentView";
import DocumentPaper from "@/app/components/records/deals/DocumentPaper";
import DocumentAcceptance from "@/app/components/marketing/campaigns/DocumentAcceptance";
import type { DealDocument, DocumentAcceptancePreview, DocumentBodyNode, DocumentContent } from "@/app/lib/types";
import dealsMessages from "@/messages/en/deals.json";
import jaDealsMessages from "@/messages/ja/deals.json";
import acceptanceMessages from "@/messages/en/document-acceptance.json";
import jaAcceptanceMessages from "@/messages/ja/document-acceptance.json";

const frozenContent: DocumentContent = {
    generatedAt: "2026-09-01T10:30:00",
    deal: { name: "Agreement", currency: "USD" },
    sections: { title: "Agreement" },
    lineItems: [{
        id: 1,
        dealId: 1,
        name: "Services",
        unitPrice: 100000,
        quantity: 1,
        billingFrequency: "one_time",
        position: 0,
        currency: "USD",
        lineSubtotal: 100000,
        lineTax: 0,
        lineTotal: 100000,
        createdAt: "2026-09-01T10:30:00",
        updatedAt: "2026-09-01T10:30:00",
    }],
    totals: {
        currency: "USD",
        subtotal: 100000,
        tax: 0,
        oneTimeTotal: 100000,
        recurringTotal: 0,
        grandTotal: 100000,
    },
};

function renderedDocument(nodes?: DocumentBodyNode[]): Document {
    const markup = renderToStaticMarkup(
        <NextIntlClientProvider locale="en" timeZone="UTC" messages={dealsMessages}>
            <DocumentView
                type="quote"
                documentLocale="en"
                content={{
                    ...frozenContent,
                    body: nodes ? { type: "doc", content: nodes } : null,
                }}
            />
        </NextIntlClientProvider>,
    );
    return new DOMParser().parseFromString(markup, "text/html");
}

function expectFrozenTotal(document: Document) {
    const table = document.querySelectorAll('[data-testid="document-line-items-table"]');
    const stacked = document.querySelectorAll('[data-testid="document-line-items-stacked"]');
    expect(table).toHaveLength(1);
    expect(stacked).toHaveLength(1);
    const expected = new Intl.NumberFormat("en", {
        style: "currency", currency: "USD", maximumFractionDigits: 2,
    }).format(frozenContent.totals.grandTotal);
    expect(table[0].querySelector("tfoot tr:last-child td:last-child")?.textContent).toBe(expected);
    expect(stacked[0].querySelector("dl:last-child > div:last-child dd")?.textContent).toBe(expected);
}

describe("DocumentView authoritative monetary content", () => {
    it.each([
        { uiLocale: "en", documentLocale: "ja", expected: "￥220" },
        { uiLocale: "ja", documentLocale: "en", expected: "¥220" },
    ])("keeps $documentLocale document money consistent under $uiLocale UI in every renderer", ({
        uiLocale, documentLocale, expected,
    }) => {
        for (const blockBody of [false, true]) {
            const content: DocumentContent = {
                ...frozenContent,
                deal: { ...frozenContent.deal, currency: "JPY" },
                sections: { title: "Quote", terms: `Frozen total: ${expected}` },
                body: blockBody ? {
                    type: "doc",
                    content: [
                        { type: "paragraph", content: [{ type: "text", text: `Frozen total: ${expected}` }] },
                        { type: "lineItems" },
                    ],
                } : null,
                lineItems: frozenContent.lineItems.map((item) => ({
                    ...item, currency: "JPY", unitPrice: 220, lineSubtotal: 220, lineTotal: 220,
                })),
                totals: {
                    currency: "JPY", subtotal: 220, tax: 0, oneTimeTotal: 220,
                    recurringTotal: 0, grandTotal: 220,
                },
            };
            const document: DealDocument = {
                id: 1, dealId: 1, type: "quote", locale: documentLocale, status: "draft",
                version: 1, currency: "JPY", generatedAt: content.generatedAt, createdBy: null,
                content, requiresApproval: false,
            };
            const preview: DocumentAcceptancePreview = {
                flowId: "frozen-flow", content, dealName: "Quote", workspaceName: "Workspace",
                recipientEmail: "recipient", deliveryStatus: "sent", recipientStatus: "pending",
                actionable: false, documentType: "quote", documentVersion: 1, documentLocale,
            };
            const messages = uiLocale === "ja"
                ? { ...jaDealsMessages, ...jaAcceptanceMessages }
                : { ...dealsMessages, ...acceptanceMessages };
            for (const view of [
                <DocumentView key="view" type="quote" content={content} documentLocale={documentLocale} />,
                <DocumentPaper key="print" document={document} />,
                <DocumentAcceptance key="acceptance" initialPreview={preview} />,
            ]) {
                const markup = renderToStaticMarkup(
                    <NextIntlClientProvider locale={uiLocale} timeZone="UTC" messages={messages}>
                        {view}
                    </NextIntlClientProvider>,
                );
                const rendered = new DOMParser().parseFromString(markup, "text/html");
                expect(rendered.body.textContent).toContain(`Frozen total: ${expected}`);
                expect(rendered.querySelector(
                    '[data-testid="document-line-items-table"] tfoot tr:last-child td:last-child',
                )?.textContent).toBe(expected);
                expect(rendered.querySelector(
                    '[data-testid="document-line-items-stacked"] dl:last-child > div:last-child dd',
                )?.textContent).toBe(expected);
                expect(rendered.querySelector(
                    '[data-testid="document-line-items-table"] tbody tr td:last-child',
                )?.textContent).toBe(expected);
            }
        }
    });

    it.each(["paragraph", "heading", "codeBlock", "unknown", "horizontalRule"])(
        "renders the frozen USD 100,000 fallback when lineItems is hidden inside %s",
        (type) => {
            const document = renderedDocument([{
                type,
                content: [
                    { type: "text", text: "Total payable: USD 100." },
                    { type: "lineItems" },
                ],
            }]);

            expectFrozenTotal(document);
        },
    );

    it.each(["bulletList", "orderedList"])(
        "renders the fallback for inline lineItems inside a %s item",
        (type) => {
            expectFrozenTotal(renderedDocument([{
                type,
                content: [{
                    type: "listItem",
                    content: [{ type: "paragraph", content: [{ type: "lineItems" }] }],
                }],
            }]));
        },
    );

    it.each(["bulletList", "orderedList"])(
        "renders the fallback for a direct lineItems child of %s",
        (type) => {
            expectFrozenTotal(renderedDocument([{ type, content: [{ type: "lineItems" }] }]));
        },
    );

    it.each([
        [{ type: "lineItems" }],
        [{ type: "blockquote", content: [{ type: "lineItems" }] }],
        [{
            type: "bulletList",
            content: [{
                type: "listItem",
                content: [{ type: "paragraph" }, { type: "lineItems" }],
            }],
        }],
    ])("renders a supported block table once: %j", (...nodes) => {
        expectFrozenTotal(renderedDocument(nodes));
    });

    it("renders the legacy and missing-placeholder fallback", () => {
        expectFrozenTotal(renderedDocument());
        expectFrozenTotal(renderedDocument([{ type: "paragraph" }]));
    });
});
