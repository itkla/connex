/** @vitest-environment jsdom */
import { act, type ReactNode } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import NoteEditorView from "@/app/components/activity/notes/NoteEditorView";
import DashboardGrid from "@/app/components/dashboard/customize/DashboardGrid";
import { useWorkspace, WorkspaceProvider } from "@/app/hooks/useWorkspace";
import { draftKey, listFreshDrafts, readDraft } from "@/app/lib/formDrafts";
import { renderedWorkspaceId } from "@/app/lib/renderedWorkspace";
import { toastError } from "@/app/lib/toast";
import type { DashboardWidgetType, Workspace } from "@/app/lib/types";

const editor = vi.hoisted<{ onChange: ((value: string) => void) | null }>(() => ({ onChange: null }));

vi.mock("next/navigation", () => ({
    useRouter: () => ({ refresh: vi.fn(), replace: vi.fn(), push: vi.fn() }),
}));

vi.mock("next-intl", () => ({
    useLocale: () => "en",
    useTranslations: () => Object.assign((key: string) => key, { has: () => false }),
}));

vi.mock("next/dynamic", () => ({
    default: () => function RichNoteEditor({ onChange }: { onChange: (value: string) => void }) {
        editor.onChange = onChange;
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

const HEALED = 5;
const ORIGIN = 9;
const USER = 7;
const DRAFT = "Board pricing plan for the renewal";

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
function stubBackend(originRevoked: boolean): Recorded[] {
    const requests: Recorded[] = [];
    vi.stubGlobal("fetch", vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
        const url = new URL(input instanceof Request ? input.url : String(input), "http://localhost");
        const method = init?.method ?? "GET";
        if (url.pathname === "/api/auth/csrf") {
            return json({ token: "csrf-token", headerName: "X-XSRF-TOKEN", requestIdentity: "identity" });
        }
        const workspaceHeader = new Headers(init?.headers).get("X-Workspace-Id");
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
        if (originRevoked && workspaceHeader === String(ORIGIN)) {
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
    onRender(useWorkspace());
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
    document.cookie = `connex_workspace=${ORIGIN}; path=/`;
    editor.onChange = null;
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

    it("keeps refused text as the origin workspace's draft and reports the refusal", async () => {
        stubBackend(true);
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

        const kept = readDraft(
            { userId: USER, workspaceId: ORIGIN, formType: "note", scope: "global" },
            { version: 1 },
        );
        expect(kept?.key).toBe(draftKey({ userId: USER, workspaceId: ORIGIN, formType: "note", scope: "global" }));
        expect(kept?.data).toMatchObject({ content: DRAFT });
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
