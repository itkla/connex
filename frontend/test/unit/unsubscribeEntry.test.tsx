/** @vitest-environment jsdom */
import { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { NextIntlClientProvider } from "next-intl";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import type { DeliveryUnsubscribeInfo } from "@/app/lib/types";
import enUnsubscribeMessages from "@/messages/en/unsubscribe.json";
import jaUnsubscribeMessages from "@/messages/ja/unsubscribe.json";

declare global {
    var IS_REACT_ACT_ENVIRONMENT: boolean;
}
globalThis.IS_REACT_ACT_ENVIRONMENT = true;

const api = vi.hoisted(() => ({
    exchange: vi.fn<(token: string) => Promise<void>>(),
    info: vi.fn<() => Promise<DeliveryUnsubscribeInfo>>(),
}));

vi.mock("@/app/lib/api", async () => {
    const actual = await vi.importActual<typeof import("@/app/lib/api")>("@/app/lib/api");
    return {
        ...actual,
        exchangeUnsubscribeToken: api.exchange,
        getUnsubscribeInfo: api.info,
    };
});

import UnsubscribeEntry from "@/app/components/marketing/campaigns/UnsubscribeEntry";
import { ApiError } from "@/app/lib/api";

const FLOW_ID = "c".repeat(64);

function info(): DeliveryUnsubscribeInfo {
    return {
        flowId: FLOW_ID,
        channel: "email",
        address: "r***@dest.test",
        unsubscribed: false,
    };
}

async function renderEntry(messages: Record<string, unknown> = enUnsubscribeMessages) {
    const container = document.createElement("div");
    document.body.appendChild(container);
    const root = createRoot(container, { onCaughtError: vi.fn() });
    await act(async () => {
        root.render(
            <NextIntlClientProvider locale="en" messages={messages}>
                <UnsubscribeEntry />
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

beforeEach(() => {
    vi.clearAllMocks();
    window.history.replaceState({}, "", "/unsubscribe");
    api.exchange.mockResolvedValue(undefined);
    api.info.mockImplementation(async () => info());
});

afterEach(() => {
    document.body.replaceChildren();
});

describe("unsubscribe entry failure states", () => {
    it("renders the confirmation from the grant", async () => {
        const rendered = await renderEntry();

        expect(rendered.container.textContent).toContain("r***@dest.test");

        await unmount(rendered.root);
    });

    it("renders a retryable throttled state for a shared-budget 429", async () => {
        api.info.mockRejectedValue(new ApiError("slow down", 429));

        const rendered = await renderEntry();

        expect(rendered.container.textContent)
            .toContain(enUnsubscribeMessages.Unsubscribe.throttledTitle);
        expect(rendered.container.textContent)
            .toContain(enUnsubscribeMessages.Unsubscribe.throttledBody);
        expect(rendered.container.textContent)
            .not.toContain(enUnsubscribeMessages.Unsubscribe.invalidTitle);

        await unmount(rendered.root);
    });

    it.each([404, 400, 503])("renders the invalid state for a %i", async (status) => {
        api.info.mockRejectedValue(new ApiError("gone", status));

        const rendered = await renderEntry();

        expect(rendered.container.textContent)
            .toContain(enUnsubscribeMessages.Unsubscribe.invalidTitle);
        expect(rendered.container.textContent)
            .not.toContain(enUnsubscribeMessages.Unsubscribe.throttledTitle);

        await unmount(rendered.root);
    });

    it("classifies a throttled exchange the same way", async () => {
        window.history.replaceState({}, "", `/unsubscribe#token=${"a".repeat(64)}`);
        api.exchange.mockRejectedValue(new ApiError("slow down", 429));

        const rendered = await renderEntry();

        expect(api.exchange).toHaveBeenCalled();
        expect(rendered.container.textContent)
            .toContain(enUnsubscribeMessages.Unsubscribe.throttledTitle);

        await unmount(rendered.root);
    });

    it("has Japanese copy for the throttled state", async () => {
        api.info.mockRejectedValue(new ApiError("slow down", 429));

        const rendered = await renderEntry(jaUnsubscribeMessages);

        expect(rendered.container.textContent)
            .toContain(jaUnsubscribeMessages.Unsubscribe.throttledTitle);

        await unmount(rendered.root);
    });
});
