/** @vitest-environment jsdom */
import { act, type ReactNode, useEffect } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import NoteEditorView from "@/app/components/activity/notes/NoteEditorView";
import DashboardGrid from "@/app/components/dashboard/customize/DashboardGrid";
import NewWorkspaceDialog from "@/app/components/NewWorkspaceDialog";
import { useWorkspace, WorkspaceProvider } from "@/app/hooks/useWorkspace";
import { draftKey, listFreshDrafts, readDraft, writeDraft } from "@/app/lib/formDrafts";
import { renderedWorkspaceId } from "@/app/lib/renderedWorkspace";
import { toastError, toastInfo } from "@/app/lib/toast";
import type { DashboardWidgetType, Note, Workspace } from "@/app/lib/types";

const editor = vi.hoisted<{
    onChange: ((value: string) => void) | null;
    ensureNoteId: (() => Promise<number>) | null;
    value: string | null;
}>(() => ({
    onChange: null,
    ensureNoteId: null,
    value: null,
}));
const translate = vi.hoisted(() => Object.assign((key: string) => key, { has: () => false }));

vi.mock("next/navigation", () => ({
    useRouter: () => ({ refresh: vi.fn(), replace: vi.fn(), push: vi.fn() }),
}));

vi.mock("next-intl", () => ({
    useLocale: () => "en",
    useTranslations: () => translate,
}));

vi.mock("next/dynamic", () => ({
    default: () => function RichNoteEditor({ value, onChange, ensureNoteId }: {
        value: string;
        onChange: (value: string) => void;
        ensureNoteId: () => Promise<number>;
    }) {
        editor.onChange = onChange;
        editor.ensureNoteId = ensureNoteId;
        editor.value = value;
        return null;
    },
}));

vi.mock("next/link", () => ({
    default: ({ children }: { children?: ReactNode }) => <a>{children}</a>,
}));

vi.mock("motion/react", () => ({
    AnimatePresence: ({ children }: { children?: ReactNode }) => <>{children}</>,
    motion: { div: ({ children }: { children?: ReactNode }) => <div>{children}</div> },
    useReducedMotion: () => true,
}));

vi.mock("@/app/lib/toast", () => ({
    toastError: vi.fn(),
    toastSuccess: vi.fn(),
    toastWarn: vi.fn(),
    toastInfo: vi.fn(),
    toastDismiss: vi.fn(),
}));

vi.mock("@/app/components/PageShell", () => ({
    PageShell: ({ children }: { children?: ReactNode }) => <div>{children}</div>,
}));

vi.mock("@/app/hooks/useNavTrail", () => ({ CrumbLabel: () => null }));

vi.mock("@/app/components/records/RecordReturnLink", () => ({
    default: ({ children }: { children?: ReactNode }) => <a>{children}</a>,
}));

vi.mock("@/app/components/activity/notes/BacklinksPanel", () => ({ default: () => null }));

vi.mock("@/components/ui/segmented-control", () => ({ SegmentedControl: () => null }));

vi.mock("@/app/components/layout/SortableGrid", () => ({ default: () => null }));

vi.mock("@/app/components/dashboard/customize/WidgetShell", () => ({ default: () => null }));

vi.mock("@/components/PixelCard", () => ({ default: () => null }));

const HEALED = 5;
const ORIGIN = 9;
const USER = 7;
const DRAFT = "Board pricing plan for the renewal";
const COMPOSER_DRAFT = "Call notes from the quick composer";

type Recorded = { method: string; path: string; workspace: string | null; body: string };

const NO_WIDGET_NODES: Record<DashboardWidgetType, ReactNode> = {
    overview: null,
    pipeline: null,
    tasks: null,
    atRiskDeals: null,
    coolingRelationships: null,
    recentMoves: null,
    introOpportunities: null,
    recentFiles: null,
    recentActivity: null,
    companyWarmth: null,
    warmthDistribution: null,
    closingSoon: null,
    recentNotes: null,
    notifications: null,
    quickActions: null,
    analyticsKpis: null,
    revenueTrend: null,
    winRate: null,
    pipelineValue: null,
    stageFunnel: null,
    activityVolume: null,
    teamLeaderboard: null,
};

function workspace(id: number, name: string): Workspace {
    return {
        id,
        name,
        slug: name.toLowerCase().replaceAll(" ", "-"),
        timezone: null,
        identityVersion: 0,
        role: "member",
        orgId: id,
        orgName: name,
        orgIdentityVersion: 0,
        orgRole: null,
    };
}

