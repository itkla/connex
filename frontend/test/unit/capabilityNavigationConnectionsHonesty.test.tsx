import {
    createElement,
    isValidElement,
    type AnchorHTMLAttributes,
    type ComponentProps,
    type PropsWithChildren,
    type ReactElement,
    type ReactNode,
} from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { afterEach, describe, expect, it, vi } from "vitest";

import ConnectedAccountsPage from "@/app/(app)/settings/personal/connected-accounts/page";
import CaptureReviewsPage from "@/app/(app)/account/connections/reviews/page";
import AppLayout from "@/app/(app)/layout";
import ConnectionsPanel from "@/app/components/account/ConnectionsPanel";
import PersonalConnectedAccounts from "@/app/components/settings/PersonalConnectedAccounts";
import CapabilityUnavailablePage from "@/app/components/CapabilityUnavailablePage";
import ContentShell from "@/app/components/ContentShell";
import Sidebar from "@/app/components/Sidebar";
import NavActionsBridge from "@/app/components/actions/NavActionsBridge";
import type { CapabilityAvailability } from "@/app/lib/capabilityAvailability";
import type { NavAccess } from "@/app/lib/navAccess";
import type { InstanceCapabilities, MyWorkspaces, User, Workspace } from "@/app/lib/types";

const { navActionIdsState, redirectMock, routerRefreshMock } = vi.hoisted(() => ({
    navActionIdsState: { ids: Array<string>() },
    redirectMock: vi.fn((destination: string): never => {
        throw new Error(`redirect:${destination}`);
    }),
    routerRefreshMock: vi.fn(),
}));

vi.mock("next/headers", () => ({
    headers: () => Promise.resolve(new Headers({
        cookie: "JSESSIONID=session; connex_workspace=7",
        "x-pathname": "/dashboard",
    })),
}));

vi.mock("next/link", async () => {
    const React = await import("react");
    type LinkProps = PropsWithChildren<AnchorHTMLAttributes<HTMLAnchorElement> & { href: string }>;
    return {
        default: ({ children, href, ...props }: LinkProps) =>
            React.createElement("a", { ...props, href }, children),
    };
});

vi.mock("next/navigation", () => ({
    redirect: redirectMock,
    usePathname: () => "/dashboard",
    useRouter: () => ({
        push: vi.fn(),
        refresh: routerRefreshMock,
        replace: vi.fn(),
    }),
    useSearchParams: () => new URLSearchParams(),
}));

vi.mock("next-intl", () => ({
    useLocale: () => "en",
    useTranslations: (namespace: string) => (key: string) => `${namespace}.${key}`,
}));

vi.mock("@/app/hooks/useActions", async () => {
    const React = await import("react");
    return {
        ActionProvider: ({ children }: PropsWithChildren) =>
            React.createElement(React.Fragment, null, children),
        useRegisterActions: (actions: readonly { id: string }[]) => {
            navActionIdsState.ids = actions.map((action) => action.id);
        },
    };
});

vi.mock("motion/react", async () => {
    const React = await import("react");
    type MotionProps<T extends "div" | "span" | "ul"> = ComponentProps<T> & {
        animate?: unknown;
        exit?: unknown;
        initial?: unknown;
        layout?: unknown;
        layoutId?: string;
        transition?: unknown;
    };
    return {
        AnimatePresence: ({ children }: PropsWithChildren) =>
            React.createElement(React.Fragment, null, children),
        motion: {
            div: ({ children, ...props }: MotionProps<"div">) =>
                React.createElement("div", { className: props.className }, children),
            span: ({ children, ...props }: MotionProps<"span">) =>
                React.createElement("span", { className: props.className }, children),
            ul: ({ children, ...props }: MotionProps<"ul">) =>
                React.createElement("ul", { className: props.className }, children),
        },
        useReducedMotion: () => true,
    };
});

vi.mock("next-themes", () => ({
    useTheme: () => ({ setTheme: vi.fn(), theme: "system" }),
}));

vi.mock("radix-ui", async () => {
    const React = await import("react");
    const Wrapper = ({ children }: PropsWithChildren) =>
        React.createElement(React.Fragment, null, children);
    return {
        DropdownMenu: {
            Content: Wrapper,
            Item: Wrapper,
            Label: Wrapper,
            Portal: Wrapper,
            Root: Wrapper,
            Separator: Wrapper,
            Trigger: Wrapper,
        },
    };
});

