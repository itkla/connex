/** @vitest-environment jsdom */
import { act, type ComponentProps, type ReactNode } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import CalendarShell from "@/app/components/calendar/CalendarShell";
import type MonthView from "@/app/components/calendar/MonthView";
import UpcomingTasks from "@/app/components/dashboard/UpcomingTasks";
import { bindRenderedWorkspace, releaseRenderedWorkspace } from "@/app/lib/renderedWorkspace";
import { toastSuccess } from "@/app/lib/toast";
import type { Task } from "@/app/lib/types";
import { deal, ORIGIN, shell, stubBackend, switchWorkspace } from "./helpers/workspaceChainBackend";

const boundary = vi.hoisted<{ month: ComponentProps<typeof MonthView> | null }>(() => ({ month: null }));
const translate = vi.hoisted(() => Object.assign((key: string) => key, { has: () => false }));
vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh: vi.fn(), push: vi.fn() }) }));
vi.mock("next-intl", () => ({ useLocale: () => "en", useTranslations: () => translate }));
vi.mock("motion/react", () => ({
    useReducedMotion: () => true,
    AnimatePresence: ({ children }: { children?: ReactNode }) => children,
    motion: { div: ({ children }: { children?: ReactNode }) => <div>{children}</div>, span: ({ children }: { children?: ReactNode }) => <span>{children}</span> },
}));
vi.mock("@/app/lib/toast", () => ({ toastSuccess: vi.fn(), toastError: vi.fn() }));
vi.mock("@/app/hooks/useNow", () => ({ useNow: () => Date.parse("2026-10-19T12:00:00Z") }));
vi.mock("@/app/components/calendar/MonthView", () => ({ default: (props: ComponentProps<typeof MonthView>) => { boundary.month = props; return null; } }));
vi.mock("@/app/components/calendar/EventDetailSheet", () => ({ default: () => null }));
vi.mock("@/app/components/calendar/EventPeekPopover", () => ({ default: () => null }));
vi.mock("@/app/components/activity/activities/ActivityDialog", () => ({ default: () => null }));
vi.mock("@/app/components/motion/Rise", () => ({ default: ({ children }: { children?: ReactNode }) => children }));
vi.mock("@/app/components/PageShell", () => ({ PageShell: ({ children }: { children?: ReactNode }) => children }));
vi.mock("@/app/components/PageHeader", () => ({ PageHeader: () => null }));

const task: Task = { id: 41, workspaceId: ORIGIN, description: "Origin task", completed: false, status: "todo", position: 0, assignedToId: 7, dueDate: "2026-10-19", createdAt: "", updatedAt: "" };
let root: Root;
beforeEach(() => {
    vi.stubGlobal("IS_REACT_ACT_ENVIRONMENT", true);
    vi.stubGlobal("matchMedia", (media: string) => ({ media, matches: false, addEventListener: vi.fn(), removeEventListener: vi.fn() }));
    const container = document.createElement("div");
    document.body.appendChild(container);
    root = createRoot(container);
    boundary.month = null;
    bindRenderedWorkspace(shell, ORIGIN);
    document.cookie = "connex_workspace=" + ORIGIN + "; path=/";
});
afterEach(async () => {
    await act(async () => root.unmount());
    releaseRenderedWorkspace(shell);
    document.body.innerHTML = "";
    window.localStorage.clear();
    document.cookie = "connex_workspace=; path=/; max-age=0";
    vi.unstubAllGlobals();
    vi.clearAllMocks();
});

async function undo(): Promise<void> {
    const action = vi.mocked(toastSuccess).mock.calls.at(-1)?.[1]?.action;
    if (typeof action !== "object" || action === null || !("onClick" in action) || typeof action.onClick !== "function") throw new Error("The original action did not offer Undo");
    await act(async () => Reflect.apply(action.onClick, undefined, []));
}

describe("deferred Undo writes retain the original action workspace", () => {
    it("completes in A and undoes in A after explicitly switching to B", async () => {
        const backend = stubBackend();
        await act(async () => root.render(<UpcomingTasks tasks={[task]} locale="en" />));
        const checkbox = document.querySelector<HTMLButtonElement>("[role=checkbox]");
        if (checkbox === null) throw new Error("The task checkbox did not render");
        await act(async () => checkbox.click());
        expect(backend.requests).toHaveLength(1);
        switchWorkspace();
        await undo();
        await vi.waitFor(() => expect(backend.requests).toHaveLength(2));
        expect(backend.requests.map(({ path, workspace, init }) => ({ path, workspace, body: init.body }))).toEqual([
            { path: "/api/tasks/41", workspace: String(ORIGIN), body: JSON.stringify({ completed: true }) },
            { path: "/api/tasks/41", workspace: String(ORIGIN), body: JSON.stringify({ completed: false }) },
        ]);
    });

    it.each(["task", "deal"] as const)("reschedules a %s in A and undoes in A after explicitly switching to B", async (kind) => {
        const backend = stubBackend();
        await act(async () => root.render(<CalendarShell currentUserId={7} tasks={kind === "task" ? [task] : []} deals={kind === "deal" ? [{ ...deal, expectedCloseDate: "2026-10-19" }] : []} />));
        const month = boundary.month;
        if (month === null) throw new Error("The calendar month did not render");
        const event = [...month.eventsByDay.values()].flat().find((event) => event.kind === kind);
        if (!event) throw new Error("The reschedulable event did not render");
        await act(async () => month.onReschedule(event, "2026-10-20"));
        expect(backend.requests).toHaveLength(1);
        switchWorkspace();
        await undo();
        await vi.waitFor(() => expect(backend.requests).toHaveLength(2));
        const path = kind === "task" ? "/api/tasks/41/reschedule" : "/api/deals/41/reschedule";
        const field = kind === "task" ? "dueDate" : "expectedCloseDate";
        expect(backend.requests.map(({ path, workspace, init }) => ({ path, workspace, body: init.body }))).toEqual([
            { path, workspace: String(ORIGIN), body: JSON.stringify({ [field]: "2026-10-20" }) },
            { path, workspace: String(ORIGIN), body: JSON.stringify({ [field]: "2026-10-19" }) },
        ]);
    });
});