function json(body: unknown, status = 200): Response {
    return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

/** Answers the switch, and refuses or accepts each content write the way a revoked or live origin would. */
function stubBackend(originRevoked: boolean | (() => boolean)): Recorded[] {
    const requests: Recorded[] = [];
    vi.stubGlobal("fetch", vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
        const url = new URL(input instanceof Request ? input.url : String(input), "http://localhost");
        const method = init?.method ?? "GET";
        if (url.pathname === "/api/auth/csrf") {
            return json({ token: "csrf-token", headerName: "X-XSRF-TOKEN", requestIdentity: "identity" });
        }
        const workspaceHeader = new Headers(init?.headers).get("X-Workspace-Id");
        const revoked = typeof originRevoked === "function" ? originRevoked() : originRevoked;
        requests.push({
            method,
            path: url.pathname,
            workspace: workspaceHeader,
            body: typeof init?.body === "string" ? init.body : "",
        });
        if (url.pathname === `/api/workspaces/${HEALED}/switch`) {
            document.cookie = `connex_workspace=${HEALED}; path=/`;
            return new Response("", { status: 200 });
        }
        if (url.pathname === "/api/workspaces" && method === "GET") {
            const memberships = revoked
                ? [workspace(HEALED, "Head Office")]
                : [workspace(HEALED, "Head Office"), workspace(ORIGIN, "Northwind Field Team")];
            return json({ workspaces: memberships, activeWorkspaceId: HEALED });
        }
        if (revoked && workspaceHeader === String(ORIGIN)) {
            return json({ code: "FORBIDDEN", message: `Not a member of workspace ${ORIGIN}` }, 403);
        }
        return method === "DELETE" ? new Response("", { status: 204 }) : json({ id: 41, layout: null });
    }));
    return requests;
}

function writesTo(requests: Recorded[], path: string): Recorded[] {
    return requests.filter((request) => request.path === path && request.method !== "GET");
}

async function settle(milliseconds = 0): Promise<void> {
    await act(async () => {
        await new Promise((resolve) => setTimeout(resolve, milliseconds));
    });
}

let root: Root | null = null;
let captured: ReturnType<typeof useWorkspace> | null = null;

function Capture({ onRender }: { onRender: (value: ReturnType<typeof useWorkspace>) => void }): null {
    const value = useWorkspace();
    useEffect(() => {
        onRender(value);
    }, [onRender, value]);
    return null;
}

function remember(value: ReturnType<typeof useWorkspace>): void {
    captured = value;
}

async function renderInShell(child: ReactNode): Promise<void> {
    const container = document.createElement("div");
    document.body.appendChild(container);
    root = createRoot(container);
    await act(async () => {
        root?.render(shell(child));
    });
}

function shell(child: ReactNode): ReactNode {
    return (
        <WorkspaceProvider
            initialWorkspaces={[workspace(HEALED, "Head Office"), workspace(ORIGIN, "Northwind Field Team")]}
            initialActiveId={ORIGIN}
        >
            <Capture onRender={remember} />
            {child}
        </WorkspaceProvider>
    );
}

/** Heals the cookie, then makes the explicit switch that publishes the new workspace before navigation. */
async function healAndSwitch(): Promise<void> {
    document.cookie = `connex_workspace=${HEALED}; path=/`;
    await act(async () => {
        if (captured === null) throw new Error("WorkspaceProvider did not render");
        await captured.switchTo(HEALED);
    });
    expect(renderedWorkspaceId()).toBe(HEALED);
}

beforeEach(() => {
    vi.stubGlobal("IS_REACT_ACT_ENVIRONMENT", true);
    document.cookie = `connex_workspace=${ORIGIN}; path=/`;
    editor.onChange = null;
    editor.ensureNoteId = null;
    editor.value = null;
    captured = null;
});

afterEach(async () => {
    await act(async () => root?.unmount());
    root = null;
    document.body.innerHTML = "";
    document.cookie = "connex_workspace=; path=/; max-age=0";
    window.sessionStorage.clear();
    vi.unstubAllGlobals();
    vi.clearAllMocks();
});

