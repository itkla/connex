/** @vitest-environment jsdom */
import { renderToStaticMarkup } from "react-dom/server";
import { NextIntlClientProvider } from "next-intl";
import { afterEach, describe, expect, it, vi } from "vitest";

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
    expect(stacked[0].querySelector("section:last-child dl > div:last-child dd")?.textContent).toBe(expected);
    expect(document.body.textContent).not.toContain("Rounding adjustment");
}

afterEach(() => { vi.unstubAllEnvs(); });

describe("DocumentView authoritative monetary content", () => {
    it.each(["en", "ja"])("reconciles fractional yen in %s on screen, recipient view and print", (locale) => {
        for (const blockBody of [false, true]) {
            const content: DocumentContent = {
                ...frozenContent,
                deal: { ...frozenContent.deal, currency: "JPY" },
                sections: { title: "Quote", terms: "Frozen total: 201" },
                body: blockBody ? { type: "doc", content: [{ type: "lineItems" }] } : null,
                lineItems: [1, 2].map((id) => ({
                    ...frozenContent.lineItems[0], id, currency: "JPY", unitPrice: 100.5,
                    lineSubtotal: 100.5, lineTax: 0, lineTotal: 100.5,
                })),
                totals: {
                    currency: "JPY", subtotal: 201, tax: 0, oneTimeTotal: 201,
                    recurringTotal: 0, grandTotal: 201,
                },
            };
            const original = JSON.stringify(content);
            const document: DealDocument = {
                id: 1, dealId: 1, type: "quote", locale, status: "draft", version: 1,
                currency: "JPY", generatedAt: content.generatedAt, createdBy: null,
                content, requiresApproval: false,
            };
            const preview: DocumentAcceptancePreview = {
                flowId: "frozen-flow", content, dealName: "Quote", workspaceName: "Workspace",
                recipientEmail: "recipient", deliveryStatus: "sent", recipientStatus: "pending",
                actionable: false, documentType: "quote", documentVersion: 1, documentLocale: locale,
            };
            const messages = locale === "ja"
                ? { ...jaDealsMessages, ...jaAcceptanceMessages }
                : { ...dealsMessages, ...acceptanceMessages };
            const money = (amount: number) => new Intl.NumberFormat(locale, {
                style: "currency", currency: "JPY",
            }).format(amount);
            const label = locale === "ja" ? "端数調整" : "Rounding adjustment";
            for (const view of [
                <DocumentView key="view" type="quote" content={content} documentLocale={locale} />,
                <DocumentPaper key="print" document={document} />,
                <DocumentAcceptance key="acceptance" initialPreview={preview} />,
            ]) {
                const markup = renderToStaticMarkup(
                    <NextIntlClientProvider locale={locale} timeZone="UTC" messages={messages}>
                        {view}
                    </NextIntlClientProvider>,
                );
                const rendered = new DOMParser().parseFromString(markup, "text/html");
                const table = rendered.querySelector('[data-testid="document-line-items-table"]');
                expect(Array.from(table?.querySelectorAll("tbody tr td:last-child") ?? [])
                    .map((cell) => cell.textContent)).toEqual([money(101), money(101)]);
                expect(table?.querySelector("tfoot tr:nth-last-child(2)")?.textContent)
                    .toBe(`${label}−${money(1)}`);
                expect(table?.querySelector("tfoot tr:last-child td:last-child")?.textContent).toBe(money(201));
                const stacked = rendered.querySelector('[data-testid="document-line-items-stacked"]');
                expect(stacked?.querySelector("section:last-child dl > div:nth-last-child(2)")?.textContent)
                    .toBe(`${label}−${money(1)}`);
                expect(stacked?.querySelector("section:last-child dl > div:last-child dd")?.textContent).toBe(money(201));
                expect(JSON.stringify(content)).toBe(original);
            }
        }
    });

    it.each(["en", "ja"])("preserves and reconciles every %s tax and billing breakdown in print and mobile markup", (locale) => {
        const messages = locale === "ja" ? jaDealsMessages : dealsMessages;
        const label = messages.DealsDocuments.print;
        const cases = [
            { subtotal: 100.49, tax: 10.45, count: 1, recurring: false, expected: [100, 10, 1, 111, 111, 111] },
            { subtotal: 100.5, tax: 0, count: 2, recurring: true, expected: [201, 0, 201, 101, 101, -1, 201, 202, -1, 201] },
            { subtotal: 100.49, tax: 0, count: 2, recurring: false, expected: [201, 0, 201, 200, 1, 201] },
            { subtotal: 100.5, tax: 0, count: 5, recurring: false, expected: [503, 0, 503, 505, -2, 503] },
        ];
        const money = (amount: number) => new Intl.NumberFormat(locale, { style: "currency", currency: "JPY" }).format(amount);
        for (const scenario of cases) {
            const lineTotal = scenario.subtotal + scenario.tax;
            const grandTotal = lineTotal * scenario.count;
            const content: DocumentContent = {
                ...frozenContent,
                deal: { name: "Quote", currency: "JPY" },
                lineItems: Array.from({ length: scenario.count }, (_, index) => ({
                    ...frozenContent.lineItems[0], id: index, currency: "JPY", unitPrice: scenario.subtotal,
                    lineSubtotal: scenario.subtotal, lineTax: scenario.tax, lineTotal,
                    billingFrequency: scenario.recurring && index === 1 ? "recurring" : "one_time",
                })),
                totals: {
                    currency: "JPY", subtotal: scenario.subtotal * scenario.count, tax: scenario.tax * scenario.count,
                    oneTimeTotal: scenario.recurring ? lineTotal : grandTotal,
                    recurringTotal: scenario.recurring ? lineTotal : 0, grandTotal,
                },
            };
            const document: DealDocument = {
                id: 1, dealId: 1, type: "quote", locale, status: "draft", version: 1,
                currency: "JPY", generatedAt: content.generatedAt, createdBy: null,
                content, requiresApproval: false,
            };
            const markup = renderToStaticMarkup(
                <NextIntlClientProvider locale={locale} timeZone="UTC" messages={messages}>
                    <DocumentPaper document={document} />
                </NextIntlClientProvider>,
            );
            const rendered = new DOMParser().parseFromString(markup, "text/html");
            const expected = scenario.expected.map((value) => value < 0
                ? `−${money(-value)}` : money(value));
            const signedPositive = scenario.expected.includes(1) ? expected.map((value, index) =>
                scenario.expected[index] === 1 ? `+${value}` : value) : expected;
            expect(Array.from(rendered.querySelectorAll('tfoot tr td:last-child'))
                .map((cell) => cell.textContent)).toEqual(signedPositive);
            const stacked = rendered.querySelector('[data-testid="document-line-items-stacked"]');
            expect(Array.from(stacked?.querySelectorAll('section dl dd') ?? [])
                .map((cell) => cell.textContent)).toEqual(signedPositive);
            expect(rendered.body.textContent).toContain(label.subtotalTaxBreakdown);
            expect(rendered.body.textContent).toContain(label.itemAmountsBreakdown);
            let displayedSum = 0;
            for (const row of rendered.querySelectorAll('tfoot tr')) {
                if (row.querySelector('th')) continue;
                const amount = Number(row.querySelector('td:last-child')?.textContent
                    ?.replace(/[^\d+−-]/g, '').replace('−', '-'));
                if (row.querySelector('td:nth-child(2)')?.textContent === label.grandTotal) {
                    expect(displayedSum).toBe(amount);
                    displayedSum = 0;
                } else {
                    displayedSum += amount;
                }
            }
        }
    });

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
                    '[data-testid="document-line-items-stacked"] section:last-child dl > div:last-child dd',
                )?.textContent).toBe(expected);
                expect(rendered.querySelector(
                    '[data-testid="document-line-items-table"] tbody tr td:last-child',
                )?.textContent).toBe(expected);
            }
        }
    });

    it.each([
        { uiLocale: "en", documentLocale: "ja", timeZone: "Asia/Tokyo", expected: "2026/09/02 8:30" },
        { uiLocale: "ja", documentLocale: "en", timeZone: "Asia/Tokyo", expected: "Sep 2, 2026, 8:30 AM" },
        { uiLocale: "en", documentLocale: "ja", timeZone: "UTC", expected: "2026/09/01 23:30" },
        { uiLocale: "ja", documentLocale: "en", timeZone: "UTC", expected: "Sep 1, 2026, 11:30 PM" },
    ])("uses frozen $documentLocale dates with $timeZone under $uiLocale UI in every renderer", ({
        uiLocale, documentLocale, timeZone, expected,
    }) => {
        vi.stubEnv("TZ", "America/Los_Angeles");
        const content: DocumentContent = { ...frozenContent, generatedAt: "2026-09-01T23:30:00" };
        const document: DealDocument = {
            id: 1, dealId: 1, type: "quote", locale: documentLocale, status: "draft",
            version: 1, currency: "USD", generatedAt: content.generatedAt, createdBy: null,
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
            <DocumentView key="view" type="quote" content={content} documentLocale={documentLocale} generatedAt={content.generatedAt} />,
            <DocumentPaper key="print" document={document} />,
            <DocumentAcceptance key="acceptance" initialPreview={preview} />,
        ]) {
            const markup = renderToStaticMarkup(
                <NextIntlClientProvider locale={uiLocale} timeZone={timeZone} messages={messages}>
                    {view}
                </NextIntlClientProvider>,
            );
            const rendered = new DOMParser().parseFromString(markup, "text/html");
            expect(rendered.body.textContent).toContain(expected);
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
