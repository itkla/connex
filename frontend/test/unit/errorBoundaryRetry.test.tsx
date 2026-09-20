/** @vitest-environment jsdom */
import { act, type ComponentType } from "react";
import { createRoot, type Root } from "react-dom/client";
import { NextIntlClientProvider } from "next-intl";
import { afterEach, describe, expect, it, vi } from "vitest";

import AppError from "@/app/(app)/error";
import RootError from "@/app/error";
import GlobalError from "@/app/global-error";
import type { SegmentErrorProps } from "@/app/components/ErrorState";
import enErrors from "@/messages/en/errors.json";

declare global {
    var IS_REACT_ACT_ENVIRONMENT: boolean;
}
globalThis.IS_REACT_ACT_ENVIRONMENT = true;

vi.mock("next/navigation", () => ({
    useRouter: () => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn(), back: vi.fn() }),
}));

vi.mock("@/app/lib/clientErrorReporter", () => ({
    reportBoundaryErrorWithConsole: vi.fn(),
}));

afterEach(() => {
    document.body.replaceChildren();
    vi.clearAllMocks();
});

/** Renders a boundary with both recovery callbacks stubbed, and returns the mounted root. */
async function mountBoundary(
    Boundary: ComponentType<SegmentErrorProps>,
    props: SegmentErrorProps,
): Promise<Root> {
    const container = document.createElement("div");
    document.body.appendChild(container);
    const root = createRoot(container, { onCaughtError: vi.fn() });
    await act(async () => {
        root.render(
            <NextIntlClientProvider locale="en" messages={enErrors} timeZone="UTC" onError={() => {}}>
                <Boundary {...props} />
            </NextIntlClientProvider>,
        );
        await Promise.resolve();
    });
    return root;
}

/** Clicks the boundary's recovery control by its rendered label. */
async function clickRecovery(label: string) {
    const button = [...document.body.querySelectorAll("button")]
        .find((candidate) => candidate.textContent?.includes(label));
    expect(button).toBeDefined();
    await act(async () => {
        button?.click();
        await Promise.resolve();
    });
}

/** Boundary props whose recovery callbacks record which one the rendered control invoked. */
function props() {
    return {
        error: Object.assign(new Error("boom"), { digest: "abc123" }),
        reset: vi.fn<() => void>(),
        retry: vi.fn<() => void>(),
    };
}

describe("segment error boundaries", () => {
    it("re-fetches the failed segment from an app-shell boundary", async () => {
        const boundary = props();
        const root = await mountBoundary(AppError, boundary);

        await clickRecovery(enErrors.ErrorState.retry);

        expect(boundary.retry).toHaveBeenCalledTimes(1);
        expect(boundary.reset).not.toHaveBeenCalled();

        await act(async () => root.unmount());
    });

    it("keeps the root boundary off the router-refreshing retry", async () => {
        const boundary = props();
        const root = await mountBoundary(RootError, boundary);

        await clickRecovery(enErrors.ErrorState.retry);

        expect(boundary.reset).toHaveBeenCalledTimes(1);
        expect(boundary.retry).not.toHaveBeenCalled();

        await act(async () => root.unmount());
    });

    it("keeps the global boundary off the router-refreshing retry", async () => {
        const boundary = props();
        const root = await mountBoundary(GlobalError, boundary);

        await clickRecovery("Try again");

        expect(boundary.reset).toHaveBeenCalledTimes(1);
        expect(boundary.retry).not.toHaveBeenCalled();

        await act(async () => root.unmount());
    });
});
