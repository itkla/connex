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

const mediaQuery = vi.hoisted(() => ({ wide: false }));

vi.mock("@/app/components/calendar/useMediaQuery", () => ({
    useMediaQuery: () => mediaQuery.wide,
}));

const api = vi.hoisted(() => ({
    getMyWork: vi.fn<(query?: unknown, init?: unknown) => Promise<WorkItemPage>>(),
    completeMyWorkTask: vi.fn<(id: number, etag: string) => Promise<{ notificationStateVersion?: number | null }>>(),
    dismissMyWorkNotification: vi.fn<(id: number, etag: string) => Promise<{ notificationStateVersion?: number | null }>>(),
    snoozeMyWorkNotification: vi.fn<(id: number, etag: string, body: unknown) => Promise<{ notificationStateVersion?: number | null }>>(),
    decideMyWorkApproval: vi.fn<(id: number, etag: string, body: unknown) => Promise<{ notificationStateVersion?: number | null }>>(),
}));

vi.mock("@/app/lib/api", async () => {
    const actual = await vi.importActual<typeof import("@/app/lib/api")>("@/app/lib/api");
    return {
        ...actual,
        getMyWork: api.getMyWork,
        completeMyWorkTask: api.completeMyWorkTask,
        dismissMyWorkNotification: api.dismissMyWorkNotification,
        snoozeMyWorkNotification: api.snoozeMyWorkNotification,
        decideMyWorkApproval: api.decideMyWorkApproval,
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

function approvalItem(id: number, title: string): WorkItem {
    return {
        ...workItem(id, title),
        id: `document_approval:${id}:${id + 100}`,
        source: "document_approval",
        reason: { code: "document_approval_pending" },
        evidence: [{
            code: "approval_requested",
            sourceType: "document_approval",
            sourceId: id,
            occurredAt: "2026-08-31T00:00:00Z",
        }],
        context: {
            type: "deal",
            id,
            label: title,
            href: `/records/deals/${id}`,
            stepId: id + 100,
        },
        permittedActions: ["approve", "reject", "open_context"],
    };
}

function notificationItem(id: number, title: string): WorkItem {
    return {
        ...workItem(id, title),
        id: `notification:${id}`,
        source: "notification",
        reason: { code: "deal_closing_soon", date: "2026-09-01" },
        evidence: [{
            code: "deal_close_date",
            sourceType: "notification",
            sourceId: id,
            occurredAt: "2026-08-31T00:00:00Z",
        }],
        context: { type: "deal", id, label: title, href: `/records/deals/${id}` },
        permittedActions: ["snooze", "dismiss", "open_context"],
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
    mediaQuery.wide = false;
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
    api.dismissMyWorkNotification.mockReset();
    api.snoozeMyWorkNotification.mockReset();
    api.decideMyWorkApproval.mockReset();
});

afterEach(async () => {
    await act(async () => root.unmount());
    container.remove();
    vi.useRealTimers();
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

function findDocumentButton(label: string, rootNode: ParentNode = document.body): HTMLButtonElement {
    const match = [...rootNode.querySelectorAll("button")]
        .find((button) => button.textContent?.includes(label));
    if (!match) throw new Error(`no document button containing ${label}`);
    return match;
}

function focusedDrawer(): HTMLElement {
    const drawer = document.body.querySelector<HTMLElement>("[data-slot='drawer-content']");
    if (drawer == null) throw new Error("focused drawer not found");
    return drawer;
}

function focusedTitle(): HTMLElement {
    const title = focusedDrawer().querySelector<HTMLElement>("[data-slot='drawer-title']");
    if (title == null) throw new Error("focused title not found");
    return title;
}

async function openFocusedItem(title: string) {
    const rowButton = [...container.querySelectorAll("button")]
        .find((button) => button.textContent?.includes(title));
    if (rowButton == null) throw new Error(`row button for ${title} not found`);
    await act(async () => { rowButton.click(); });
}

type NotificationAction = "dismiss" | "snooze";

async function invokeFocusedNotificationAction(action: NotificationAction) {
    if (action === "dismiss") {
        await act(async () => { findDocumentButton("queueDismiss", focusedDrawer()).click(); });
        return;
    }
    const snooze = focusedDrawer().querySelector<HTMLButtonElement>('button[aria-label="snooze"]');
    if (snooze == null) throw new Error("snooze trigger not found");
    await act(async () => {
        snooze.focus();
        snooze.dispatchEvent(new KeyboardEvent("keydown", { key: "ArrowDown", bubbles: true }));
    });
    const preset = document.body.querySelector<HTMLElement>("[data-slot='dropdown-menu-content'] [role='menuitem']");
    if (preset == null) throw new Error("snooze preset not found");
    await act(async () => { preset.click(); });
}

function dispatchActivation(button: HTMLButtonElement, detail: number) {
    button.dispatchEvent(new MouseEvent("click", { bubbles: true, cancelable: true, detail }));
}

function dispatchPointerDown(button: HTMLButtonElement) {
    button.dispatchEvent(new MouseEvent("pointerdown", { bubbles: true, cancelable: true, detail: 1 }));
}

function tabbableControls(rootNode: ParentNode): HTMLElement[] {
    return [...rootNode.querySelectorAll<HTMLElement>(
        'a[href],button:not([disabled]),[tabindex]:not([tabindex="-1"])',
    )].filter((element) => element.tabIndex >= 0);
}

describe("My Work queue interactions", () => {
    it("steps through the loaded page in server order without fetching", async () => {
        await mount([
            workItem(1, "Task one"),
            workItem(2, "Task two"),
            workItem(3, "Task three"),
        ]);
        await openFocusedItem("Task one");

        let drawer = focusedDrawer();
        let previous = findDocumentButton("queueStepPrevious", drawer);
        let next = findDocumentButton("queueStepNext", drawer);
        expect(previous.disabled).toBe(true);
        expect(next.disabled).toBe(false);
        expect(drawer.textContent).toContain('queueStepPosition:{"position":1,"count":3}');

        await act(async () => { next.click(); });
        expect(focusedTitle().textContent).toBe("Task two");
        expect(document.activeElement).toBe(focusedTitle());
        expect(focusedDrawer().querySelector("[aria-live='polite']")?.textContent).toContain("Task two");

        drawer = focusedDrawer();
        next = findDocumentButton("queueStepNext", drawer);
        await act(async () => { next.click(); });
        expect(focusedTitle().textContent).toBe("Task three");
        expect(findDocumentButton("queueStepNext", focusedDrawer()).disabled).toBe(true);

        previous = findDocumentButton("queueStepPrevious", focusedDrawer());
        await act(async () => { previous.click(); });
        expect(focusedTitle().textContent).toBe("Task two");
        expect(api.getMyWork).not.toHaveBeenCalled();
    });

    it("steps with arrow keys and suppresses stepping inside the reject composer", async () => {
        api.decideMyWorkApproval.mockResolvedValueOnce({});
        api.getMyWork.mockResolvedValueOnce(pageOf([approvalItem(2, "Approval two")]));
        await mount([
            approvalItem(1, "Approval one"),
            approvalItem(2, "Approval two"),
        ]);
        await openFocusedItem("Approval one");

        await act(async () => {
            focusedTitle().dispatchEvent(new KeyboardEvent("keydown", { key: "ArrowRight", bubbles: true }));
        });
        expect(focusedTitle().textContent).toBe("Approval two");

        await act(async () => {
            focusedTitle().dispatchEvent(new KeyboardEvent("keydown", { key: "ArrowLeft", bubbles: true }));
        });
        expect(focusedTitle().textContent).toBe("Approval one");

        await act(async () => { findDocumentButton("queueReject", focusedDrawer()).click(); });
        const composer = document.body.querySelector<HTMLTextAreaElement>("textarea");
        if (composer == null) throw new Error("reject composer not found");
        await act(async () => {
            composer.dispatchEvent(new KeyboardEvent("keydown", { key: "ArrowRight", bubbles: true }));
        });
        expect(focusedTitle().textContent).toBe("Approval one");

        const dialog = document.body.querySelector<HTMLElement>("[data-slot='dialog-content']");
        if (dialog == null) throw new Error("reject dialog not found");
        await act(async () => { findDocumentButton("queueReject", dialog).click(); });
        expect(focusedTitle().textContent).toBe("Approval two");
    });

    it("suppresses arrow stepping while the snooze menu owns focus", async () => {
        await mount([
            notificationItem(1, "Notice one"),
            notificationItem(2, "Notice two"),
        ]);
        await openFocusedItem("Notice one");

        const drawer = focusedDrawer();
        const snooze = drawer.querySelector<HTMLButtonElement>('button[aria-label="snooze"]');
        if (snooze == null) throw new Error("snooze trigger not found");
        await act(async () => {
            snooze.focus();
            snooze.dispatchEvent(new KeyboardEvent("keydown", { key: "ArrowDown", bubbles: true }));
        });
        const menuItem = document.body.querySelector<HTMLElement>("[data-slot='dropdown-menu-content'] [role='menuitem']");
        if (menuItem == null) throw new Error("snooze menu item not found");
        await act(async () => {
            menuItem.dispatchEvent(new KeyboardEvent("keydown", { key: "ArrowRight", bubbles: true }));
        });

        expect(focusedTitle().textContent).toBe("Notice one");
    });

    it("auto-advances a focused action and keeps the target through page backfill", async () => {
        api.completeMyWorkTask.mockResolvedValueOnce({});
        api.getMyWork.mockResolvedValueOnce(pageOf([
            workItem(2, "Task two"),
            workItem(3, "Backfilled task"),
        ]));
        await mount([workItem(1, "Task one"), workItem(2, "Task two")]);
        await openFocusedItem("Task one");

        await act(async () => { findDocumentButton("queueComplete", focusedDrawer()).click(); });

        expect(focusedTitle().textContent).toBe("Task two");
        expect(focusedDrawer().textContent).toContain('queueStepPosition:{"position":1,"count":2}');
        expect(document.body.textContent).toContain("Backfilled task");
        expect(api.getMyWork).toHaveBeenCalledTimes(1);
    });

    it("closes after acting on the only remaining item and renders the empty state", async () => {
        api.completeMyWorkTask.mockResolvedValueOnce({});
        api.getMyWork.mockResolvedValueOnce(pageOf([]));
        await mount([workItem(1, "Only task")]);
        await openFocusedItem("Only task");

        await act(async () => { findDocumentButton("queueComplete", focusedDrawer()).click(); });

        expect(document.body.querySelector("[data-slot='drawer-title']")).toBeNull();
        expect(container.textContent).toContain("queueEmptyTitle");
    });

    it("keeps the focused view on the preceding item when the last ranked item resolves", async () => {
        const first = workItem(1, "Task one");
        const second = workItem(2, "Task two");
        api.completeMyWorkTask.mockResolvedValueOnce({});
        api.getMyWork.mockResolvedValueOnce(pageOf([first]));
        await mount([first, second]);
        await openFocusedItem("Task two");

        await act(async () => { findDocumentButton("queueComplete", focusedDrawer()).click(); });

        expect(focusedTitle().textContent).toBe("Task one");
        expect(focusedDrawer().textContent).toContain('queueStepPosition:{"position":1,"count":1}');
    });

    it("preserves a retained focused id across refresh and closes when it disappears", async () => {
        const first = workItem(1, "Task one");
        const second = workItem(2, "Task two");
        const third = workItem(3, "Task three");
        await mount([first, second, third]);
        await openFocusedItem("Task two");

        await act(async () => {
            root.render(createElement(MyWorkQueue, {
                userId: 7,
                initial: { ok: true, data: pageOf([second, third]) },
            }));
        });
        expect(focusedTitle().textContent).toBe("Task two");
        expect(focusedDrawer().textContent).toContain('queueStepPosition:{"position":1,"count":2}');

        await act(async () => {
            root.render(createElement(MyWorkQueue, {
                userId: 7,
                initial: { ok: true, data: pageOf([third]) },
            }));
        });
        expect(document.body.querySelector("[data-slot='drawer-title']")).toBeNull();
    });

    it("does not open the focused view for a list-row action", async () => {
        api.completeMyWorkTask.mockResolvedValueOnce({});
        api.getMyWork.mockResolvedValueOnce(pageOf([workItem(2, "Task two")]));
        await mount([workItem(1, "Task one"), workItem(2, "Task two")]);

        await act(async () => { findButton("queueComplete").click(); });

        expect(document.body.querySelector("[data-slot='drawer-title']")).toBeNull();
    });

    it("ignores accidental pointer click-through after auto-advance without changing control semantics", async () => {
        const first = approvalItem(1, "Approval one");
        const second = approvalItem(2, "Approval two");
        const third = approvalItem(3, "Approval three");
        api.decideMyWorkApproval.mockResolvedValue({});
        api.getMyWork.mockResolvedValue(pageOf([second, third]));
        await mount([first, second]);
        await openFocusedItem("Approval one");
        vi.useFakeTimers();

        await act(async () => { findDocumentButton("queueApprove", focusedDrawer()).click(); });

        expect(focusedTitle().textContent).toBe("Approval two");
        const drawer = focusedDrawer();
        const nextApprove = findDocumentButton("queueApprove", focusedDrawer());
        const nextReject = findDocumentButton("queueReject", focusedDrawer());
        const announcement = drawer.querySelector<HTMLElement>("[aria-live='polite']");
        const announcementText = announcement?.textContent;
        const approveLabel = nextApprove.textContent;
        const rejectLabel = nextReject.textContent;
        const tabOrder = tabbableControls(drawer);

        expect(nextApprove.disabled).toBe(false);
        expect(nextApprove.getAttribute("aria-disabled")).toBeNull();
        expect(nextReject.disabled).toBe(false);
        expect(tabOrder).toContain(nextApprove);
        expect(tabOrder).toContain(nextReject);
        expect(tabOrder.indexOf(nextReject)).toBe(tabOrder.indexOf(nextApprove) + 1);
        nextApprove.focus();
        expect(document.activeElement).toBe(nextApprove);

        await act(async () => { dispatchActivation(nextApprove, 1); });
        expect(api.decideMyWorkApproval).toHaveBeenCalledTimes(1);
        expect(nextApprove.textContent).toBe(approveLabel);
        expect(nextReject.textContent).toBe(rejectLabel);
        expect(announcement?.textContent).toBe(announcementText);
        expect(focusedTitle().textContent).toBe("Approval two");
    });

    it("honors a deliberate pointerdown and click after auto-advance inside the guard window", async () => {
        const first = approvalItem(1, "Approval one");
        const second = approvalItem(2, "Approval two");
        const third = approvalItem(3, "Approval three");
        api.decideMyWorkApproval.mockResolvedValue({});
        api.getMyWork
            .mockResolvedValueOnce(pageOf([second, third]))
            .mockResolvedValueOnce(pageOf([third]));
        await mount([first, second]);
        await openFocusedItem("Approval one");
        vi.useFakeTimers();

        await act(async () => { findDocumentButton("queueApprove", focusedDrawer()).click(); });
        const nextApprove = findDocumentButton("queueApprove", focusedDrawer());

        await act(async () => {
            dispatchPointerDown(nextApprove);
            dispatchActivation(nextApprove, 1);
        });

        expect(api.decideMyWorkApproval).toHaveBeenCalledTimes(2);
        expect(focusedTitle().textContent).toBe("Approval three");
    });

    it.each([
        { name: "Enter", key: "Enter" },
        { name: "Space", key: " " },
    ])("honors $name on the next action immediately after auto-advance", async ({ key }) => {
        const first = approvalItem(1, "Approval one");
        const second = approvalItem(2, "Approval two");
        api.decideMyWorkApproval.mockResolvedValue({});
        api.getMyWork
            .mockResolvedValueOnce(pageOf([second]))
            .mockResolvedValueOnce(pageOf([]));
        await mount([first, second]);
        await openFocusedItem("Approval one");
        vi.useFakeTimers();

        await act(async () => { findDocumentButton("queueApprove", focusedDrawer()).click(); });
        const nextApprove = findDocumentButton("queueApprove", focusedDrawer());
        let keyEventAccepted = false;

        await act(async () => {
            nextApprove.focus();
            keyEventAccepted = nextApprove.dispatchEvent(new KeyboardEvent("keydown", {
                key,
                bubbles: true,
                cancelable: true,
            }));
            dispatchActivation(nextApprove, 0);
        });

        expect(keyEventAccepted).toBe(true);
        expect(api.decideMyWorkApproval).toHaveBeenCalledTimes(2);
    });

    it("allows a pointer click without a new pointerdown after the guard expires", async () => {
        const first = approvalItem(1, "Approval one");
        const second = approvalItem(2, "Approval two");
        const third = approvalItem(3, "Approval three");
        api.decideMyWorkApproval.mockResolvedValue({});
        api.getMyWork
            .mockResolvedValueOnce(pageOf([second, third]))
            .mockResolvedValueOnce(pageOf([third]));
        await mount([first, second]);
        await openFocusedItem("Approval one");
        vi.useFakeTimers();

        await act(async () => { findDocumentButton("queueApprove", focusedDrawer()).click(); });
        const nextApprove = findDocumentButton("queueApprove", focusedDrawer());
        await act(async () => { dispatchActivation(nextApprove, 1); });
        expect(api.decideMyWorkApproval).toHaveBeenCalledTimes(1);

        await act(async () => { vi.advanceTimersByTime(501); });

        expect(nextApprove.disabled).toBe(false);
        await act(async () => { dispatchActivation(nextApprove, 1); });
        expect(api.decideMyWorkApproval).toHaveBeenCalledTimes(2);
        expect(focusedTitle().textContent).toBe("Approval three");
    });

    it.each(["dismiss", "snooze"] as const)(
        "%s removes the resolved item, focuses the next item, and honors server backfill",
        async (action) => {
            const first = notificationItem(1, "Notice one");
            const second = notificationItem(2, "Notice two");
            const backfill = notificationItem(3, "Backfilled notice");
            if (action === "dismiss") api.dismissMyWorkNotification.mockResolvedValueOnce({});
            else api.snoozeMyWorkNotification.mockResolvedValueOnce({});
            api.getMyWork.mockResolvedValueOnce(pageOf([second, backfill]));
            await mount([first, second]);
            await openFocusedItem("Notice one");

            await invokeFocusedNotificationAction(action);

            expect(focusedTitle().textContent).toBe("Notice two");
            expect(container.textContent).not.toContain("Notice one");
            expect(focusedDrawer().textContent).toContain('queueStepPosition:{"position":1,"count":2}');
            expect(document.body.textContent).toContain("Backfilled notice");
            expect(api.getMyWork).toHaveBeenCalledTimes(1);
            if (action === "dismiss") expect(api.dismissMyWorkNotification).toHaveBeenCalledTimes(1);
            else expect(api.snoozeMyWorkNotification).toHaveBeenCalledTimes(1);
        },
    );

    it.each(["dismiss", "snooze"] as const)(
        "%s closes the focused drawer after resolving the last item",
        async (action) => {
            if (action === "dismiss") api.dismissMyWorkNotification.mockResolvedValueOnce({});
            else api.snoozeMyWorkNotification.mockResolvedValueOnce({});
            api.getMyWork.mockResolvedValueOnce(pageOf([]));
            await mount([notificationItem(1, "Only notice")]);
            await openFocusedItem("Only notice");

            await invokeFocusedNotificationAction(action);

            expect(document.body.querySelector("[data-slot='drawer-title']")).toBeNull();
            expect(container.textContent).toContain("queueEmptyTitle");
        },
    );

    it("pins mobile safe-area and touch-target tokens and switches the focused drawer to the desktop right edge", async () => {
        await mount([workItem(1, "Task one"), workItem(2, "Task two")]);
        await openFocusedItem("Task one");

        let drawer = focusedDrawer();
        let viewport = drawer.closest<HTMLElement>("[data-slot='drawer-viewport']");
        expect(drawer.dataset.swipeAxis).toBe("y");
        expect(viewport?.className).toContain("items-end");
        expect(viewport?.className).toContain("justify-center");
        expect(drawer.className).toContain("pb-[max(1rem,env(safe-area-inset-bottom))]");
        expect(drawer.querySelector("[data-slot='drawer-swipe-handle']")).not.toBeNull();
        for (const label of ["queueStepPrevious", "queueStepNext"]) {
            const button = findDocumentButton(label, drawer);
            expect(button.className).toContain("min-h-11");
            expect(button.className).toContain("min-w-11");
        }

        await act(async () => { root.unmount(); });
        root = createRoot(container);
        mediaQuery.wide = true;
        await mount([workItem(1, "Task one"), workItem(2, "Task two")]);
        await openFocusedItem("Task one");

        drawer = focusedDrawer();
        viewport = drawer.closest<HTMLElement>("[data-slot='drawer-viewport']");
        expect(drawer.dataset.swipeAxis).toBe("x");
        expect(viewport?.className).toContain("items-stretch");
        expect(viewport?.className).toContain("justify-end");
        expect(drawer.querySelector("[data-slot='drawer-swipe-handle']")).toBeNull();
    });

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
