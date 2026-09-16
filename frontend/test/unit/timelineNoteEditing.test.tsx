/** @vitest-environment jsdom */

import { act, type ReactNode } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import TimelineRow from "@/app/components/me/TimelineRow";
import TimelineContent from "@/app/components/me/TimelineContent";
import type { Note } from "@/app/lib/types";

declare global {
    var IS_REACT_ACT_ENVIRONMENT: boolean;
}
globalThis.IS_REACT_ACT_ENVIRONMENT = true;

const api = vi.hoisted(() => ({ read: vi.fn(), page: vi.fn(), remove: vi.fn(), save: vi.fn(), refresh: vi.fn(), report: vi.fn() }));
vi.mock("@/app/lib/api", () => ({ getNoteById: api.read, getNotesForPerson: api.page, deleteNote: api.remove }));
vi.mock("@/app/hooks/useWorkspace", () => ({ useWorkspace: () => ({ activeWorkspaceId: 7, switching: false }) }));
vi.mock("@/app/components/me/TimelineDeepLinkFallback", () => ({ default: () => null }));
vi.mock("@/app/lib/toast", () => ({ toastSuccess: vi.fn() }));
vi.mock("@/app/hooks/useApiErrorToast", () => ({ useApiErrorToast: () => api.report }));
vi.mock("@/app/hooks/useRecordTargetSearch", () => ({
    useContactTargetSearch: () => ({ contacts: [] }),
    useDealTargetSearch: () => ({ deals: [] }),
}));
vi.mock("next-intl", () => ({ useTranslations: () => (key: string) => key, useLocale: () => "en" }));
vi.mock("next/navigation", () => ({
    usePathname: () => "/records/contacts/31", useRouter: () => ({ refresh: api.refresh }),
    useSearchParams: () => new URLSearchParams(),
}));
vi.mock("motion/react", () => ({ useReducedMotion: () => true }));
vi.mock("@/app/components/activity/tasks/EditTaskSheet", () => ({ default: () => null }));
vi.mock("@/app/components/activity/activities/EditActivitySheet", () => ({ default: () => null }));
vi.mock("@/app/components/activity/notes/NoteContent", () => ({ default: ({ content }: { content: string }) => content }));
vi.mock("@/app/components/activity/notes/NoteDialog", () => ({
    default: ({ note, open }: { note: Note; open: boolean }) => open ? <>
        <textarea aria-label="note editor" readOnly value={note.content} />
        <button onClick={async () => { await api.save(note.id); api.refresh(); }}>save</button>
    </> : null,
}));
vi.mock("@/components/ui/tooltip", () => ({
    Tooltip: ({ children }: { children: ReactNode }) => children,
    TooltipContent: () => null,
    TooltipTrigger: ({ children }: { children: ReactNode }) => children,
}));
vi.mock("@/components/ui/dropdown-menu", () => ({
    DropdownMenu: ({ children }: { children: ReactNode }) => children,
    DropdownMenuContent: ({ children }: { children: ReactNode }) => children,
    DropdownMenuTrigger: ({ children }: { children: ReactNode }) => children,
    DropdownMenuSeparator: () => null,
    DropdownMenuItem: ({ children, onClick, disabled }: { children: ReactNode; onClick: () => void; disabled?: boolean }) =>
        <button disabled={disabled} onClick={onClick}>{children}</button>,
}));

const preview: Note = { id: 17, content: "x".repeat(500), author: 1, createdAt: "2026-01-01T00:00:00Z", updatedAt: "2026-01-01T00:00:00Z" };
let root: Root;
let container: HTMLDivElement;
beforeEach(async () => {
    vi.resetAllMocks();
    container = document.createElement("div");
    document.body.append(container);
    root = createRoot(container);
    await act(async () => root.render(<ul><TimelineRow entry={{ kind: "note", sortAt: 0, note: preview }}
        persons={[]} deals={[]} companyId={null} currentUserId={1} originWorkspaceId={7} /></ul>));
});
afterEach(async () => {
    await act(async () => root.unmount());
    container.remove();
});