vi.mock("@/components/ui/dropdown-menu", async () => {
    const React = await import("react");
    const Wrapper = ({ children }: PropsWithChildren) =>
        React.createElement(React.Fragment, null, children);
    return {
        DropdownMenuItem: Wrapper,
        DropdownMenuPortal: Wrapper,
        DropdownMenuRadioGroup: Wrapper,
        DropdownMenuRadioItem: Wrapper,
        DropdownMenuSub: Wrapper,
        DropdownMenuSubContent: Wrapper,
        DropdownMenuSubTrigger: Wrapper,
    };
});

vi.mock("@/components/ui/tooltip", async () => {
    const React = await import("react");
    const Wrapper = ({ children }: PropsWithChildren) =>
        React.createElement(React.Fragment, null, children);
    return {
        Tooltip: Wrapper,
        TooltipContent: Wrapper,
        TooltipTrigger: Wrapper,
    };
});

vi.mock("@/app/components/WorkspaceSwitcher", () => ({ default: () => null }));
vi.mock("@/app/components/notifications/NotificationBell", () => ({ default: () => null }));
vi.mock("@/app/components/actions/QuickCreateLauncher", () => ({ default: () => null }));
vi.mock("@/app/components/records/users/UserAvatar", () => ({ default: () => null }));

vi.mock("@/app/hooks/useWorkspace", async () => {
    const React = await import("react");
    return {
        WorkspaceProvider: ({ children }: PropsWithChildren) =>
            React.createElement(React.Fragment, null, children),
        useWorkspace: () => ({ activeWorkspace: null, activeWorkspaceId: 7 }),
    };
});

vi.mock("@/app/hooks/useSidebarSections", () => ({
    useSidebarSections: () => ({
        isCollapsed: () => false,
        setCollapsed: vi.fn(),
    }),
}));

vi.mock("@/app/hooks/usePinnedViews", async () => {
    const React = await import("react");
    return {
        PinnedViewsProvider: ({ children }: PropsWithChildren) =>
            React.createElement(React.Fragment, null, children),
        usePinnedViews: () => ({ pins: [], reload: vi.fn(), status: "resolved" }),
    };
});

vi.mock("@/app/hooks/useRecentRecords", async () => {
    const React = await import("react");
    return {
        RecentRecordsProvider: ({ children }: PropsWithChildren) =>
            React.createElement(React.Fragment, null, children),
        useRecentRecords: () => ({ recents: [] }),
    };
});

vi.mock("@/app/hooks/useSidebarMode", async () => {
    const React = await import("react");
    return {
        SidebarModeProvider: ({ children }: PropsWithChildren) =>
            React.createElement(React.Fragment, null, children),
        useSidebarMode: () => ({ mode: "expanded" }),
    };
});

vi.mock("@/app/hooks/useIsMobile", () => ({
    useIsMobile: () => false,
}));

vi.mock("@/app/hooks/useNotifications", async () => {
    const React = await import("react");
    return {
        NotificationProvider: ({ children }: PropsWithChildren) =>
            React.createElement(React.Fragment, null, children),
        useNotifications: () => ({ unread: 0 }),
    };
});

vi.mock("@/app/hooks/usePasskeyStepUpError", () => ({
    usePasskeyStepUpErrorHandler: () => () => false,
}));

const USER = {
    id: 9,
    username: "member",
    displayName: "Member",
    email: "member@connex.test",
    createdAt: "2026-01-01T00:00:00Z",
    updatedAt: "2026-01-01T00:00:00Z",
    timezone: "UTC",
    locale: "en",
} satisfies User;

const WORKSPACE = {
    id: 7,
    name: "Workspace",
    slug: "workspace",
    timezone: "UTC",
    identityVersion: 1,
    role: "member",
    orgId: 3,
    orgName: "Organization",
    orgIdentityVersion: 1,
    orgRole: null,
} satisfies Workspace;

const WORKSPACES = {
    workspaces: [WORKSPACE],
    activeWorkspaceId: WORKSPACE.id,
} satisfies MyWorkspaces;

const DISABLED_CAPABILITIES = {
    sso: false,
    socialLogin: { google: false, microsoft: false },
    connectedAccounts: { google: false, microsoft: false },
    connectedCapture: { google: false, microsoft: false },
    mailManaged: false,
    businessCardScanning: false,
    businessCardImport: false,
    campaignDelivery: false,
} satisfies InstanceCapabilities;

const CAPTURE_ENABLED_CAPABILITIES = {
    ...DISABLED_CAPABILITIES,
    connectedCapture: { google: true, microsoft: false },
} satisfies InstanceCapabilities;

const BASE_NAV_ACCESS = {
    goals: false,
    auditLog: false,
    captureReviews: "disabled",
    campaigns: false,
    workflows: false,
    diagnostics: false,
} satisfies NavAccess;

function json(body: unknown): Response {
    return new Response(JSON.stringify(body), {
        status: 200,
        headers: { "Content-Type": "application/json" },
    });
}

