import { afterEach, describe, expect, it, vi } from "vitest";

import {
    acceptDocument,
    ApiError,
    documentAcceptanceFailureKind,
    getDocumentAcceptancePreview,
} from "@/app/lib/api";
import type { DocumentAcceptancePreview } from "@/app/lib/types";

const TOKEN = `w42-${"a".repeat(64)}`;

function validPreview(): DocumentAcceptancePreview {
    return {
        content: {
            generatedAt: "2026-09-01T10:30:00",
            workspace: { name: "Hikari Systems", address: "Tokyo" },
            company: { name: "Northstar Trading", address: "Osaka" },
            owner: { name: "Aiko Mori" },
            deal: { name: "Autumn renewal", currency: "JPY" },
            sections: {
                title: "Frozen agreement",
                intro: null,
                terms: null,
                footer: null,
            },
            lineItems: [],
            totals: {
                currency: null,
                subtotal: 0,
                tax: 0,
                oneTimeTotal: 0,
                recurringTotal: 0,
                grandTotal: 0,
            },
        },
        dealName: "Autumn renewal",
        workspaceName: "Hikari Systems",
        recipientEmail: "r***@example.test",
        deliveryStatus: "sent",
        recipientStatus: "pending",
        actionable: true,
        documentType: "contract",
        documentTitle: "Frozen agreement",
        documentVersion: 1,
        documentLocale: "en",
        expiresAt: "2026-09-08T10:30:00Z",
    };
}

function stubPublicResponse(body: unknown): void {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify(body), {
        status: 200,
        headers: { "Content-Type": "application/json" },
    })));
}

afterEach(() => {
    vi.unstubAllGlobals();
});