describe("a note composed in one workspace and flushed after a switch to another", () => {
    it.each(["autosave", "inline image"])("keeps later refused edits with the note created by %s and offers them there", async (creation) => {
        let revoked = false;
        const requests = stubBackend(() => revoked);
        const savedNote: Note = {
            id: 41,
            author: USER,
            content: DRAFT,
            createdAt: "2026-10-18T00:00:00Z",
            updatedAt: "2026-10-18T00:00:00Z",
        };
        await renderInShell(
            <NoteEditorView note={null} currentUserId={USER} persons={[]} deals={[]} users={[]} returnPath="/activity/notes" />,
        );
        await act(async () => {
            if (editor.onChange === null) throw new Error("The note editor did not render");
            editor.onChange(DRAFT);
        });
        await act(async () => {
            if (creation === "autosave") {
                window.dispatchEvent(new Event("beforeunload"));
            } else {
                if (editor.ensureNoteId === null) throw new Error("The note editor did not render");
                expect(await editor.ensureNoteId()).toBe(savedNote.id);
            }
        });
        expect(writesTo(requests, "/api/notes")).toHaveLength(1);
        expect(window.location.pathname).toBe(`/activity/notes/${savedNote.id}`);

        revoked = true;
        document.cookie = `connex_workspace=${HEALED}; path=/`;
        const refusedContent = `${DRAFT}, revised after creation`;
        await act(async () => {
            editor.onChange?.(refusedContent);
        });
        await act(async () => {
            window.dispatchEvent(new Event("beforeunload"));
        });
        await settle(500);

        const editorParts = { userId: USER, workspaceId: ORIGIN, formType: "noteEditor", scope: `note:${savedNote.id}` };
        expect(editor.value).toBe(refusedContent);
        expect(readDraft(editorParts, { version: 1 })?.data).toMatchObject({ content: refusedContent });
        expect(readDraft({ ...editorParts, scope: "new" }, { version: 1 })).toBeNull();
        expect(listFreshDrafts({ userId: USER, workspaceId: HEALED })).toEqual([]);
        expect(writesTo(requests, `/api/notes/${savedNote.id}`)).toMatchObject([
            { method: "PUT", workspace: String(ORIGIN), body: expect.stringContaining(refusedContent) },
        ]);

        await act(async () => root?.unmount());
        vi.mocked(toastInfo).mockClear();
        await renderInShell(
            <NoteEditorView note={null} currentUserId={USER} persons={[]} deals={[]} users={[]} returnPath="/activity/notes" />,
        );
        expect(toastInfo).not.toHaveBeenCalled();

        await act(async () => root?.unmount());
        await renderInShell(
            <NoteEditorView note={savedNote} currentUserId={USER} persons={[]} deals={[]} users={[]} returnPath="/activity/notes" />,
        );
        const offer = vi.mocked(toastInfo).mock.calls.find(([, options]) => options?.id === draftKey(editorParts));
        await act(async () => {
            const action = offer?.[1]?.action;
            if (typeof action !== "object" || action === null || !("onClick" in action)) {
                throw new Error("The saved note's kept edits were not offered");
            }
            Reflect.apply(action.onClick, undefined, []);
        });
        expect(editor.value).toBe(refusedContent);
        vi.mocked(toastError).mockClear();
        await act(async () => {
            window.dispatchEvent(new Event("beforeunload"));
            await vi.waitFor(() => expect(toastError).toHaveBeenCalledWith(
                "ApiErrors.title",
                { description: "ApiErrors.workspaceUnavailableNamed" },
            ));
        });
    });

    it.each([
        ["still a member", false],
        ["revoked", true],
    ])("is never written into the newly selected workspace when the origin is %s", async (_state, revoked) => {
        const requests = stubBackend(revoked);
        await renderInShell(
            <NoteEditorView note={null} currentUserId={USER} persons={[]} deals={[]} users={[]} returnPath="/activity/notes" />,
        );
        await act(async () => {
            if (editor.onChange === null) throw new Error("The note editor did not render");
            editor.onChange(DRAFT);
        });

        await healAndSwitch();
        await act(async () => {
            root?.render(shell(null));
        });
        await settle(100);

        const noteWrites = writesTo(requests, "/api/notes");
        expect(noteWrites.length).toBeGreaterThan(0);
        expect(noteWrites.every(({ workspace }) => workspace === String(ORIGIN))).toBe(true);
        expect(noteWrites.some(({ body }) => body.includes(DRAFT))).toBe(true);
        expect(listFreshDrafts({ userId: USER, workspaceId: HEALED })).toEqual([]);
    });

    it("keeps refused text as the editor's own draft beside a composer draft, and offers it back", async () => {
        stubBackend(true);
        const composerKey = draftKey({ userId: USER, workspaceId: ORIGIN, formType: "note", scope: "global" });
        writeDraft(composerKey, {
            version: 1,
            scope: "global",
            formType: "note",
            data: { content: COMPOSER_DRAFT, personId: null, dealId: null },
        });
        await renderInShell(
            <NoteEditorView note={null} currentUserId={USER} persons={[]} deals={[]} users={[]} returnPath="/activity/notes" />,
        );
        await act(async () => {
            if (editor.onChange === null) throw new Error("The note editor did not render");
            editor.onChange(DRAFT);
        });

        await healAndSwitch();
        await act(async () => {
            root?.render(shell(null));
        });
        await settle(600);

        const editorParts = { userId: USER, workspaceId: ORIGIN, formType: "noteEditor", scope: "new" };
        expect(readDraft({ userId: USER, workspaceId: ORIGIN, formType: "note", scope: "global" }, { version: 1 })?.data)
            .toMatchObject({ content: COMPOSER_DRAFT });
        expect(readDraft(editorParts, { version: 1 })?.data).toMatchObject({ content: DRAFT });
        expect(listFreshDrafts({ userId: USER, workspaceId: ORIGIN }).map(({ key }) => key).sort()).toEqual(
            [composerKey, draftKey(editorParts)].sort(),
        );
        expect(vi.mocked(toastError)).toHaveBeenCalledWith(
            "ApiErrors.title",
            { description: "ApiErrors.workspaceUnavailableNamed" },
        );

        await act(async () => root?.unmount());
        editor.value = null;
        await renderInShell(
            <NoteEditorView note={null} currentUserId={USER} persons={[]} deals={[]} users={[]} returnPath="/activity/notes" />,
        );
        const offer = vi.mocked(toastInfo).mock.calls.find(([, options]) => options?.id === draftKey(editorParts));
        await act(async () => {
            const action = offer?.[1]?.action;
            if (typeof action !== "object" || action === null || !("onClick" in action)) {
                throw new Error("The kept editor draft was not offered");
            }
            Reflect.apply(action.onClick, undefined, []);
        });

        expect(editor.value).toBe(DRAFT);
        expect(readDraft({ userId: USER, workspaceId: ORIGIN, formType: "note", scope: "global" }, { version: 1 })?.data)
            .toMatchObject({ content: COMPOSER_DRAFT });
    });
});

