/** @vitest-environment jsdom */
import { StrictMode } from "react";
import { act } from "react";
import { createRoot, type Root } from "react-dom/client";
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
    exchange: vi.fn<(token: string) => Promise<void>>(),
    preview: vi.fn<() => Promise<DocumentAcceptancePreview>>(),
    markViewed: vi.fn<() => Promise<DocumentAcceptancePreview>>(),
    accept: vi.fn<(payload: { flowId: string; typedName: string }) => Promise<unknown>>(),
    decline: vi.fn<(payload: { flowId: string; reason: string }) => Promise<unknown>>(),
}));

vi.mock("@/app/lib/api", async () => {
    const actual = await vi.importActual<typeof import("@/app/lib/api")>("@/app/lib/api");
    return {
        ...actual,
        exchangeDocumentAcceptanceToken: api.exchange,
        getDocumentAcceptancePreview: api.preview,
        markDocumentAcceptanceViewed: api.markViewed,
        acceptDocument: api.accept,
        declineDocument: api.decline,
    };
});

import DocumentAcceptance from "@/app/components/marketing/campaigns/DocumentAcceptance";
import DocumentAcceptanceEntry from "@/app/components/marketing/campaigns/DocumentAcceptanceEntry";
import { documentAcceptanceViewFailure } from "@/app/components/marketing/campaigns/documentAcceptance";
import { ApiError } from "@/app/lib/api";

const TOKEN = `w12-${"a".repeat(64)}`;
const FLOW_ID = "c".repeat(64);
const MESSAGES = {
    ...acceptanceMessages,
    DealsDocuments: dealsMessages.DealsDocuments,
};

