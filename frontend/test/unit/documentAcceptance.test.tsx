/** @vitest-environment jsdom */
import { isValidElement, StrictMode, type ReactNode } from "react";
import { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { renderToStaticMarkup } from "react-dom/server";
import { NextIntlClientProvider } from "next-intl";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import type {
    DocumentAcceptanceFailureKind,
    DocumentAcceptancePreview,
} from "@/app/lib/types";
import { formatDateTime, formatUtcDateTime } from "@/app/lib/utils";
import dealsMessages from "@/messages/en/deals.json";
import acceptanceMessages from "@/messages/en/document-acceptance.json";
import jaAcceptanceMessages from "@/messages/ja/document-acceptance.json";

declare global {
    var IS_REACT_ACT_ENVIRONMENT: boolean;
}
globalThis.IS_REACT_ACT_ENVIRONMENT = true;

const api = vi.hoisted(() => ({
    markViewed: vi.fn<(token: string) => Promise<DocumentAcceptancePreview>>(),
    accept: vi.fn<(token: string, payload: { typedName: string }) => Promise<unknown>>(),
    decline: vi.fn<(token: string, payload: { reason: string }) => Promise<unknown>>(),
}));
const serverTranslations = vi.hoisted(() => vi.fn());
const requestHeaders = vi.hoisted(() => vi.fn<() => Promise<Headers>>());
const reactCacheState = vi.hoisted(() => {
    const clearers: Array<() => void> = [];
    return {
        register(clear: () => void) {
            clearers.push(clear);
        },
        clear() {
            for (const clear of clearers) clear();
        },
    };
});

vi.mock("react", async () => {
    const React = await vi.importActual<typeof import("react")>("react");
    return {
        ...React,
        cache: function cache<Args extends unknown[], Result>(callback: (...args: Args) => Result) {
            const results = new Map<string, { value: Result }>();
            reactCacheState.register(() => results.clear());
            return (...args: Args): Result => {
                const key = JSON.stringify(args) ?? "[]";
                const cached = results.get(key);
                if (cached) return cached.value;
                const value = callback(...args);
                results.set(key, { value });
                return value;
            };
        },
    };
});

vi.mock("next/headers", () => ({
    headers: requestHeaders,
}));

vi.mock("next-intl/server", () => ({
    getTranslations: serverTranslations,
}));

vi.mock("@/app/lib/api", async () => {
    const actual = await vi.importActual<typeof import("@/app/lib/api")>("@/app/lib/api");
    return {
        ...actual,
        markDocumentAcceptanceViewed: api.markViewed,
        acceptDocument: api.accept,
        declineDocument: api.decline,
    };
});

import DocumentAcceptance from "@/app/components/marketing/campaigns/DocumentAcceptance";
import { documentAcceptanceViewFailure } from "@/app/components/marketing/campaigns/documentAcceptance";
import DocumentAcceptanceUnavailable from "@/app/components/marketing/campaigns/DocumentAcceptanceUnavailable";
import DocumentAcceptancePage, {
    generateMetadata,
} from "@/app/document-acceptance/[token]/page";
import { ApiError } from "@/app/lib/api";

const TOKEN = `w12-${"a".repeat(64)}`;
const UNAVAILABLE_BODY = JSON.stringify({
    code: "RESOURCE_NOT_FOUND",
    message: "Document link is no longer available",
});
const MESSAGES = {
    ...acceptanceMessages,
    DealsDocuments: dealsMessages.DealsDocuments,
};

function preview(overrides: Partial<DocumentAcceptancePreview> = {}): DocumentAcceptancePreview {
    return {
        content: {
            generatedAt: "2026-09-01T10:30:00",
            workspace: { name: "Hikari Systems", address: "Tokyo" },
            company: { name: "Northstar Trading", address: "Osaka" },
            owner: { name: "Aiko Mori" },
            deal: { name: "Autumn renewal", currency: "JPY" },
            sections: {
                title: "Template section title",
                intro: "Please review the terms below.",
                terms: "Payment is due within 30 days.",
                footer: "Thank you.",
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
        documentType: "quote",
        documentTitle: "Frozen document title",
        documentVersion: 3,
        documentLocale: "en",
        expiresAt: "2026-09-08T10:30:00Z",
        ...overrides,
    };
}

function jsonResponse(body: unknown, status = 200): Response {
    return new Response(JSON.stringify(body), {
        status,
        headers: { "Content-Type": "application/json" },
    });
}

function stubPreview(body = preview()): ReturnType<typeof vi.fn> {
    const fetchMock = vi.fn().mockImplementation(async () => jsonResponse(body));
    vi.stubGlobal("fetch", fetchMock);
    return fetchMock;
}

function deferred<T>() {
    let callbacks: {
        resolve: (value: T) => void;
        reject: (reason?: unknown) => void;
    } | undefined;
    const promise = new Promise<T>((resolvePromise, rejectPromise) => {
        callbacks = { resolve: resolvePromise, reject: rejectPromise };
    });
    if (!callbacks) throw new Error("Deferred promise callbacks were not initialized");
    return { promise, resolve: callbacks.resolve, reject: callbacks.reject };
}

async function renderAcceptance(initialPreview = preview()) {
    const container = document.createElement("div");
    document.body.appendChild(container);
    const root = createRoot(container, { onCaughtError: vi.fn() });
    await act(async () => {
        root.render(
            <NextIntlClientProvider locale="en" messages={MESSAGES}>
                <DocumentAcceptance initialPreview={initialPreview} />
            </NextIntlClientProvider>,
        );
        await Promise.resolve();
        await Promise.resolve();
    });
    return { container, root };
}

async function renderStrictAcceptance(initialPreview = preview()) {
    const container = document.createElement("div");
    document.body.appendChild(container);
    const root = createRoot(container, { onCaughtError: vi.fn() });
    await act(async () => {
        root.render(
            <NextIntlClientProvider locale="en" messages={MESSAGES}>
                <StrictMode>
                    <DocumentAcceptance initialPreview={initialPreview} />
                </StrictMode>
            </NextIntlClientProvider>,
        );
        await Promise.resolve();
        await Promise.resolve();
    });
    return { container, root };
}

async function unmount(root: Root) {
    await act(async () => root.unmount());
}

function button(container: HTMLElement, label: string): HTMLButtonElement {
    const candidate = [...container.querySelectorAll("button")]
        .find((element) => element.textContent?.trim() === label);
    if (!candidate) throw new Error(`Button not found: ${label}`);
    return candidate;
}

async function click(element: HTMLElement) {
    await act(async () => {
        element.click();
        await Promise.resolve();
        await Promise.resolve();
    });
}

async function enterValue(element: HTMLInputElement | HTMLTextAreaElement, value: string) {
    const prototype = element instanceof HTMLInputElement
        ? HTMLInputElement.prototype
        : HTMLTextAreaElement.prototype;
    const setter = Object.getOwnPropertyDescriptor(prototype, "value")?.set;
    if (!setter) throw new Error("Form control value setter is unavailable");
    await act(async () => {
        setter.call(element, value);
        element.dispatchEvent(new Event("input", { bubbles: true }));
    });
}

beforeEach(() => {
    vi.clearAllMocks();
    reactCacheState.clear();
    requestHeaders.mockResolvedValue(new Headers());
    serverTranslations.mockImplementation(async (
        options: string | { locale?: string; namespace?: string },
    ) => {
        const catalog = typeof options === "object" && options.locale === "ja"
            ? jaAcceptanceMessages.DocumentAcceptance
            : acceptanceMessages.DocumentAcceptance;
        return (key: keyof typeof acceptanceMessages.DocumentAcceptance) => catalog[key];
    });
    window.history.replaceState({}, "", `/document-acceptance/${TOKEN}`);
    stubPreview();
    api.markViewed.mockImplementation(async () => preview({
        deliveryStatus: "viewed",
        recipientStatus: "viewed",
    }));
    api.accept.mockResolvedValue({
        deliveryStatus: "completed",
        recipientStatus: "completed",
        completed: true,
    });
    api.decline.mockResolvedValue({
        deliveryStatus: "declined",
        recipientStatus: "declined",
        completed: false,
    });
});

afterEach(() => {
    document.body.replaceChildren();
    vi.unstubAllGlobals();
    vi.unstubAllEnvs();
});

describe("document acceptance", () => {
    it("sends a malformed path token through the same backend round trip", async () => {
        const fetchMock = stubPreview();
        fetchMock.mockResolvedValueOnce(new Response(UNAVAILABLE_BODY, {
            status: 404,
            headers: { "Content-Type": "application/json" },
        }));
        const page = await DocumentAcceptancePage({
            params: Promise.resolve({ token: "not-a-bearer" }),
        });

        expect(fetchMock).toHaveBeenCalledTimes(1);
        expect(String(fetchMock.mock.calls[0]?.[0])).toContain("not-a-bearer");
        expect(isValidElement(page) && page.type).toBe(DocumentAcceptanceUnavailable);
        expect(api.markViewed).not.toHaveBeenCalled();
    });

    it("forwards the incoming client address and shares one preview across metadata and page", async () => {
        requestHeaders.mockResolvedValue(new Headers({
            "X-Forwarded-For": "203.0.113.44",
        }));
        const fetchMock = stubPreview(preview({ documentLocale: "ja" }));

        const metadata = await generateMetadata({
            params: Promise.resolve({ token: TOKEN }),
        });
        const page = await DocumentAcceptancePage({
            params: Promise.resolve({ token: TOKEN }),
        });

        expect(metadata.title).toBe("ドキュメントの確認 | Connex");
        expect(isValidElement(page)).toBe(true);
        expect(fetchMock).toHaveBeenCalledTimes(1);
        const init = fetchMock.mock.calls[0]?.[1];
        expect(new Headers(init?.headers).get("x-forwarded-for"))
            .toBe("203.0.113.44");
    });

    it("does not forward a multi-hop address from a recipient request", async () => {
        requestHeaders.mockResolvedValue(new Headers({
            "X-Forwarded-For": "198.51.100.91, 10.0.0.2",
        }));
        const fetchMock = stubPreview();

        await DocumentAcceptancePage({
            params: Promise.resolve({ token: TOKEN }),
        });

        const init = fetchMock.mock.calls[0]?.[1];
        expect(new Headers(init?.headers).get("x-forwarded-for")).toBeNull();
    });

    it("renders byte-identical server-only copy for distinct preview 404 responses", async () => {
        const causes = [
            "unknown token",
            "wrong hash",
            "inactive workspace",
            "expired",
            "voided",
            "completed or declined",
        ];
        const renderedCopy: string[] = [];

        for (const [index, cause] of causes.entries()) {
            const candidateToken = `w${20 + index}-${String(index + 1).repeat(64)}`;
            const fetchMock = vi.fn().mockResolvedValueOnce(new Response(UNAVAILABLE_BODY, {
                status: 404,
                headers: { "Content-Type": "application/json" },
            }));
            vi.stubGlobal("fetch", fetchMock);
            const page = await DocumentAcceptancePage({
                params: Promise.resolve({ token: candidateToken }),
            });

            expect(fetchMock, cause).toHaveBeenCalledTimes(1);
            expect(String(fetchMock.mock.calls[0]?.[0]), cause).toContain(candidateToken);
            expect(isValidElement(page) && page.type, cause).toBe(DocumentAcceptanceUnavailable);
            renderedCopy.push(renderToStaticMarkup(page));
        }

        expect(new Set(renderedCopy)).toEqual(new Set([renderedCopy[0]]));
        expect(renderedCopy[0]).toContain("Link unavailable");
        expect(api.markViewed).not.toHaveBeenCalled();
    });

    it.each([
        [429, "Too many document-link requests", "Try again shortly"],
        [503, "This deployment cannot serve the request", "Document review is unavailable"],
    ])("keeps a preview GET %s server-only and inert", async (status, body, title) => {
        const fetchMock = vi.fn().mockResolvedValueOnce(new Response(body, {
            status,
            headers: { "Content-Type": "text/plain" },
        }));
        vi.stubGlobal("fetch", fetchMock);

        const page = await DocumentAcceptancePage({
            params: Promise.resolve({ token: TOKEN }),
        });

        expect(isValidElement(page) && page.type).toBe(DocumentAcceptanceUnavailable);
        expect(renderToStaticMarkup(page)).toContain(title);
        expect(api.markViewed).not.toHaveBeenCalled();
    });

    it("scopes the public page and document renderer to the frozen document locale", async () => {
        stubPreview(preview({ documentLocale: "ja" }));

        const page = await DocumentAcceptancePage({
            params: Promise.resolve({ token: TOKEN }),
        });

        if (!isValidElement<{
            locale: string;
            messages: { DocumentAcceptance: { pageLabel: string } };
            children: ReactNode;
        }>(page)) {
            throw new Error("Locale provider not rendered");
        }
        expect(page.props.locale).toBe("ja");
        expect(page.props.messages.DocumentAcceptance.pageLabel).toBe("ドキュメントの確認");
        if (!isValidElement<{ lang: string }>(page.props.children)) {
            throw new Error("Language boundary not rendered");
        }
        expect(page.props.children.props.lang).toBe("ja");
        expect(api.markViewed).not.toHaveBeenCalled();
    });

    it("resolves metadata from the frozen document locale", async () => {
        stubPreview(preview({ documentLocale: "ja" }));

        const metadata = await generateMetadata({
            params: Promise.resolve({ token: TOKEN }),
        });

        expect(metadata.title).toBe("ドキュメントの確認 | Connex");
        expect(metadata.robots).toEqual({ index: false, follow: false });
        expect(JSON.stringify(metadata)).not.toContain(TOKEN);
    });

    it.each([404, 429, 503])("keeps preview GET %s metadata out of search indexes", async (status) => {
        const fetchMock = vi.fn().mockResolvedValueOnce(new Response(
            status === 404 ? UNAVAILABLE_BODY : "Temporarily unavailable",
            {
                status,
                headers: {
                    "Content-Type": status === 404 ? "application/json" : "text/plain",
                },
            },
        ));
        vi.stubGlobal("fetch", fetchMock);

        const metadata = await generateMetadata({
            params: Promise.resolve({ token: TOKEN }),
        });

        expect(metadata.robots).toEqual({ index: false, follow: false });
        expect(JSON.stringify(metadata)).not.toContain(TOKEN);
        expect(api.markViewed).not.toHaveBeenCalled();
    });

    it("does not echo the route bearer into page-owned props, metadata, or body markup", async () => {
        const page = await DocumentAcceptancePage({
            params: Promise.resolve({ token: TOKEN }),
        });
        if (!isValidElement<{ children: ReactNode }>(page)) {
            throw new Error("Locale provider not rendered");
        }
        const languageBoundary = page.props.children;
        if (!isValidElement<{ children: ReactNode }>(languageBoundary)) {
            throw new Error("Language boundary not rendered");
        }
        const client = languageBoundary.props.children;
        if (!isValidElement<{ initialPreview: DocumentAcceptancePreview }>(client)) {
            throw new Error("Acceptance client not rendered");
        }

        expect(client.type).toBe(DocumentAcceptance);
        expect(client.key).toBeNull();
        expect(client.props).not.toHaveProperty("token");
        expect(JSON.stringify(client.props)).not.toContain(TOKEN);
        expect(renderToStaticMarkup(page)).not.toContain(TOKEN);
    });

    it("renders the frozen title and records one view under React strict effects", async () => {
        const initial = preview();
        api.markViewed.mockResolvedValueOnce({
            ...initial,
            deliveryStatus: "viewed",
            recipientStatus: "viewed",
        });
        const rendered = await renderStrictAcceptance(initial);

        expect(rendered.container.textContent).toContain("Frozen document title");
        expect(rendered.container.textContent).not.toContain("Template section title");
        expect(rendered.container.textContent).toContain("Hikari Systems");
        expect(rendered.container.textContent).toContain("r***@example.test");
        expect(rendered.container.textContent).toContain(
            formatDateTime(initial.expiresAt ?? undefined, "en"),
        );
        expect(button(rendered.container, "Accept").disabled).toBe(false);
        expect(button(rendered.container, "Decline").disabled).toBe(false);
        expect(api.markViewed).toHaveBeenCalledTimes(1);
        expect(api.markViewed).toHaveBeenCalledWith(TOKEN);

        await unmount(rendered.root);
    });

    it("interprets the offset-less generated timestamp as UTC", async () => {
        vi.stubEnv("TZ", "Asia/Tokyo");
        const initial = preview();
        const utcDisplay = formatUtcDateTime(initial.content.generatedAt, "en");
        const browserLocalDisplay = formatDateTime(initial.content.generatedAt, "en");
        expect(utcDisplay).not.toBe(browserLocalDisplay);

        const rendered = await renderAcceptance(initial);

        expect(rendered.container.textContent).toContain(utcDisplay);
        expect(rendered.container.textContent).not.toContain(browserLocalDisplay);
        await unmount(rendered.root);
    });

    it("keeps decision controls disabled until the deferred viewed request resolves", async () => {
        const viewed = deferred<DocumentAcceptancePreview>();
        api.markViewed.mockReturnValueOnce(viewed.promise);
        const rendered = await renderAcceptance();

        expect(button(rendered.container, "Accept").disabled).toBe(true);
        expect(button(rendered.container, "Decline").disabled).toBe(true);
        expect(rendered.container.querySelector('[role="status"]')?.textContent)
            .toContain("Preparing your response options");
        expect(api.accept).not.toHaveBeenCalled();
        expect(api.decline).not.toHaveBeenCalled();

        await act(async () => {
            viewed.resolve(preview({ deliveryStatus: "viewed", recipientStatus: "viewed" }));
            await viewed.promise;
        });

        expect(button(rendered.container, "Accept").disabled).toBe(false);
        expect(button(rendered.container, "Decline").disabled).toBe(false);
        expect(rendered.container.querySelector('[role="status"]')).toBeNull();
        await unmount(rendered.root);
    });

    it("ignores a deferred viewed 404 after a successful terminal decision", async () => {
        const viewed = deferred<DocumentAcceptancePreview>();
        let terminalReceipt = false;
        let failure: DocumentAcceptanceFailureKind | null = null;
        const observedView = viewed.promise.catch((error: unknown) => {
            failure = documentAcceptanceViewFailure(terminalReceipt, error);
        });

        await api.accept(TOKEN, { typedName: "Rina Sato" });
        terminalReceipt = true;
        viewed.reject(new ApiError("Document link is no longer available", 404));
        await observedView;

        expect(failure).toBeNull();
    });

    it("hides decision controls for a viewer", async () => {
        const viewerPreview = preview({ actionable: false });
        api.markViewed.mockResolvedValue({
            ...viewerPreview,
            deliveryStatus: "viewed",
            recipientStatus: "viewed",
        });
        const rendered = await renderAcceptance(viewerPreview);

        expect(rendered.container.textContent).toContain("Shared for review");
        expect(rendered.container.querySelectorAll("button")).toHaveLength(0);

        await unmount(rendered.root);
    });

    it("accepts a trimmed typed name and settles into an in-session receipt", async () => {
        const rendered = await renderAcceptance();
        await click(button(rendered.container, "Accept"));
        const input = rendered.container.querySelector<HTMLInputElement>("#document-acceptance-name");
        if (!input) throw new Error("Typed-name field not found");
        expect(input.form?.hasAttribute("action")).toBe(false);
        expect(input.maxLength).toBe(255);
        await enterValue(input, "  Rina Sato  ");
        await click(button(rendered.container, "Confirm acceptance"));

        expect(api.accept).toHaveBeenCalledWith(TOKEN, { typedName: "Rina Sato" });
        expect(rendered.container.textContent).toContain("Document accepted");
        expect(rendered.container.textContent).not.toContain("Confirm acceptance");

        await unmount(rendered.root);
    });

    it("declines with a trimmed reason and settles into an in-session receipt", async () => {
        const rendered = await renderAcceptance();
        await click(button(rendered.container, "Decline"));
        const textarea = rendered.container.querySelector<HTMLTextAreaElement>("#document-decline-reason");
        if (!textarea) throw new Error("Decline-reason field not found");
        expect(textarea.maxLength).toBe(500);
        await enterValue(textarea, "  Commercial terms do not work  ");
        await click(button(rendered.container, "Confirm decline"));

        expect(api.decline).toHaveBeenCalledWith(TOKEN, {
            reason: "Commercial terms do not work",
        });
        expect(rendered.container.textContent).toContain("Document declined");

        await unmount(rendered.root);
    });

    it("omits credentials, workspace context, and CSRF headers from every public call", async () => {
        const fetchMock = vi.fn<(
            input: RequestInfo | URL,
            init?: RequestInit,
        ) => Promise<Response>>().mockImplementation(async (input) => {
            const url = String(input);
            if (url.endsWith("/accept")) {
                return jsonResponse({
                    deliveryStatus: "completed",
                    recipientStatus: "completed",
                    completed: true,
                });
            }
            if (url.endsWith("/decline")) {
                return jsonResponse({
                    deliveryStatus: "declined",
                    recipientStatus: "declined",
                    completed: false,
                });
            }
            return jsonResponse(preview());
        });
        vi.stubGlobal("fetch", fetchMock);
        const actual = await vi.importActual<typeof import("@/app/lib/api")>("@/app/lib/api");

        await actual.getDocumentAcceptancePreview(TOKEN);
        await actual.markDocumentAcceptanceViewed(TOKEN);
        await actual.acceptDocument(TOKEN, { typedName: "Rina Sato" });
        await actual.declineDocument(TOKEN, { reason: "Commercial terms" });

        expect(fetchMock).toHaveBeenCalledTimes(4);
        for (const call of fetchMock.mock.calls) {
            const init = call[1];
            if (!init) throw new Error("Public request options were not provided");
            const headers = new Headers(init.headers);
            expect(init.credentials).toBe("omit");
            expect(headers.has("Authorization")).toBe(false);
            expect(headers.has("Cookie")).toBe(false);
            expect(headers.has("X-Workspace-Id")).toBe(false);
            expect([...headers.keys()].some((key) => key.toLowerCase().includes("csrf"))).toBe(false);
        }
    });
});
