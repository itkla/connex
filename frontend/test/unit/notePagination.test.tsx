/** @vitest-environment jsdom */

import { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import BacklinksPanel from "@/app/components/activity/notes/BacklinksPanel";
import TimelineContent from "@/app/components/me/TimelineContent";
import type { TimelineEntry } from "@/app/components/me/timelineEntries";
import type { Note } from "@/app/lib/types";

declare global {
    var IS_REACT_ACT_ENVIRONMENT: boolean;
}
globalThis.IS_REACT_ACT_ENVIRONMENT = true;

const api = vi.hoisted(() => ({ backlinks: vi.fn(), person: vi.fn(), deal: vi.fn(), user: vi.fn(), report: vi.fn(), row: vi.fn() }));
const scope = vi.hoisted(() => ({ workspaceId: 7, switching: false }));
vi.mock("@/app/lib/api", () => ({
    getNotesReferencing: api.backlinks,
    getNotesForPerson: api.person,
    getNotesForDeal: api.deal,
    getUserNotes: api.user,
}));
vi.mock("@/app/hooks/useWorkspace", () => ({ useWorkspace: () => ({ activeWorkspaceId: scope.workspaceId, switching: scope.switching }) }));
vi.mock("@/app/hooks/useApiErrorToast", () => ({ useApiErrorToast: () => api.report }));
vi.mock("next-intl", () => ({ useTranslations: () => (key: string) => key }));
vi.mock("@/app/components/me/TimelineDeepLinkFallback", () => ({ default: () => null }));
vi.mock("@/app/components/me/TimelineRow", () => ({
    default: (props: { entry: TimelineEntry; persons: unknown[]; deals: unknown[] }) => {
        api.row(props);
        return props.entry.kind === "note" ? <li data-note-id={props.entry.note.id}>{props.entry.note.content}</li> : null;
    },
}));

function note(id: number): Note {
    return { id, content: `Note ${id}`, author: 1, createdAt: "2026-01-01T00:00:00Z", updatedAt: "2026-01-01T00:00:00Z" };
}

const firstPage = Array.from({ length: 25 }, (_, index) => note(index + 1));
let container: HTMLDivElement;
let root: Root;

beforeEach(() => {
    vi.resetAllMocks();
    scope.workspaceId = 7;
    scope.switching = false;
    container = document.createElement("div");
    document.body.append(container);
    root = createRoot(container);
});

afterEach(async () => {
    await act(async () => root.unmount());
    container.remove();
});

async function loadMore() {
    const button = container.querySelector("button");
    expect(button).not.toBeNull();
    await act(async () => button?.click());
}

describe("note pagination", () => {
    it("reveals the 26th backlink after an explicit bounded continuation", async () => {
        api.backlinks.mockResolvedValueOnce(firstPage).mockResolvedValueOnce([note(26)]);
        await act(async () => root.render(<BacklinksPanel refType="person" refId={31} />));
        expect(container.querySelectorAll("li")).toHaveLength(25);
        expect(container.querySelector('a[href="/activity/notes/26"]')).toBeNull();
        await loadMore();
        expect(api.backlinks).toHaveBeenNthCalledWith(2, "person", 31, { page: 2, size: 25 }, expect.objectContaining({ signal: expect.any(AbortSignal) }));
        expect(container.querySelectorAll("li")).toHaveLength(26);
        expect(container.querySelector('a[href="/activity/notes/26"]')).not.toBeNull();
        expect(container.querySelector("button")).toBeNull();
    });

    it("keeps continuation when the excluded note is on a full raw page", async () => {
        api.backlinks.mockResolvedValueOnce(firstPage).mockResolvedValueOnce([note(26)]);
        await act(async () => root.render(<BacklinksPanel refType="note" refId={1} excludeNoteId={1} />));
        expect(container.querySelectorAll("li")).toHaveLength(24);
        await loadMore();
        expect(container.querySelector('a[href="/activity/notes/26"]')).not.toBeNull();
    });

    it("retains loaded rows and retries the same page after failure", async () => {
        api.backlinks.mockResolvedValueOnce(firstPage).mockRejectedValueOnce(new Error("Unavailable")).mockResolvedValueOnce([note(26)]);
        await act(async () => root.render(<BacklinksPanel refType="deal" refId={41} />));
        await loadMore();
        expect(container.querySelectorAll("li")).toHaveLength(25);
        expect(container.querySelector('[role="alert"]')).not.toBeNull();
        expect(api.report).toHaveBeenCalledOnce();
        await loadMore();
        expect(api.backlinks.mock.calls.map((call) => call[2])).toEqual([
            { page: 1, size: 25 }, { page: 2, size: 25 }, { page: 2, size: 25 },
        ]);
        expect(container.querySelectorAll("li")).toHaveLength(26);
    });

    it("ignores an old record response after the workspace or target changes", async () => {
        let resolveOld: (notes: Note[]) => void = () => undefined;
        api.backlinks.mockImplementationOnce(() => new Promise<Note[]>((resolve) => { resolveOld = resolve; }))
            .mockResolvedValueOnce([note(51)]);
        await act(async () => root.render(<BacklinksPanel refType="person" refId={31} />));
        scope.workspaceId = 8;
        await act(async () => root.render(<BacklinksPanel refType="person" refId={32} />));
        await act(async () => resolveOld(firstPage));
        expect(container.querySelectorAll("li")).toHaveLength(1);
        expect(container.querySelector('a[href="/activity/notes/51"]')).not.toBeNull();
    });

    it.each(["person", "deal", "user"] as const)("keeps an edited oldest %s note first across refresh and cursor continuation", async (type) => {
        const fetch = type === "person" ? api.person : type === "deal" ? api.deal : api.user;
        const initial = Array.from({ length: 25 }, (_, index) => ({
            ...note(26 - index),
            createdAt: `2026-01-${String(26 - index).padStart(2, "0")}T00:00:00Z`,
            updatedAt: `2026-01-${String(26 - index).padStart(2, "0")}T00:00:00Z`,
        }));
        const oldest = { ...note(1), createdAt: "2025-01-01T00:00:00Z", updatedAt: "2025-01-01T00:00:00Z" };
        fetch.mockResolvedValueOnce([oldest]);
        const props = { tasks: [], activities: [], noteTarget: { type, id: 31 }, originWorkspaceId: 7 };
        await act(async () => root.render(<TimelineContent {...props} notes={initial} />));
        await loadMore();
        expect(container.querySelector("li:last-child")?.getAttribute("data-note-id")).toBe("1");
        const edited = { ...oldest, content: "Edited oldest", updatedAt: "2026-09-15T00:00:00Z" };
        const refreshed = [edited, ...initial.slice(0, 24)];
        fetch.mockResolvedValueOnce(initial.slice(24));
        await act(async () => root.render(<TimelineContent {...props} notes={refreshed} />));
        expect(container.querySelector("li")?.getAttribute("data-note-id")).toBe("1");
        expect(container.querySelector("li")?.textContent).toBe("Edited oldest");
        expect(container.querySelectorAll("li")).toHaveLength(25);
        await loadMore();
        expect(fetch).toHaveBeenLastCalledWith(31, {
            page: 1, size: 25, beforeAt: refreshed[24].updatedAt, beforeId: refreshed[24].id,
        }, expect.anything());
        const ids = Array.from(container.querySelectorAll("li"), (element) => element.getAttribute("data-note-id"));
        expect(ids[0]).toBe("1");
        expect(ids).toHaveLength(26);
        expect(new Set(ids).size).toBe(26);
    });

    it("keeps omitted record option arrays stable across timeline renders", async () => {
        api.person.mockResolvedValueOnce([note(26)]);
        await act(async () => root.render(<TimelineContent tasks={[]} activities={[]} notes={firstPage}
            noteTarget={{ type: "person", id: 31 }} originWorkspaceId={7} />));
        const first = api.row.mock.calls[0]?.[0];
        expect(first).toBeDefined();
        await loadMore();
        const last = api.row.mock.lastCall?.[0];
        expect(last.persons).toBe(first.persons);
        expect(last.deals).toBe(first.deals);
    });

    it("orders equal-time notes by descending id across a continuation boundary", async () => {
        const initial = Array.from({ length: 25 }, (_, index) => note(26 - index));
        api.person.mockResolvedValueOnce([note(1)]);
        await act(async () => root.render(<TimelineContent tasks={[]} activities={[]} notes={initial}
            noteTarget={{ type: "person", id: 31 }} originWorkspaceId={7} />));
        await loadMore();
        expect(Array.from(container.querySelectorAll("li"), (element) => Number(element.getAttribute("data-note-id"))))
            .toEqual(Array.from({ length: 26 }, (_, index) => 26 - index));
        expect(api.person).toHaveBeenCalledWith(31, {
            page: 1, size: 25, beforeAt: initial[24].updatedAt, beforeId: 2,
        }, expect.anything());
    });

    it.each(["person", "deal", "user"] as const)("continues the %s timeline beyond its embedded first page", async (type) => {
        const fetch = type === "person" ? api.person : type === "deal" ? api.deal : api.user;
        fetch.mockResolvedValueOnce([note(26)]);
        await act(async () => root.render(<TimelineContent tasks={[]} activities={[]} notes={firstPage}
            noteTarget={{ type, id: 31 }} originWorkspaceId={7} />));
        expect(fetch).not.toHaveBeenCalled();
        expect(container.querySelectorAll("li")).toHaveLength(25);
        await loadMore();
        expect(fetch).toHaveBeenCalledWith(31, { page: 1, size: 25, beforeAt: firstPage[24].updatedAt, beforeId: 25 }, expect.objectContaining({ signal: expect.any(AbortSignal) }));
        expect(container.querySelector('[data-note-id="26"]')).not.toBeNull();
        expect(container.querySelectorAll("li")).toHaveLength(26);
        expect(container.querySelector("button")).toBeNull();
    });

    it("reaches the 101st authored note through both user timelines' bounded continuation", async () => {
        api.user.mockImplementation((_id: number, { beforeId }: { beforeId: number }) => Promise.resolve(
            Array.from({ length: beforeId === 100 ? 1 : 25 }, (_, index) => note(beforeId + index + 1))));
        await act(async () => root.render(<TimelineContent tasks={[]} activities={[]} notes={firstPage}
            noteTarget={{ type: "user", id: 51 }} originWorkspaceId={7} />));
        for (let page = 2; page <= 5; page++) await loadMore();
        expect(container.querySelectorAll("li")).toHaveLength(101);
        expect(container.querySelector('[data-note-id="101"]')).not.toBeNull();
        expect(api.user).toHaveBeenLastCalledWith(51, { page: 1, size: 25, beforeAt: note(100).updatedAt, beforeId: 100 }, expect.anything());
        expect(container.querySelector("button")).toBeNull();
    });

    it("discards an in-flight page on refresh and restarts continuation at page two", async () => {
        let resolvePage: (notes: Note[]) => void = () => undefined;
        api.person.mockImplementationOnce(() => new Promise<Note[]>((resolve) => { resolvePage = resolve; }));
        const render = () => root.render(<TimelineContent tasks={[]} activities={[]} notes={[...firstPage]}
            noteTarget={{ type: "person", id: 31 }} originWorkspaceId={7} />);
        await act(async () => render());
        await loadMore();
        expect(container.querySelector("button")?.disabled).toBe(true);
        await act(async () => render());
        await act(async () => resolvePage([note(26)]));
        expect(container.querySelector('[data-note-id="26"]')).toBeNull();
        expect(container.querySelector("button")?.disabled).toBe(false);
        api.person.mockResolvedValueOnce([note(27)]);
        await loadMore();
        expect(api.person).toHaveBeenLastCalledWith(31, { page: 1, size: 25, beforeAt: firstPage[24].updatedAt, beforeId: 25 }, expect.anything());
        expect(container.querySelector('[data-note-id="27"]')).not.toBeNull();
    });

    it("aborts pending timeline reads while the workspace changes before the server refresh", async () => {
        let resolvePage: (notes: Note[]) => void = () => undefined;
        let signal: AbortSignal | null = null;
        api.person.mockImplementationOnce((_id: number, _params: unknown, init: RequestInit) => {
            signal = init.signal ?? null;
            return new Promise<Note[]>((resolve) => { resolvePage = resolve; });
        });
        const render = () => root.render(<TimelineContent tasks={[]} activities={[]} notes={firstPage}
            noteTarget={{ type: "person", id: 31 }} originWorkspaceId={7} />);
        await act(async () => render());
        await loadMore();
        scope.switching = true;
        await act(async () => render());
        expect(signal).toHaveProperty("aborted", true);
        scope.workspaceId = 8;
        scope.switching = false;
        await act(async () => render());
        await act(async () => resolvePage([note(26)]));
        expect(container.querySelectorAll("li")).toHaveLength(0);
        expect(container.querySelector("button")).toBeNull();
        expect(api.person).toHaveBeenCalledOnce();
    });
});
