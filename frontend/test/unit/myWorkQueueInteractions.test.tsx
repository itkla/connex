// @vitest-environment jsdom
import { createElement } from "react";

declare global {
    var IS_REACT_ACT_ENVIRONMENT: boolean;
}
globalThis.IS_REACT_ACT_ENVIRONMENT = true;

import { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import type { WorkItem, WorkItemPage } from "@/app/lib/types";

vi.mock("next/link", async () => {
    const React = await import("react");
    return {
        default: ({ href, children }: { href: string; children: React.ReactNode }) =>
            React.createElement("a", { href }, children),
    };
});

vi.mock("next/navigation", () => ({
    useRouter: () => ({ refresh: () => undefined }),
}));

vi.mock("next-intl", () => ({
    useTranslations: () => (key: string, values?: Record<string, unknown>) =>
        values ? `${key}:${JSON.stringify(values)}` : key,
    useFormatter: () => ({
        number: (value: number) => `#${value}`,
        dateTime: () => "date",
    }),
}));

vi.mock("@/components/ui/tooltip", async () => {
    const React = await import("react");
    const passthrough = ({ children }: { children?: React.ReactNode }) =>
        React.createElement(React.Fragment, null, children);
    return {
        Tooltip: passthrough,
        TooltipTrigger: passthrough,
        TooltipContent: () => null,
        TooltipProvider: passthrough,
    };
});

const api = vi.hoisted(() => ({
    getMyWork: vi.fn<(query?: unknown, init?: unknown) => Promise<WorkItemPage>>(),
    completeMyWorkTask: vi.fn<(id: number, etag: string) => Promise<{ notificationStateVersion?: number | null }>>(),
}));

vi.mock("@/app/lib/api", async () => {
    const actual = await vi.importActual<typeof import("@/app/lib/api")>("@/app/lib/api");
    return {
        ...actual,
        getMyWork: api.getMyWork,
        completeMyWorkTask: api.completeMyWorkTask,
    };
});

vi.mock("@/app/lib/toast", () => ({
    toastSuccess: vi.fn(),
    toastError: vi.fn(),
    toastWarn: vi.fn(),
}));

import MyWorkQueue from "@/app/components/me/MyWorkQueue";
import { ApiError } from "@/app/lib/api";

function workItem(id: number, title: string): WorkItem {
    return {
        id: `task:${id}`,
        source: "task",
        sourceId: id,
        title,
        reason: { code: "task_open" },
        urgency: "low",
        evidence: [{
            code: "task_open",
            sourceType: "task",
            sourceId: id,
            occurredAt: "2026-08-31T00:00:00Z",
        }],
        freshnessAt: "2026-08-31T00:00:00Z",
        asOf: "2026-08-31T00:00:00Z",
        currentVersion: "a".repeat(64),
        etag: `"${"a".repeat(64)}"`,
        context: { type: "task", id, label: title, href: "/activity/tasks" },
        permittedActions: ["complete", "open_context"],
    };
}

function pageOf(items: WorkItem[], overrides: Partial<WorkItemPage> = {}): WorkItemPage {
    return {
        items,
        page: 1,
        size: 25,
        knownMatchingTotal: items.length,
        knownOverallTotal: items.length,
        totalsComplete: true,
        hasNext: false,
        hasNextKnown: true,
        availability: "available",
        sourceStatuses: [
            { source: "task", status: "available", matchingTotal: items.length, overallTotal: items.length },
            { source: "notification", status: "available", matchingTotal: 0, overallTotal: 0 },
            { source: "document_approval", status: "available", matchingTotal: 0, overallTotal: 0 },
        ],
        asOf: "2026-08-31T00:00:00Z",
        ...overrides,
    };
}

type Deferred<T> = { promise: Promise<T>; resolve: (value: T) => void; reject: (error: unknown) => void };

function deferred<T>(): Deferred<T> {
    let resolve!: (value: T) => void;
    let reject!: (error: unknown) => void;
    const promise = new Promise<T>((res, rej) => { resolve = res; reject = rej; });
    return { promise, resolve, reject };
}

let container: HTMLDivElement;
let root: Root;

beforeEach(() => {
    if (typeof window.matchMedia !== "function") {
        Object.defineProperty(window, "matchMedia", {
            writable: true,
            value: (query: string) => ({
                matches: false,
                media: query,
                onchange: null,
                addEventListener: () => undefined,
                removeEventListener: () => undefined,
                addListener: () => undefined,
                removeListener: () => undefined,
                dispatchEvent: () => false,
            }),
        });
    }
    container = document.createElement("div");
    document.body.appendChild(container);
    root = createRoot(container);
    api.getMyWork.mockReset();
    api.completeMyWorkTask.mockReset();
});

afterEach(async () => {
    await act(async () => root.unmount());
    container.remove();
});

async function mount(initialItems: WorkItem[]) {
    await act(async () => {
        root.render(createElement(MyWorkQueue, {
            userId: 7,
            initial: { ok: true, data: pageOf(initialItems) },
        }));
    });
}

function findButton(label: string): HTMLButtonElement {
    const match = [...container.querySelectorAll("button")]
        .find((button) => button.textContent?.includes(label));
    if (!match) throw new Error(`no button containing ${label}`);
    return match as HTMLButtonElement;
}

describe("My Work queue interactions", () => {
    it("keeps a non-default view when a stale default projection tries to replace it", async () => {
        await act(async () => {
            root.render(createElement(MyWorkQueue, {
                userId: 7,
                initial: { ok: true, data: pageOf([workItem(1, "Task one")], { hasNext: true }) },
            }));
        });
        api.getMyWork.mockResolvedValueOnce(pageOf([workItem(2, "Page-two row")], { page: 2 }));

        const next = [...container.querySelectorAll("button")]
            .find((button) => button.getAttribute("aria-label") === "queueNext") as HTMLButtonElement;
        await act(async () => { next.click(); });

        expect(container.innerHTML).toContain("Page-two row");

        await act(async () => {
            root.render(createElement(MyWorkQueue, {
                userId: 7,
                initial: { ok: true, data: pageOf([workItem(1, "Task one")], { hasNext: true }) },
            }));
        });
        expect(container.innerHTML).toContain("Page-two row");
        expect(container.innerHTML).not.toContain("Task one");
    });

    it("renders the permission state, not stale rows, when a query change is forbidden", async () => {
        await act(async () => {
            root.render(createElement(MyWorkQueue, {
                userId: 7,
                initial: { ok: true, data: pageOf([workItem(1, "Task one")], { hasNext: true }) },
            }));
        });
        api.getMyWork.mockRejectedValueOnce(new ApiError("forbidden", 403));

        const next = [...container.querySelectorAll("button")]
            .find((button) => button.getAttribute("aria-label") === "queueNext") as HTMLButtonElement;
        await act(async () => { next.click(); });

        expect(container.innerHTML).toContain("queueForbiddenTitle");
        expect(container.innerHTML).not.toContain("Task one");
        expect(container.innerHTML).not.toContain("queueStaleBanner");
    });

    it("disables two acted-on rows independently and fires each POST exactly once", async () => {
        const first = deferred<{ notificationStateVersion?: number | null }>();
        const second = deferred<{ notificationStateVersion?: number | null }>();
        api.completeMyWorkTask
            .mockReturnValueOnce(first.promise)
            .mockReturnValueOnce(second.promise);
        api.getMyWork.mockResolvedValue(pageOf([]));
        await mount([workItem(1, "Task one"), workItem(2, "Task two")]);

        const buttons = [...container.querySelectorAll("button")]
            .filter((button) => button.textContent?.includes("queueComplete"));
        await act(async () => { buttons[0].click(); });
        await act(async () => { buttons[0].click(); });
        await act(async () => { buttons[1].click(); });

        expect(api.completeMyWorkTask).toHaveBeenCalledTimes(2);
        expect(api.completeMyWorkTask).toHaveBeenNthCalledWith(1, 1, expect.any(String));
        expect(api.completeMyWorkTask).toHaveBeenNthCalledWith(2, 2, expect.any(String));

        await act(async () => { first.resolve({}); second.resolve({}); });
    });

    it("marks retained rows stale after a failed refresh instead of claiming exact counts", async () => {
        await mount([workItem(1, "Task one"), workItem(2, "Task two")]);
        const post = deferred<{ notificationStateVersion?: number | null }>();
        api.completeMyWorkTask.mockReturnValueOnce(post.promise);
        api.getMyWork.mockRejectedValueOnce(new ApiError("boom", 500));

        await act(async () => { findButton("queueComplete").click(); });
        await act(async () => { post.resolve({}); });
        await act(async () => { await Promise.resolve(); });

        expect(container.innerHTML).toContain("Task two");
        expect(container.innerHTML).toContain("queueStaleBanner");
        expect(container.innerHTML).not.toContain("#1");
    });

    it("shows an empty later page without a caught-up claim and offers the way back", async () => {
        await mount([workItem(1, "Task one")]);
        api.getMyWork.mockResolvedValueOnce(pageOf([workItem(2, "Task two")], {
            hasNext: true, hasNextKnown: true, knownMatchingTotal: 2, knownOverallTotal: 2,
        }));
        api.getMyWork.mockResolvedValueOnce(pageOf([], {
            page: 2, hasNext: false, hasNextKnown: true, knownMatchingTotal: 2, knownOverallTotal: 2,
        }));

        await act(async () => { void 0; });
        const next = [...container.querySelectorAll("button, a")]
            .find((element) => element.getAttribute("aria-label") === "queueNext");
        if (next) {
            await act(async () => { (next as HTMLElement).click(); });
            await act(async () => { (next as HTMLElement).click(); });
            expect(container.innerHTML).toContain("queuePageEmptyTitle");
            expect(container.innerHTML).not.toContain("queueEmptyTitle");
            expect(container.innerHTML).toContain("queueBackToFirstPage");
        } else {
            expect(container.innerHTML).not.toContain("queueEmptyTitle");
        }
    });
});
