/** @vitest-environment jsdom */
import { act, StrictMode } from "react";
import { createRoot, type Root } from "react-dom/client";
import { NextIntlClientProvider } from "next-intl";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import en from "@/messages/en/auth.json";
import ja from "@/messages/ja/auth.json";

const navigation = vi.hoisted(() => ({ replace: vi.fn(), refresh: vi.fn() }));
const showApiError = vi.hoisted(() => vi.fn());
vi.mock("next/navigation", () => ({ useRouter: () => navigation }));
vi.mock("@/app/hooks/useApiErrorToast", () => ({ useApiErrorToast: () => showApiError }));
vi.mock("@/app/lib/toast", () => ({ toastSuccess: vi.fn() }));

import LogoutPage from "@/app/auth/logout/page";

const DRAFT_KEY = "connex:draft:7:11:note:new";
let container: HTMLDivElement;
let root: Root;
const fetcher = vi.fn<typeof fetch>();

beforeEach(() => {
    vi.clearAllMocks();
    vi.stubGlobal("IS_REACT_ACT_ENVIRONMENT", true);
    vi.stubGlobal("fetch", fetcher);
    fetcher.mockImplementation(async () => Response.json({ token: "csrf-test", headerName: "X-CSRF-TOKEN" }));
    window.sessionStorage.setItem(DRAFT_KEY, "Recoverable draft");
    window.history.replaceState({}, "", "/auth/logout");
    container = document.createElement("div");
    document.body.append(container);
    root = createRoot(container);
});

afterEach(async () => {
    await act(async () => root.unmount());
    container.remove();
    window.sessionStorage.clear();
    window.localStorage.clear();
    vi.unstubAllGlobals();
});

function requiredElement<T extends Element>(selector: string, type: { new(...args: never[]): T }): T {
    const element = container.querySelector(selector);
    if (!(element instanceof type)) throw new Error(`Missing ${selector}`);
    return element;
}

describe.each(["en", "ja"] as const)("logout confirmation in %s", (locale) => {
    async function renderPage() {
        const messages = locale === "en" ? en : ja;
        await act(async () => root.render(
            <StrictMode><NextIntlClientProvider locale={locale} messages={messages} timeZone="UTC">
                <LogoutPage />
            </NextIntlClientProvider></StrictMode>,
        ));
        return messages.AuthLogout;
    }

    it("keeps authentication and drafts intact on navigation until explicit submission", async () => {
        const messages = await renderPage();
        expect(fetcher).not.toHaveBeenCalled();
        expect(window.sessionStorage.getItem(DRAFT_KEY)).toBe("Recoverable draft");
        expect(container.querySelector("h1")?.textContent).toBe(messages.title);
        expect(navigation.replace).not.toHaveBeenCalled();
        const cancel = requiredElement('button[type="button"]', HTMLButtonElement);
        expect(cancel.textContent).toBe(messages.cancel);
        expect(document.activeElement).toBe(cancel);
        const confirm = requiredElement('button[type="submit"]', HTMLButtonElement);
        expect(confirm.textContent).toBe(messages.confirm);
        await act(async () => confirm.click());
        expect(fetcher.mock.calls.filter(([url, init]) => url === "/api/auth/logout" && init?.method === "POST")).toHaveLength(1);
        expect(window.sessionStorage.getItem(DRAFT_KEY)).toBeNull();
        expect(navigation.replace).toHaveBeenCalledWith("/");
        expect(requiredElement("form", HTMLFormElement).getAttribute("aria-busy")).toBe("false");
        expect(confirm.disabled).toBe(false);
        expect(cancel.disabled).toBe(false);
    });

    it("allows cancellation without a logout request or draft loss", async () => {
        const messages = await renderPage();
        const cancel = requiredElement('button[type="button"]', HTMLButtonElement);
        expect(cancel.textContent).toBe(messages.cancel);
        await act(async () => cancel.click());
        expect(navigation.replace).toHaveBeenCalledWith("/dashboard");
        expect(fetcher).not.toHaveBeenCalled();
        expect(window.sessionStorage.getItem(DRAFT_KEY)).toBe("Recoverable draft");
    });

    it("submits once while pending and allows an explicit retry after failure", async () => {
        await renderPage();
        let fail: (reason: Error) => void = () => undefined;
        fetcher.mockImplementation(async (url) => String(url).endsWith("/csrf")
            ? Response.json({ token: "csrf-test", headerName: "X-CSRF-TOKEN" })
            : new Promise<Response>((_resolve, reject) => { fail = reject; }));
        const form = requiredElement("form", HTMLFormElement);
        await act(async () => {
            form.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
            form.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
        });
        expect(fetcher.mock.calls.filter(([url]) => url === "/api/auth/logout")).toHaveLength(1);
        expect(form.getAttribute("aria-busy")).toBe("true");
        expect(requiredElement('button[type="submit"]', HTMLButtonElement).disabled).toBe(true);
        await act(async () => fail(new TypeError("Network unavailable")));
        expect(showApiError).toHaveBeenCalledOnce();
        expect(navigation.replace).not.toHaveBeenCalled();
        expect(form.getAttribute("aria-busy")).toBe("false");
        expect(requiredElement('button[type="submit"]', HTMLButtonElement).disabled).toBe(false);
        expect(requiredElement('button[type="button"]', HTMLButtonElement).disabled).toBe(false);
        fetcher.mockResolvedValue(new Response(null, { status: 204 }));
        await act(async () => form.requestSubmit());
        expect(fetcher.mock.calls.filter(([url]) => url === "/api/auth/logout")).toHaveLength(2);
        expect(navigation.replace).toHaveBeenCalledWith("/");
    });
});