function preview(overrides: Partial<DocumentAcceptancePreview> = {}): DocumentAcceptancePreview {
    return {
        flowId: FLOW_ID,
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

let restoreLocation: (() => void) | null = null;

function stubLocation(): { replace: ReturnType<typeof vi.fn>; reload: ReturnType<typeof vi.fn> } {
    const real = window.location;
    const replace = vi.fn();
    const reload = vi.fn();
    Object.defineProperty(window, "location", {
        configurable: true,
        value: {
            get href() { return real.href; },
            get origin() { return real.origin; },
            get pathname() { return real.pathname; },
            get search() { return real.search; },
            get hash() { return real.hash; },
            replace,
            reload,
        },
    });
    restoreLocation = () => Object.defineProperty(window, "location", {
        configurable: true,
        value: real,
    });
    return { replace, reload };
}

async function renderEntry() {
    const container = document.createElement("div");
    document.body.appendChild(container);
    const root = createRoot(container, { onCaughtError: vi.fn() });
    await act(async () => {
        root.render(<DocumentAcceptanceEntry />);
        await Promise.resolve();
        await Promise.resolve();
    });
    return { container, root };
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
    vi.restoreAllMocks();
    window.history.replaceState({}, "", "/document-acceptance");
    api.exchange.mockResolvedValue(undefined);
    api.preview.mockImplementation(async () => preview());
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
    restoreLocation?.();
    restoreLocation = null;
    vi.unstubAllGlobals();
    vi.unstubAllEnvs();
});

describe("document acceptance entry", () => {
    it("strips the fragment before the exchange request", async () => {
        window.history.replaceState({}, "", `/document-acceptance#token=${TOKEN}`);
        const { replace } = stubLocation();

        const rendered = await renderEntry();

        expect(api.exchange).toHaveBeenCalledWith(TOKEN);
        expect(window.location.hash).toBe("");
        expect(window.location.pathname).toBe("/document-acceptance");
        expect(replace).toHaveBeenCalledWith("/document-acceptance");
        expect(api.preview).not.toHaveBeenCalled();

        await unmount(rendered.root);
    });

    it("re-opens a second emailed link that lands in the same tab", async () => {
        const { reload } = stubLocation();
        const rendered = await renderEntry();
        expect(reload).not.toHaveBeenCalled();

        await act(async () => {
            window.history.replaceState({}, "", `/document-acceptance#token=${TOKEN}`);
            window.dispatchEvent(new HashChangeEvent("hashchange"));
            await Promise.resolve();
        });

        expect(reload).toHaveBeenCalledTimes(1);

        await unmount(rendered.root);
    });

    it("loads the preview from the grant without a token", async () => {
        const rendered = await renderEntry();

        expect(api.exchange).not.toHaveBeenCalled();
        expect(api.preview).toHaveBeenCalledWith();
        expect(rendered.container.textContent).toContain("Frozen document title");
        expect(rendered.container.textContent).toContain("Hikari Systems");

        await unmount(rendered.root);
    });

    it("keeps the grant's session alive while the preview stays open, and stops on unmount", async () => {
        vi.useFakeTimers();
        try {
            const rendered = await renderEntry();
            expect(api.preview).toHaveBeenCalledTimes(1);

            await act(async () => {
                await vi.advanceTimersByTimeAsync(10 * 60 * 1000);
            });
            expect(api.preview).toHaveBeenCalledTimes(2);

            await act(async () => {
                await vi.advanceTimersByTimeAsync(10 * 60 * 1000);
            });
            expect(api.preview).toHaveBeenCalledTimes(3);

            await unmount(rendered.root);
            await act(async () => {
                await vi.advanceTimersByTimeAsync(60 * 60 * 1000);
            });
            expect(api.preview).toHaveBeenCalledTimes(3);
        } finally {
            vi.useRealTimers();
        }
    });

    it("surfaces the unavailable state when the keep-alive loses the grant", async () => {
        vi.useFakeTimers();
        try {
            const rendered = await renderEntry();
            expect(rendered.container.textContent).toContain("Frozen document title");
            api.preview.mockRejectedValue(new ApiError("gone", 404));

            await act(async () => {
                await vi.advanceTimersByTimeAsync(10 * 60 * 1000);
            });

            expect(rendered.container.textContent).toContain("Link unavailable");

            await unmount(rendered.root);
        } finally {
            vi.useRealTimers();
        }
    });

    it.each([
        [400, "Link unavailable"],
        [404, "Link unavailable"],
        [429, "Try again shortly"],
        [503, "Document review is unavailable"],
    ])("maps a preview %i into non-diagnostic copy", async (status, title) => {
        api.preview.mockRejectedValue(new ApiError("nope", status));

        const rendered = await renderEntry();

        expect(rendered.container.textContent).toContain(title);
        expect(api.markViewed).not.toHaveBeenCalled();

        await unmount(rendered.root);
    });

    it("renders byte-identical copy for distinct grant failures", async () => {
        const causes = [
            new ApiError("Document link is no longer available", 404),
            new ApiError("This link is invalid or has expired", 400),
            new ApiError("nothing", 404, "RESOURCE_NOT_FOUND"),
        ];
        const rendered: string[] = [];

        for (const cause of causes) {
            api.preview.mockRejectedValue(cause);
            const view = await renderEntry();
            rendered.push(view.container.innerHTML);
            await unmount(view.root);
        }

        expect(new Set(rendered)).toEqual(new Set([rendered[0]]));
        expect(rendered[0]).toContain("Link unavailable");
    });

    it("sets the Japanese document title after a ja preview", async () => {
        api.preview.mockImplementation(async () => preview({ documentLocale: "ja" }));

        const rendered = await renderEntry();

        expect(document.title).toBe(
            `${jaAcceptanceMessages.DocumentAcceptance.metaTitle} | Connex`,
        );
        expect(rendered.container.querySelector("[lang]")?.getAttribute("lang")).toBe("ja");
        expect(rendered.container.textContent)
            .toContain(jaAcceptanceMessages.DocumentAcceptance.pageLabel);

        await unmount(rendered.root);
    });
});

describe("document acceptance", () => {
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
        expect(api.markViewed).toHaveBeenCalledWith();

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

        await api.accept({ flowId: FLOW_ID, typedName: "Rina Sato" });
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

        expect(api.accept).toHaveBeenCalledWith({ flowId: FLOW_ID, typedName: "Rina Sato" });
        expect(rendered.container.textContent).toContain("Document accepted");
        expect(rendered.container.textContent).not.toContain("Confirm acceptance");

        await unmount(rendered.root);
    });

    it("refuses a viewed receipt rendered from another tab's grant", async () => {
        const initial = preview();
        api.markViewed.mockResolvedValueOnce({
            ...initial,
            flowId: "e".repeat(64),
            dealName: "Another deal",
            deliveryStatus: "viewed",
            recipientStatus: "viewed",
        });
        const rendered = await renderAcceptance(initial);

        expect(rendered.container.textContent).toContain("Link unavailable");
        expect(rendered.container.textContent).not.toContain("Another deal");
        expect(rendered.container.textContent).not.toContain("Confirm acceptance");
        expect(api.accept).not.toHaveBeenCalled();

        await unmount(rendered.root);
    });

    it("shows the unavailable state when the grant no longer matches the rendered flow", async () => {
        api.accept.mockRejectedValueOnce(
            new ApiError("Document link is no longer available", 404),
        );
        const rendered = await renderAcceptance();
        await click(button(rendered.container, "Accept"));
        const input = rendered.container.querySelector<HTMLInputElement>("#document-acceptance-name");
        if (!input) throw new Error("Typed-name field not found");
        await enterValue(input, "Rina Sato");
        await click(button(rendered.container, "Confirm acceptance"));

        expect(api.accept).toHaveBeenCalledWith({ flowId: FLOW_ID, typedName: "Rina Sato" });
        expect(rendered.container.textContent).toContain("Link unavailable");
        expect(rendered.container.textContent).not.toContain("Document accepted");

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

        expect(api.decline).toHaveBeenCalledWith({
            flowId: FLOW_ID,
            reason: "Commercial terms do not work",
        });
        expect(rendered.container.textContent).toContain("Document declined");

        await unmount(rendered.root);
    });

    it("names no bearer in any request URL and sends no workspace context", async () => {
        const fetchMock = vi.fn<(
            input: RequestInfo | URL,
            init?: RequestInit,
        ) => Promise<Response>>().mockImplementation(async (input) => {
            const url = String(input);
            if (url.endsWith("/api/auth/csrf")) {
                return jsonResponse({ headerName: "X-CSRF-TOKEN", token: "csrf-token" });
            }
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
        document.cookie = `connex_workspace=12; path=/`;
        const actual = await vi.importActual<typeof import("@/app/lib/api")>("@/app/lib/api");

        await actual.getDocumentAcceptancePreview();
        await actual.markDocumentAcceptanceViewed();
        await actual.acceptDocument({ flowId: FLOW_ID, typedName: "Rina Sato" });
        await actual.declineDocument({ flowId: FLOW_ID, reason: "Commercial terms" });

        const linkCalls = fetchMock.mock.calls
            .filter((call) => !String(call[0]).endsWith("/api/auth/csrf"));
        expect(linkCalls).toHaveLength(4);
        for (const call of linkCalls) {
            const init = call[1];
            if (!init) throw new Error("Link-flow request options were not provided");
            const headers = new Headers(init.headers);
            expect(String(call[0])).not.toContain(TOKEN);
            expect(String(call[0])).not.toMatch(/[a-f0-9]{64}/);
            expect(init.credentials).toBe("include");
            expect(headers.has("X-Workspace-Id")).toBe(false);
            expect(headers.has("Authorization")).toBe(false);
        }
        for (const call of linkCalls.filter((entry) => entry[1]?.method === "POST")) {
            expect(new Headers(call[1]?.headers).get("X-CSRF-TOKEN")).toBe("csrf-token");
        }
    });
});