function stubAppReads(capabilities: InstanceCapabilities | null) {
    vi.stubGlobal("fetch", vi.fn((input: string | URL | Request) => {
        const url = String(input);
        if (url.endsWith("/api/auth/me")) return Promise.resolve(json(USER));
        if (url.endsWith("/api/workspaces")) return Promise.resolve(json(WORKSPACES));
        if (url.endsWith("/api/permissions/effective")) return Promise.resolve(json([]));
        if (url.endsWith("/api/capabilities")) {
            return Promise.resolve(capabilities === null
                ? new Response("", { status: 503 })
                : json(capabilities));
        }
        return Promise.resolve(new Response("", { status: 404 }));
    }));
}

function hasChildren(value: unknown): value is { children?: ReactNode } {
    return typeof value === "object" && value !== null && "children" in value;
}

function findByType(node: ReactNode, type: unknown): ReactElement | null {
    if (Array.isArray(node)) {
        for (const child of node) {
            const found = findByType(child, type);
            if (found !== null) return found;
        }
        return null;
    }
    if (!isValidElement(node)) return null;
    if (node.type === type) return node;
    return hasChildren(node.props) ? findByType(node.props.children, type) : null;
}

function hasContentShellSidebar(value: unknown): value is { sidebar: ReactNode } {
    return typeof value === "object" && value !== null && "sidebar" in value;
}

function hasSidebarContract(value: unknown): value is { navAccess: NavAccess; user: User } {
    return typeof value === "object"
        && value !== null
        && "navAccess" in value
        && typeof value.navAccess === "object"
        && value.navAccess !== null
        && "user" in value;
}

function hasConnectionsContract(
    value: unknown,
): value is ComponentProps<typeof ConnectionsPanel> {
    return typeof value === "object"
        && value !== null
        && "capabilitiesAvailability" in value
        && (value.capabilitiesAvailability === "enabled"
            || value.capabilitiesAvailability === "disabled"
            || value.capabilitiesAvailability === "unavailable")
        && "capabilities" in value
        && typeof value.capabilities === "object"
        && value.capabilities !== null
        && "effectivePermissions" in value
        && Array.isArray(value.effectivePermissions)
        && "permissionsStatus" in value
        && (value.permissionsStatus === "resolved" || value.permissionsStatus === "unavailable");
}

function sidebarFromLayout(rendered: ReactNode): ReactElement {
    const contentShell = findByType(rendered, ContentShell);
    if (contentShell === null || !hasContentShellSidebar(contentShell.props)) {
        throw new Error("App layout did not render its content shell");
    }
    const sidebar = findByType(contentShell.props.sidebar, Sidebar);
    if (sidebar === null || !hasSidebarContract(sidebar.props)) {
        throw new Error("App layout did not render the expected sidebar contract");
    }
    return sidebar;
}

function captureRegisteredNavigationActions(captureReviews: CapabilityAvailability): string[] {
    navActionIdsState.ids = [];
    renderToStaticMarkup(createElement(NavActionsBridge, {
        navAccess: { ...BASE_NAV_ACCESS, captureReviews },
    }));
    return navActionIdsState.ids;
}

afterEach(() => {
    vi.unstubAllGlobals();
    redirectMock.mockClear();
    routerRefreshMock.mockClear();
});

describe("app-shell capability navigation honesty", () => {
    it("shows capture reviews without an unavailable marker when capture resolves enabled", async () => {
        stubAppReads(CAPTURE_ENABLED_CAPABILITIES);

        const rendered = await AppLayout({ children: createElement("p", null, "dashboard") });
        const sidebar = sidebarFromLayout(rendered);
        if (!hasSidebarContract(sidebar.props)) throw new Error("Sidebar contract changed");
        const html = renderToStaticMarkup(sidebar);

        expect(sidebar.props.navAccess.captureReviews).toBe("enabled");
        expect(html).toContain("/account/connections/reviews");
        expect(html).not.toContain("CapabilityUnavailable.title");
    });

    it("keeps the capture-review destination visible and marks it unavailable after lookup failure", async () => {
        stubAppReads(null);

        const rendered = await AppLayout({ children: createElement("p", null, "dashboard") });
        const sidebar = sidebarFromLayout(rendered);
        if (!hasSidebarContract(sidebar.props)) throw new Error("Sidebar contract changed");
        const html = renderToStaticMarkup(sidebar);

        expect(sidebar.props.navAccess.captureReviews).toBe("unavailable");
        expect(html).toContain("/account/connections/reviews");
        expect(html).toContain("CapabilityUnavailable.title");
        expect(html).toContain("CommonSidebar.navDashboard");
    });

    it("hides capture reviews without an unavailable marker when capture resolves disabled", async () => {
        stubAppReads(DISABLED_CAPABILITIES);

        const rendered = await AppLayout({ children: createElement("p", null, "dashboard") });
        const sidebar = sidebarFromLayout(rendered);
        if (!hasSidebarContract(sidebar.props)) throw new Error("Sidebar contract changed");
        const html = renderToStaticMarkup(sidebar);

        expect(sidebar.props.navAccess.captureReviews).toBe("disabled");
        expect(html).not.toContain("/account/connections/reviews");
        expect(html).not.toContain("CapabilityUnavailable.title");
        expect(html).toContain("CommonSidebar.navDashboard");
    });
});