async function edit() {
    const button = [...container.querySelectorAll("button")].find((item) => item.textContent === "edit");
    expect(button).toBeDefined();
    await act(async () => button?.click());
}

describe("timeline note editing", () => {
    it("opens only the freshly loaded full content, preserving the tail beyond the preview", async () => {
        const full = { ...preview, content: "x".repeat(50_000) + "preserved tail" };
        api.read.mockResolvedValue(full);
        await edit();
        expect(api.read).toHaveBeenCalledWith(17, expect.objectContaining({ signal: expect.any(AbortSignal) }));
        expect(container.querySelector("textarea")?.value).toBe(full.content);
    });

    it("keeps the editor closed when the full-note read is refused", async () => {
        api.read.mockRejectedValue(new Error("Note not found"));
        await edit();
        expect(container.querySelector("textarea")).toBeNull();
        expect(api.report).toHaveBeenCalledOnce();
    });

    it("clears a rejected read's busy state and allows a successful retry", async () => {
        api.read.mockRejectedValueOnce(new Error("Unavailable")).mockResolvedValueOnce(preview);
        await edit();
        const button = [...container.querySelectorAll("button")].find((item) => item.textContent === "edit");
        expect(button?.disabled).toBe(false);
        await edit();
        expect(api.read).toHaveBeenCalledTimes(2);
        expect(container.querySelector("textarea")?.value).toBe(preview.content);
    });

    it.each(["delete", "edit"] as const)("reconciles a second-page %s after refresh with an unchanged first page", async (mutation) => {
        const firstPage = Array.from({ length: 25 }, (_, index) => ({ ...preview, id: index + 1, content: `First-page note ${index + 1}` }));
        const later = { ...preview, id: 26, content: "Later-page note" };
        let secondPage = [later, { ...preview, id: 27, content: "Following note" }];
        api.page.mockImplementation(() => Promise.resolve(secondPage));
        api.read.mockResolvedValue(later);
        api.remove.mockImplementation(async () => { secondPage = secondPage.filter((note) => note.id !== 26); });
        api.save.mockImplementation(async () => { secondPage = secondPage.map((note) => note.id === 26 ? { ...note, content: "Updated later-page note" } : note); });
        const render = () => root.render(<TimelineContent tasks={[]} activities={[]} notes={[...firstPage]}
            noteTarget={{ type: "person", id: 31 }} currentUserId={1} originWorkspaceId={7} />);
        api.refresh.mockImplementation(render);
        await act(async () => render());
        const loadMore = async () => {
            const button = [...container.querySelectorAll("button")].find((item) => item.textContent === "loadMore");
            expect(button).toBeDefined();
            await act(async () => button?.click());
        };
        await loadMore();
        const laterRow = [...container.querySelectorAll("li")].find((item) => item.textContent?.includes("Later-page note"));
        expect(laterRow).toBeDefined();
        const action = [...laterRow?.querySelectorAll("button") ?? []].find((item) => item.textContent === mutation);
        expect(action).toBeDefined();
        await act(async () => action?.click());
        if (mutation === "edit") {
            expect(api.read).toHaveBeenCalledWith(26, expect.anything());
            const save = [...container.querySelectorAll("button")].find((item) => item.textContent === "save");
            expect(save).toBeDefined();
            await act(async () => save?.click());
            expect(api.save).toHaveBeenCalledWith(26);
        } else {
            expect(api.remove).toHaveBeenCalledWith(26);
        }
        expect(api.refresh).toHaveBeenCalledOnce();
        expect(container.querySelectorAll("li")).toHaveLength(25);
        expect(container.textContent).not.toContain("Later-page note");
        await loadMore();
        expect(api.page).toHaveBeenLastCalledWith(31, expect.objectContaining({ page: 1, size: 25, beforeId: 25 }), expect.anything());
        expect(container.textContent).toContain("Following note");
        if (mutation === "edit") {
            expect(container.textContent).toContain("Updated later-page note");
        } else {
            expect(container.textContent).not.toContain("Later-page note");
        }
    });
});
