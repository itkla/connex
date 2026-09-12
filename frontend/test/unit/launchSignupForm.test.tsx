// @vitest-environment jsdom

import { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { NextIntlClientProvider } from "next-intl";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { LaunchSignupForm } from "@/app/components/landing/LaunchSignupForm";
import { subscribeToLaunch } from "@/app/lib/api";
import en from "@/messages/en/common.json";
import ja from "@/messages/ja/common.json";

(globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
let container: HTMLDivElement;
let root: Root;
const fetchMock = vi.fn<typeof fetch>();

beforeEach(() => {
    fetchMock.mockReset();
    vi.stubGlobal("fetch", fetchMock);
    container = document.createElement("div");
    document.body.append(container);
    root = createRoot(container);
});

afterEach(async () => {
    await act(async () => root.unmount());
    container.remove();
    vi.unstubAllGlobals();
});

describe.each(["en", "ja"] as const)("launch signup in %s", (locale) => {
    it("prevents duplicate submissions, preserves the address on failure, and confirms a successful retry", async () => {
        const messages = locale === "en" ? en : ja;
        let complete: (response: Response) => void = () => undefined;
        fetchMock.mockImplementationOnce(() => new Promise((resolve) => { complete = resolve; }));
        await act(async () => root.render(<NextIntlClientProvider locale={locale} messages={messages} timeZone="UTC"><LaunchSignupForm id="signup" /></NextIntlClientProvider>));
        const form = container.querySelector("form")!;
        const input = container.querySelector<HTMLInputElement>('input[type="email"]')!;
        const submit = container.querySelector<HTMLButtonElement>('button[type="submit"]')!;
        input.value = "visitor@example.com";
        await act(async () => {
            form.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
            form.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
        });
        expect(fetchMock).toHaveBeenCalledTimes(1);
        expect(form.getAttribute("aria-busy")).toBe("true");
        expect(submit.closest("fieldset")?.disabled).toBe(true);
        expect(submit.textContent).toContain(messages.CommonHome.prelaunch.submitting);
        const [url, init] = fetchMock.mock.calls[0];
        expect(url).toBe("/api/launch-signups");
        expect(init?.credentials).toBe("omit");
        expect(new Headers(init?.headers).has("X-Workspace-ID")).toBe(false);
        expect(JSON.parse(init?.body as string)).toEqual({ email: "visitor@example.com", website: "" });
        await act(async () => complete(Response.json({ error: "unavailable" }, { status: 503 })));
        expect(input.value).toBe("visitor@example.com");
        expect(container.querySelector('[role="alert"]')?.textContent).toBe(messages.CommonHome.prelaunch.error);
        expect(submit.closest("fieldset")?.disabled).toBe(false);
        fetchMock.mockResolvedValueOnce(Response.json({ status: "subscribed" }));
        await act(async () => form.requestSubmit());
        expect(fetchMock).toHaveBeenCalledTimes(2);
        expect(container.querySelector("form")).toBeNull();
        expect(container.querySelector('[role="status"]')?.textContent).toContain(messages.CommonHome.prelaunch.successTitle);
    });

    it("keeps an invalid address editable and reports server validation inline", async () => {
        const messages = locale === "en" ? en : ja;
        fetchMock.mockResolvedValue(Response.json({ error: "invalid_email" }, { status: 400 }));
        await act(async () => root.render(<NextIntlClientProvider locale={locale} messages={messages} timeZone="UTC"><LaunchSignupForm id="signup" /></NextIntlClientProvider>));
        const form = container.querySelector("form")!;
        const input = container.querySelector<HTMLInputElement>('input[type="email"]')!;
        input.value = "invalid";
        await act(async () => form.requestSubmit());
        expect(fetchMock).not.toHaveBeenCalled();
        input.value = "visitor@example.com";
        await act(async () => form.requestSubmit());
        expect(input.getAttribute("aria-invalid")).toBe("true");
        expect(input.value).toBe("visitor@example.com");
        expect(container.querySelector('[role="alert"]')?.textContent).toBe(messages.CommonHome.prelaunch.invalid);
    });
});

it.each([
    [200, { error: "unavailable" }, "error"],
    [503, { status: "subscribed" }, "error"],
    [200, {}, "error"],
    [429, { error: "rate_limited" }, "rateLimited"],
] as const)("requires confirmed provider acceptance for HTTP %s", async (status, body, expected) => {
    fetchMock.mockResolvedValueOnce(Response.json(body, { status }));
    expect(await subscribeToLaunch("visitor@example.com")).toBe(expected);
});
