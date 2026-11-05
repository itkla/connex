/** @vitest-environment jsdom */

import { act, type ReactNode } from "react";
import { createRoot, type Root } from "react-dom/client";
import { NextIntlClientProvider } from "next-intl";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import TimelineRow from "@/app/components/me/TimelineRow";
import type { TimelineEntry } from "@/app/components/me/timelineEntries";
import enMe from "@/messages/en/me.json";
import enRecords from "@/messages/en/records.json";
import enContacts from "@/messages/en/contacts.json";
import jaMe from "@/messages/ja/me.json";
import jaRecords from "@/messages/ja/records.json";
import jaContacts from "@/messages/ja/contacts.json";

globalThis.IS_REACT_ACT_ENVIRONMENT = true;

const api = vi.hoisted(() => ({
    task: vi.fn(), activity: vi.fn(), note: vi.fn(), refresh: vi.fn(), report: vi.fn(),
}));
vi.mock("@/app/lib/api", () => ({ deleteTask: api.task, deleteActivity: api.activity, deleteNote: api.note }));
vi.mock("@/app/lib/toast", () => ({ toastSuccess: vi.fn() }));
vi.mock("@/app/hooks/useApiErrorToast", () => ({ useApiErrorToast: () => api.report }));
vi.mock("@/app/hooks/useRecordTargetSearch", () => ({
    useContactTargetSearch: () => ({ contacts: [] }), useDealTargetSearch: () => ({ deals: [] }),
}));
vi.mock("next/navigation", () => ({
    usePathname: () => "/records/contacts/31", useRouter: () => ({ refresh: api.refresh }),
    useSearchParams: () => new URLSearchParams(),
}));
vi.mock("motion/react", () => ({ useReducedMotion: () => true }));
vi.mock("@/app/components/activity/tasks/EditTaskSheet", () => ({ default: () => null }));
vi.mock("@/app/components/activity/activities/EditActivitySheet", () => ({ default: () => null }));
vi.mock("@/app/components/activity/notes/NoteDialog", () => ({ default: () => null }));
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

const entries: TimelineEntry[] = [
    { kind: "task", sortAt: 0, task: { id: 41, description: "Call **customer**", completed: false, status: "todo", position: 0, assignedToId: 2, createdAt: "", updatedAt: "" } },
    { kind: "activity", sortAt: 0, activity: { id: 42, subject: "Customer meeting", type: "meeting", createdById: 2 } },
    { kind: "note", sortAt: 0, note: { id: 43, title: "Meeting decisions", content: "Full note content", author: 2, createdAt: "", updatedAt: "" } },
];
const names = ["Call customer", "Customer meeting", "Meeting decisions"];
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

async function render(entry: TimelineEntry, locale: "en" | "ja" = "en") {
    const messages = locale === "en"
        ? { ...enMe, ...enRecords, ContactLifecycle: enContacts.ContactLifecycle }
        : { ...jaMe, ...jaRecords, ContactLifecycle: jaContacts.ContactLifecycle };
    await act(async () => root.render(
        <NextIntlClientProvider locale={locale} messages={messages} timeZone="UTC">
            <ul><TimelineRow entry={entry} author={{ id: 2, displayName: "Aiko", username: "aiko" }}
                persons={[]} deals={[]} companyId={null} currentUserId={1} originWorkspaceId={7} /></ul>
        </NextIntlClientProvider>,
    ));
}

async function click(label: string, within: ParentNode = document) {
    const button = [...within.querySelectorAll("button")].find((item) => item.textContent === label);
    expect(button).toBeDefined();
    await act(async () => button?.click());
}

describe.each(["en", "ja"] as const)("timeline deletion in %s", (locale) => {
    it.each(entries.map((entry, index) => ({ entry, name: names[index] })))(
        "names the $entry.kind and teammate, and requires confirmation",
        async ({ entry, name }) => {
            const labels = locale === "en" ? enRecords.RecordsDeleteDialog : jaRecords.RecordsDeleteDialog;
            await render(entry, locale);
            await click(labels.delete, container);
            const dialog = document.querySelector('[role="dialog"]');
            expect(dialog?.textContent).toContain(name);
            expect(dialog?.textContent).toContain("Aiko");
            expect(dialog?.textContent).toContain(locale === "en" ? "This can't be undone." : "この操作は取り消せません。");
            expect(api.task).not.toHaveBeenCalled();
            expect(api.activity).not.toHaveBeenCalled();
            expect(api.note).not.toHaveBeenCalled();
            await click(labels.cancel);
            expect(document.querySelector('[role="dialog"]')).toBeNull();
            expect(api.refresh).not.toHaveBeenCalled();
            await click(labels.delete, container);
            const confirmation = document.querySelector('[role="dialog"]');
            expect(confirmation).not.toBeNull();
            if (!confirmation) throw new Error("Missing confirmation");
            await click(labels.delete, confirmation);
            const expected = entry.kind === "task" ? api.task : entry.kind === "activity" ? api.activity : api.note;
            expect(expected).toHaveBeenCalledExactlyOnceWith(entry.kind === "task" ? 41 : entry.kind === "activity" ? 42 : 43);
            expect(api.refresh).toHaveBeenCalledOnce();
            expect(document.querySelector('[role="dialog"]')).toBeNull();
        },
    );
});

it("keeps a failed deletion available for a deliberate retry", async () => {
    api.task.mockRejectedValueOnce(new Error("Unavailable"));
    await render(entries[0]);
    await click("Delete", container);
    const dialog = document.querySelector('[role="dialog"]');
    if (!dialog) throw new Error("Missing confirmation");
    await click("Delete", dialog);
    expect(api.report).toHaveBeenCalledOnce();
    expect(api.refresh).not.toHaveBeenCalled();
    expect(document.querySelector('[role="dialog"]')).not.toBeNull();
    await click("Delete", dialog);
    expect(api.task).toHaveBeenCalledTimes(2);
    expect(api.refresh).toHaveBeenCalledOnce();
});

it("disables confirmation and cancellation while the request is pending", async () => {
    let finish: (() => void) | undefined;
    api.task.mockImplementation(() => new Promise<void>((resolve) => { finish = resolve; }));
    await render(entries[0]);
    await click("Delete", container);
    const dialog = document.querySelector('[role="dialog"]');
    if (!dialog) throw new Error("Missing confirmation");
    await click("Delete", dialog);
    const buttons = [...dialog.querySelectorAll("button")];
    expect(buttons.find((button) => button.textContent === "Cancel")?.disabled).toBe(true);
    expect(buttons.find((button) => button.dataset.variant === "destructive")?.disabled).toBe(true);
    expect(api.task).toHaveBeenCalledOnce();
    await act(async () => finish?.());
    expect(api.refresh).toHaveBeenCalledOnce();
});