describe("creating a workspace while the workspace on screen has been revoked", () => {
    it("keeps the typed name and names the workspace that is gone", async () => {
        const requests = stubBackend(true);
        vi.stubGlobal("matchMedia", (query: string) => ({
            matches: false,
            media: query,
            onchange: null,
            addEventListener: () => undefined,
            removeEventListener: () => undefined,
            addListener: () => undefined,
            removeListener: () => undefined,
            dispatchEvent: () => false,
        }));
        document.cookie = `connex_workspace=${HEALED}; path=/`;
        await renderInShell(<NewWorkspaceDialog open onOpenChange={() => undefined} />);
        const input = document.getElementById("workspace-name");
        if (!(input instanceof HTMLInputElement)) throw new Error("No workspace name field");
        const setValue = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value")?.set;

        await act(async () => {
            setValue?.call(input, "Field team");
            input.dispatchEvent(new Event("input", { bubbles: true }));
        });
        await act(async () => {
            input.form?.requestSubmit();
        });
        await settle(100);

        expect(input.value).toBe("Field team");
        expect(writesTo(requests, "/api/workspaces")).toEqual([]);
        expect(vi.mocked(toastError)).toHaveBeenCalledWith(
            "ApiErrors.title",
            { description: "ApiErrors.workspaceUnavailableNamed" },
        );
    });
});

describe("a dashboard layout change still waiting to save when the workspace switches", () => {
    it("is saved to the workspace whose layout was changed", async () => {
        const requests = stubBackend(false);
        await renderInShell(<DashboardGrid initialWidgets={[]} nodes={NO_WIDGET_NODES} />);
        const button = (label: string) => {
            const match = [...document.querySelectorAll("button")].find((candidate) =>
                candidate.textContent?.trim() === label);
            if (!match) throw new Error(`No ${label} button`);
            return match;
        };

        await act(async () => {
            button("customize").click();
        });
        await act(async () => {
            button("reset").click();
        });
        await healAndSwitch();
        await settle(500);

        expect(writesTo(requests, "/api/dashboard-layout")).toEqual([
            { method: "DELETE", path: "/api/dashboard-layout", workspace: String(ORIGIN), body: "" },
        ]);
    });
});
