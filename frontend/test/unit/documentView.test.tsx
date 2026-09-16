/** @vitest-environment jsdom */
import { renderToStaticMarkup } from "react-dom/server";
import { NextIntlClientProvider } from "next-intl";
import { describe, expect, it } from "vitest";

import DocumentView from "@/app/components/records/documents/DocumentView";
import type { DocumentBodyNode, DocumentContent } from "@/app/lib/types";
import dealsMessages from "@/messages/en/deals.json";

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