describe("command navigation capability honesty", () => {
    it.each([
        ["enabled", true],
        ["disabled", false],
        ["unavailable", true],
    ] as const)("registers capture reviews for %s availability: %s", (availability, expected) => {
        const actionIds = captureRegisteredNavigationActions(availability);

        expect(actionIds.includes("navigate.capture-reviews")).toBe(expected);
    });
});

/**
 * The capability contract the route hands down, and the panel built from it.
 *
 * #1340 WS4.2 moved this surface to `/settings/personal/connected-accounts`, where the route hands
 * its four resolved values to the page component and that component passes them through to the
 * shipped panel. The route's job is still exactly what it was — resolve the capabilities and the
 * effective permissions honestly, and refuse to canonicalize a deep link on a lookup that did not
 * answer — so the assertions are unchanged; what they read them from is one element further out.
 *
 * The panel is rendered from those same props rather than from the page component, because the
 * page component is where the composition lives and the honesty being tested is the panel's.
 */
function connectedAccountsContract(
    rendered: ReactNode,
): ComponentProps<typeof ConnectionsPanel> {
    const composed = findByType(rendered, PersonalConnectedAccounts);
    if (composed === null || !hasConnectionsContract(composed.props)) {
        throw new Error("Connected accounts page did not render the expected capability contract");
    }
    return composed.props;
}

describe("connected accounts page capability honesty", () => {
    it("preserves a provider deep link when the capability lookup fails", async () => {
        stubAppReads(null);

        const rendered = await ConnectedAccountsPage({
            searchParams: Promise.resolve({ provider: "google" }),
        });

        expect(findByType(rendered, PersonalConnectedAccounts)).not.toBeNull();
        expect(redirectMock).not.toHaveBeenCalled();
    });

    it("preserves a workspace-policy deep link when the capability lookup fails", async () => {
        stubAppReads(null);

        const rendered = await ConnectedAccountsPage({
            searchParams: Promise.resolve({
                provider: "google",
                panel: "workspace-policy",
            }),
        });

        expect(findByType(rendered, PersonalConnectedAccounts)).not.toBeNull();
        expect(redirectMock).not.toHaveBeenCalled();
    });

    it("keeps the connections page mounted and renders a retryable unavailable section after lookup failure", async () => {
        stubAppReads(null);

        const rendered = await ConnectedAccountsPage({ searchParams: Promise.resolve({}) });
        const composed = connectedAccountsContract(rendered);
        const html = renderToStaticMarkup(<ConnectionsPanel {...composed} />);

        expect(composed.capabilitiesAvailability).toBe("unavailable");
        expect(html).toContain("AccountConnections.title");
        expect(html).toContain("CapabilityUnavailable.title");
        expect(html).toContain("CapabilityUnavailable.retry");
    });

    it("keeps a resolved no-provider deployment distinct from lookup failure", async () => {
        stubAppReads(DISABLED_CAPABILITIES);

        const rendered = await ConnectedAccountsPage({ searchParams: Promise.resolve({}) });
        const composed = connectedAccountsContract(rendered);
        const html = renderToStaticMarkup(<ConnectionsPanel {...composed} />);

        expect(composed.capabilitiesAvailability).toBe("disabled");
        expect(html).toContain("AccountConnections.title");
        expect(html).not.toContain("CapabilityUnavailable.title");
    });
});

describe("capture-review route capability honesty", () => {
    it("renders the retryable capability page when provider availability cannot be checked", async () => {
        stubAppReads(null);

        const rendered = await CaptureReviewsPage();

        expect(isValidElement(rendered) ? rendered.type : null).toBe(CapabilityUnavailablePage);
        expect(redirectMock).not.toHaveBeenCalled();
    });

    it("redirects to connections only when every capture provider resolves disabled", async () => {
        stubAppReads(DISABLED_CAPABILITIES);

        await expect(CaptureReviewsPage()).rejects.toThrow("redirect:/account/connections");

        expect(redirectMock).toHaveBeenCalledWith("/account/connections");
    });
});
