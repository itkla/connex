/** @vitest-environment jsdom */

import { act, type ReactNode } from "react";
import { createRoot, type Root } from "react-dom/client";
import { NextIntlClientProvider } from "next-intl";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import DealTaskList from "@/app/components/records/deals/DealTaskList";
import type { Task } from "@/app/lib/types";
import enDeals from "@/messages/en/deals.json";
import enRecords from "@/messages/en/records.json";
import jaDeals from "@/messages/ja/deals.json";
import jaRecords from "@/messages/ja/records.json";

globalThis.IS_REACT_ACT_ENVIRONMENT = true;

const api = vi.hoisted(() => ({ remove: vi.fn(), update: vi.fn(), refresh: vi.fn(), report: vi.fn() }));
vi.mock("@/app/lib/api", () => ({ deleteTask: api.remove, updateTask: api.update }));
vi.mock("@/app/lib/toast", () => ({ toastSuccess: vi.fn() }));
vi.mock("@/app/hooks/useApiErrorToast", () => ({ useApiErrorToast: () => api.report }));
vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh: api.refresh }) }));
vi.mock("motion/react", () => ({
    useReducedMotion: () => true,
    AnimatePresence: ({ children }: { children: ReactNode }) => children,
    motion: {
        div: ({ children }: { children: ReactNode }) => <div>{children}</div>,
        li: ({ children }: { children: ReactNode }) => <li>{children}</li>,
    },
}));
vi.mock("@/app/components/activity/tasks/EditTaskSheet", () => ({ default: () => null }));
vi.mock("@/app/components/activity/notes/NoteContent", () => ({ default: ({ content }: { content: string }) => content }));
vi.mock("@/components/ui/tooltip", () => ({
    Tooltip: ({ children }: { children: ReactNode }) => children,
    TooltipContent: () => null, TooltipTrigger: ({ children }: { children: ReactNode }) => children,
}));
vi.mock("@/components/ui/dropdown-menu", () => ({
    DropdownMenu: ({ children }: { children: ReactNode }) => children,
    DropdownMenuContent: ({ children }: { children: ReactNode }) => children,
    DropdownMenuTrigger: ({ children }: { children: ReactNode }) => children,
    DropdownMenuSeparator: () => null,
    DropdownMenuItem: ({ children, onClick }: { children: ReactNode; onClick: () => void }) =>
        <button onClick={onClick}>{children}</button>,
}));

const task: Task = {
    id: 41, description: "Call **customer**", completed: false, status: "todo", position: 0,
    assignedToId: 2, dealId: 31, createdAt: "", updatedAt: "",
};
let root: Root;
let container: HTMLDivElement;

beforeEach(() => {
    vi.resetAllMocks();
    container = document.createElement("div");
    document.body.append(container);
    root = createRoot(container);
});
afterEach(async () => {
    await act(async () => root.unmount());
    container.remove();
});

async function render(locale: "en" | "ja" = "en", tasks = [task]) {
    const messages = locale === "en" ? { ...enDeals, ...enRecords } : { ...jaDeals, ...jaRecords };
    await act(async () => root.render(
        <NextIntlClientProvider locale={locale} messages={messages} timeZone="UTC">
            <DealTaskList dealId={31} tasks={tasks} deals={[]} />
        </NextIntlClientProvider>,
    ));
}

async function click(label: string, within: ParentNode = document) {
    const button = [...within.querySelectorAll("button")].find((item) => item.textContent === label);
    if (!button) throw new Error(`Missing button ${label}`);
    await act(async () => button.click());
}

function dialog() {
    const result = document.querySelector('[role="dialog"]');
    if (!result) throw new Error("Missing delete confirmation");
    return result;
}

describe.each(["en", "ja"] as const)("deal task deletion in %s", (locale) => {
    it("names the task, preserves the row on cancellation, and deletes only after confirmation", async () => {
        const labels = locale === "en" ? enRecords.RecordsDeleteDialog : jaRecords.RecordsDeleteDialog;
        await render(locale);
        const actions = container.querySelector('button[data-size="icon"]');
        await click(labels.delete, container);
        expect(dialog().textContent).toContain("Call customer");
        expect(dialog().textContent).toContain(locale === "en" ? "This can't be undone." : "この操作は取り消せません。");
        expect(api.remove).not.toHaveBeenCalled();
        expect(container.querySelector("li")).not.toBeNull();
        await click(labels.cancel, dialog());
        expect(document.querySelector('[role="dialog"]')).toBeNull();
        await vi.waitFor(() => expect(document.activeElement).toBe(actions));
        expect(api.remove).not.toHaveBeenCalled();
        expect(container.querySelector("li")).not.toBeNull();
        await click(labels.delete, container);
        await click(labels.delete, dialog());
        expect(api.remove).toHaveBeenCalledExactlyOnceWith(41);
        expect(api.refresh).toHaveBeenCalledOnce();
        expect(document.querySelector('[role="dialog"]')).toBeNull();
    });
});

it("keeps the task and confirmation available after a failed DELETE", async () => {
    api.remove.mockRejectedValueOnce(new Error("Unavailable"));
    await render();
    await click("Delete", container);
    await click("Delete", dialog());
    expect(api.report).toHaveBeenCalledOnce();
    expect(api.refresh).not.toHaveBeenCalled();
    expect(container.querySelector("li")).not.toBeNull();
    await click("Delete", dialog());
    expect(api.remove).toHaveBeenCalledTimes(2);
    expect(api.refresh).toHaveBeenCalledOnce();
});

it("preserves the row and blocks duplicate confirmation while DELETE is pending", async () => {
    let finish: (() => void) | undefined;
    api.remove.mockImplementation(() => new Promise<void>((resolve) => { finish = resolve; }));
    await render();
    await click("Delete", container);
    await click("Delete", dialog());
    expect(container.querySelector("li")).not.toBeNull();
    const cancel = [...dialog().querySelectorAll("button")].find((button) => button.textContent === "Cancel");
    expect(cancel?.disabled).toBe(true);
    const confirm = dialog().querySelector('button[data-variant="destructive"]');
    if (!(confirm instanceof HTMLButtonElement)) throw new Error("Missing destructive button");
    expect(confirm.disabled).toBe(true);
    await act(async () => confirm.click());
    expect(api.remove).toHaveBeenCalledOnce();
    await act(async () => finish?.());
    expect(api.refresh).toHaveBeenCalledOnce();
});

it("returns focus to the selected row when there are multiple tasks", async () => {
    await render("en", [task, { ...task, id: 42, description: "Second task" }]);
    const row = container.querySelectorAll("li")[1];
    const actions = row.querySelector('button[data-size="icon"]');
    await click("Delete", row);
    expect(dialog().textContent).toContain("Second task");
    await click("Cancel", dialog());
    await vi.waitFor(() => expect(document.activeElement).toBe(actions));
    expect(api.remove).not.toHaveBeenCalled();
});