describe("document acceptance public response boundary", () => {
    it("rejects a successful preview with a malformed shape as unavailable", async () => {
        vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify({
            actionable: "yes",
        }), {
            status: 200,
            headers: { "Content-Type": "application/json" },
        })));

        const request = getDocumentAcceptancePreview(TOKEN);

        await expect(request).rejects.toMatchObject({
            status: 502,
            code: "INVALID_PUBLIC_RESPONSE",
        });
        await request.catch((error: unknown) => {
            expect(documentAcceptanceFailureKind(error)).toBe("service-unavailable");
        });
    });

    it("rejects malformed decision JSON instead of producing a terminal receipt", async () => {
        vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify({
            deliveryStatus: "completed",
            recipientStatus: "completed",
            completed: "yes",
        }), {
            status: 200,
            headers: { "Content-Type": "application/json" },
        })));

        const request = acceptDocument(TOKEN, { typedName: "Rina Sato" });

        await expect(request).rejects.toBeInstanceOf(ApiError);
        await request.catch((error: unknown) => {
            expect(documentAcceptanceFailureKind(error)).toBe("service-unavailable");
        });
    });

    it("rejects a malformed successful JSON body before rendering it", async () => {
        vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("{not-json", {
            status: 200,
            headers: { "Content-Type": "application/json" },
        })));

        await expect(getDocumentAcceptancePreview(TOKEN)).rejects.toMatchObject({
            status: 502,
            code: "INVALID_PUBLIC_RESPONSE",
        });
    });

    it("rejects an expiry that is not an ISO-8601 instant", async () => {
        const response = validPreview();
        response.expiresAt = "not-a-date";
        stubPublicResponse(response);

        await expect(getDocumentAcceptancePreview(TOKEN)).rejects.toMatchObject({
            status: 502,
            code: "INVALID_PUBLIC_RESPONSE",
        });
    });

    it("rejects a normalized-but-impossible calendar instant", async () => {
        const response = validPreview();
        response.expiresAt = "2026-02-30T10:30:00Z";
        stubPublicResponse(response);

        await expect(getDocumentAcceptancePreview(TOKEN)).rejects.toMatchObject({
            status: 502,
            code: "INVALID_PUBLIC_RESPONSE",
        });
    });

    it("rejects a generated timestamp that is not an ISO-8601 local date-time", async () => {
        const response = validPreview();
        response.content.generatedAt = "2026-09-01 10:30:00";
        stubPublicResponse(response);

        await expect(getDocumentAcceptancePreview(TOKEN)).rejects.toMatchObject({
            status: 502,
            code: "INVALID_PUBLIC_RESPONSE",
        });
    });

    it.each([
        "2026-09-01T10:30",
        "2026-09-01T10:30:00.123456789",
    ])("accepts the backend LocalDateTime representation %s", async (generatedAt) => {
        const response = validPreview();
        response.content.generatedAt = generatedAt;
        stubPublicResponse(response);

        await expect(getDocumentAcceptancePreview(TOKEN)).resolves.toEqual(response);
    });

    it("accepts a permitted unsupported persisted locale", async () => {
        const response = validPreview();
        response.documentLocale = "fr";
        stubPublicResponse(response);

        await expect(getDocumentAcceptancePreview(TOKEN)).resolves.toEqual(response);
    });

    it.each([
        ["deal", (response: DocumentAcceptancePreview) => {
            response.content.deal.currency = " ";
        }],
        ["totals", (response: DocumentAcceptancePreview) => {
            response.content.totals.currency = "123456789";
        }],
    ])("rejects a malformed %s currency before rendering", async (_field, mutate) => {
        const response = validPreview();
        mutate(response);
        stubPublicResponse(response);

        await expect(getDocumentAcceptancePreview(TOKEN)).rejects.toMatchObject({
            status: 502,
            code: "INVALID_PUBLIC_RESPONSE",
        });
    });

    it.each([null, undefined])(
        "accepts an empty document whose totals currency is %s",
        async (currency) => {
            const response = validPreview();
            response.content.totals.currency = currency;
            stubPublicResponse(response);

            await expect(getDocumentAcceptancePreview(TOKEN)).resolves.toEqual(response);
        },
    );

    it("accepts every nullable preview field when non-null serialization omits its key", async () => {
        const response = validPreview();
        response.content.lineItems = [{
            id: 1,
            dealId: 2,
            name: "Implementation",
            unitPrice: 100,
            quantity: 1,
            billingFrequency: "one_time",
            position: 0,
            currency: "JPY",
            lineSubtotal: 100,
            lineTax: 0,
            lineTotal: 100,
            createdAt: "2026-09-01T10:00:00",
            updatedAt: "2026-09-01T10:00:00",
        }];
        delete response.documentTitle;
        delete response.expiresAt;
        delete response.content.workspace?.address;
        delete response.content.company;
        delete response.content.owner;
        delete response.content.sections.title;
        delete response.content.sections.intro;
        delete response.content.sections.terms;
        delete response.content.sections.footer;
        delete response.content.body;
        delete response.content.totals.currency;
        stubPublicResponse(response);

        await expect(getDocumentAcceptancePreview(TOKEN)).resolves.toEqual(response);
    });

    it("accepts product currency codes up to eight non-blank characters", async () => {
        const response = validPreview();
        response.content.deal.currency = "USDT";
        response.content.lineItems = [{
            id: 1,
            dealId: 2,
            productId: null,
            name: "Implementation",
            sku: null,
            unit: null,
            unitPrice: 100,
            quantity: 1,
            discountType: null,
            discountValue: null,
            taxRate: 0,
            billingFrequency: "one_time",
            description: null,
            servicePeriodStart: null,
            servicePeriodEnd: null,
            position: 0,
            currency: "USDT",
            lineSubtotal: 100,
            lineTax: 0,
            lineTotal: 100,
            createdAt: "2026-09-01T10:00:00",
            updatedAt: "2026-09-01T10:00:00",
        }];
        response.content.totals = {
            currency: "USDT",
            subtotal: 100,
            tax: 0,
            oneTimeTotal: 100,
            recurringTotal: 0,
            grandTotal: 100,
        };
        stubPublicResponse(response);

        await expect(getDocumentAcceptancePreview(TOKEN)).resolves.toEqual(response);
    });
});
