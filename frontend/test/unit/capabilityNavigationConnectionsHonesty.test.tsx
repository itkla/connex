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

import AccountConnectionsPage from "@/app/(app)/account/connections/page";
import CaptureReviewsPage from "@/app/(app)/account/connections/reviews/page";
import AppLayout from "@/app/(app)/layout";
import ConnectionsPanel from "@/app/components/account/ConnectionsPanel";
import CapabilityUnavailablePage from "@/app/components/CapabilityUnavailablePage";
import ContentShell from "@/app/components/ContentShell";
import Sidebar from "@/app/components/Sidebar";
import type { CapabilityAvailability } from "@/app/lib/capabilityAvailability";
import type { NavAccess } from "@/app/lib/navAccess";
import type { InstanceCapabilities, MyWorkspaces, User, Workspace } from "@/app/lib/types";

const { redirectMock, routerRefreshMock } = vi.hoisted(() => ({
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

function hasConnectionsContract(value: unknown): value is {
    capabilitiesAvailability: CapabilityAvailability;
} {
    return typeof value === "object"
        && value !== null
        && "capabilitiesAvailability" in value
        && (value.capabilitiesAvailability === "enabled"
            || value.capabilitiesAvailability === "disabled"
            || value.capabilitiesAvailability === "unavailable");
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

afterEach(() => {
    vi.unstubAllGlobals();
    redirectMock.mockClear();
    routerRefreshMock.mockClear();
});

describe("app-shell capability navigation honesty", () => {
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

describe("connections page capability honesty", () => {
    it("keeps the connections page mounted and renders a retryable unavailable section after lookup failure", async () => {
        stubAppReads(null);

        const rendered = await AccountConnectionsPage({ searchParams: Promise.resolve({}) });
        const panel = findByType(rendered, ConnectionsPanel);
        if (panel === null || !hasConnectionsContract(panel.props)) {
            throw new Error("Connections page did not render the expected capability contract");
        }
        const html = renderToStaticMarkup(panel);

        expect(panel.props.capabilitiesAvailability).toBe("unavailable");
        expect(html).toContain("AccountConnections.title");
        expect(html).toContain("CapabilityUnavailable.title");
        expect(html).toContain("CapabilityUnavailable.retry");
    });

    it("keeps a resolved no-provider deployment distinct from lookup failure", async () => {
        stubAppReads(DISABLED_CAPABILITIES);

        const rendered = await AccountConnectionsPage({ searchParams: Promise.resolve({}) });
        const panel = findByType(rendered, ConnectionsPanel);
        if (panel === null || !hasConnectionsContract(panel.props)) {
            throw new Error("Connections page did not render the expected capability contract");
        }
        const html = renderToStaticMarkup(panel);

        expect(panel.props.capabilitiesAvailability).toBe("disabled");
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
