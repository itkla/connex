/** @vitest-environment jsdom */

import { Suspense } from "react";
import { act, fireEvent } from "@testing-library/react";
import { hydrateRoot } from "react-dom/client";
import { renderToString } from "react-dom/server";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import ContentShell from "@/app/components/ContentShell";
import Sidebar from "@/app/components/Sidebar";
import { NowProvider } from "@/app/hooks/useNow";
import { RecentRecordsProvider } from "@/app/hooks/useRecentRecords";
import { SidebarModeProvider } from "@/app/hooks/useSidebarMode";
import { TooltipProvider } from "@/components/ui/tooltip";
import { recentRecordsStorageKey, serializeRecents } from "@/app/lib/recentRecords";
import { serializeCollapsedSidebarSections, sidebarSectionStorageKey } from "@/app/lib/sidebarSections";
import type { User } from "@/app/lib/types";

const state = vi.hoisted(() => ({ pathname: "/dashboard", desktop: false }));

vi.mock("motion/react", async (importOriginal) => ({
    ...await importOriginal<typeof import("motion/react")>(),
    useReducedMotion: () => false,
}));
vi.mock("next/navigation", () => ({
    usePathname: () => state.pathname,
    useSearchParams: () => new URLSearchParams(),
    useRouter: () => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn() }),
}));
vi.mock("next-intl", () => ({
    useLocale: () => "en",
    useTimeZone: () => "UTC",
    useTranslations: () => (key: string) => key,
}));
vi.mock("@/app/hooks/useWorkspace", () => ({
    useWorkspace: () => ({
        activeWorkspaceId: 8,
        activeWorkspace: { id: 8, name: "Workspace" },
        workspaces: [{ id: 8, name: "Workspace" }],
        switching: false,
    }),
}));
vi.mock("@/app/hooks/useActions", () => ({
    useActions: () => ({ context: { user: { id: 7 } }, pendingIds: new Set(), run: vi.fn() }),
    useAvailableActions: () => [],
}));
vi.mock("@/app/hooks/useAccountStorageGeneration", () => ({ useAccountStorageGeneration: () => () => true }));
vi.mock("@/app/hooks/usePinnedViews", () => ({ usePinnedViews: () => ({ pins: [], status: "resolved" }) }));
vi.mock("@/app/hooks/useNotifications", () => ({
    useNotifications: () => ({ recipientId: 7, unread: 0, refreshUnread: vi.fn() }),
}));
vi.mock("@/app/components/notifications/useNotificationWorkspaceActions", () => ({
    useNotificationWorkspaceActions: () => ({}),
}));
vi.mock("@/app/components/account/PrivilegedMfaRouteGate", () => ({
    default: ({ children }: { children: React.ReactNode }) => children,
}));
vi.mock("@/app/components/ask-connex/AskConnexProvider", () => ({
    useAskConnex: () => ({ open: false, workspace: false, width: 420 }),
    useAskConnexMount: () => ({ registerDesktopRoot: vi.fn() }),
}));

const user = {
    id: 7, username: "member", displayName: "Member", email: "member@connex.test",
    createdAt: "2026-01-01T00:00:00Z", updatedAt: "2026-01-01T00:00:00Z",
    timezone: "UTC", locale: "en",
} satisfies User;

beforeEach(() => {
    localStorage.clear();
    state.desktop = false;
    vi.stubGlobal("matchMedia", (query: string) => ({
        media: query,
        matches: query === "(max-width: 767px)" ? !state.desktop : query.startsWith("(min-width:") && state.desktop,
        addEventListener: vi.fn(), removeEventListener: vi.fn(),
        addListener: vi.fn(), removeListener: vi.fn(),
    }));
    vi.stubGlobal("ResizeObserver", class {
        observe() {}
        disconnect() {}
    });
});

afterEach(() => {
    localStorage.clear();
    vi.unstubAllGlobals();
});

describe.each([
    "/dashboard", "/records/deals", "/records/deals/1", "/records/pipelines",
    "/records/contacts", "/records/companies", "/settings/workspace/crm",
])("authenticated shell hydration on %s", (pathname) => {
    it.each([false, true])("retains navigation when a suspended sidebar adopts browser preferences (desktop: %s)", async (desktop) => {
        state.pathname = pathname;
        let pause = false;
        let release: (() => void) | undefined;
        const blocked = new Promise<void>((resolve) => { release = resolve; });
        function Navigation() {
            if (pause) throw blocked;
            return <Sidebar user={user} navAccess={{
                goals: true, auditLog: true, captureReviews: "enabled", campaigns: true,
                workflows: true, diagnostics: true,
            }} />;
        }
        const view = <TooltipProvider><NowProvider value={Date.UTC(2026, 10, 1)}>
            <RecentRecordsProvider><SidebarModeProvider>
                <ContentShell sidebar={<Suspense fallback={null}><Navigation /></Suspense>}><h1>Page</h1></ContentShell>
            </SidebarModeProvider></RecentRecordsProvider>
        </NowProvider></TooltipProvider>;
        const container = document.createElement("div");
        const browserWindow = window;
        const browserDocument = document;
        try {
            vi.stubGlobal("window", undefined);
            vi.stubGlobal("document", undefined);
            container.innerHTML = renderToString(view);
        } finally {
            vi.stubGlobal("window", browserWindow);
            vi.stubGlobal("document", browserDocument);
        }
        document.body.append(container);
        expect(container.querySelector("aside nav a[href='/dashboard']")).not.toBeNull();
        expect(container.querySelector("template")).toBeNull();
        const main = container.querySelector("main");
        const sidebar = container.querySelector("aside");
        state.desktop = desktop;
        localStorage.setItem("connex:sidebar-mode:7:8", "rail");
        localStorage.setItem(sidebarSectionStorageKey(7, 8), serializeCollapsedSidebarSections(new Set(["records"])));
        localStorage.setItem(recentRecordsStorageKey(7, 8), serializeRecents([
            { t: "person", id: 42, label: "Stored contact", ts: 1 },
        ]));
        const errors: unknown[] = [];
        pause = true;
        let root: ReturnType<typeof hydrateRoot> | undefined;
        try {
            await act(async () => {
                root = hydrateRoot(container, view, { onRecoverableError: (error) => errors.push(error) });
            });
            await act(async () => {
                pause = false;
                release?.();
                await blocked;
            });
            expect(errors.map(String)).toEqual([]);
            expect(container.querySelector("main")).toBe(main);
            expect(container.querySelector("aside")).toBe(sidebar);
            expect(container.querySelector("aside nav a[href='/records/contacts/42']")).not.toBeNull();
            expect(sidebar?.classList.contains(desktop ? "w-16" : "w-64")).toBe(true);
            const toggle = container.querySelector("button[aria-label='expandSidebar']");
            if (!(toggle instanceof HTMLButtonElement)) throw new Error("Sidebar toggle was not rendered");
            fireEvent.click(toggle);
            expect(sidebar?.classList.contains("w-64")).toBe(true);
            expect(localStorage.getItem("connex:sidebar-mode:7:8")).toBe("expanded");
        } finally {
            await act(async () => root?.unmount());
            container.remove();
        }
    });
});
