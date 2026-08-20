import {
    act,
    createElement,
    isValidElement,
    type AnchorHTMLAttributes,
    type ComponentProps,
    type PropsWithChildren,
    type ReactElement,
    type ReactNode,
} from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import OrganizationLayout from "@/app/(app)/organization/layout";
import OrgSsoPage from "@/app/(app)/organization/sso/page";
import CapabilityUnavailablePage from "@/app/components/CapabilityUnavailablePage";
import { NoAccessCard } from "@/app/components/organization/OrgPrimitives";
import OrgTabs from "@/app/components/organization/OrgTabs";
import OrganizationWorkspaceGuard from "@/app/components/organization/OrganizationWorkspaceGuard";
import SettingsAvailabilityNotice from "@/app/components/settings/SettingsAvailabilityNotice";
import SsoPanel from "@/app/components/settings/SsoPanel";
import WorkspaceUnavailablePage from "@/app/components/WorkspaceUnavailablePage";
import type { MyWorkspaces, Workspace } from "@/app/lib/types";

const {
    activeWorkspaceState,
    buttonActionState,
    capabilityResultMock,
    redirectMock,
    retrySelectionRecoveryMock,
    routerRefreshMock,
    workspaceResultMock,
    workspaceUnavailableState,
} = vi.hoisted(() => ({
    activeWorkspaceState: { id: 7 as number | null },
    buttonActionState: { onClick: (): void => {} },
    capabilityResultMock: vi.fn(),
    redirectMock: vi.fn(),
    retrySelectionRecoveryMock: vi.fn(async () => {}),
    routerRefreshMock: vi.fn(),
    workspaceResultMock: vi.fn(),
    workspaceUnavailableState: { onRetry: async (): Promise<void> => {} },
}));

vi.mock("next/headers", () => ({
    headers: () => Promise.resolve(new Headers({
        cookie: "JSESSIONID=session; connex_workspace=7",
    })),
}));

vi.mock("next/link", async () => {
    const React = await import("react");
    type LinkProps = PropsWithChildren<AnchorHTMLAttributes<HTMLAnchorElement> & { href: string }>;
    return {
        default: ({ children, href, ...props }: LinkProps) => React.createElement("a", { ...props, href }, children),
    };
});

vi.mock("next/navigation", () => ({
    redirect: redirectMock,
    usePathname: () => "/organization/members",
    useRouter: () => ({ refresh: routerRefreshMock }),
}));

vi.mock("next-intl", () => ({
    useTranslations: () => (key: string) => key,
}));

vi.mock("next-intl/server", () => ({
    getTranslations: () => Promise.resolve((key: string) => key),
}));

vi.mock("motion/react", async () => {
    const React = await import("react");
    type MotionSpanProps = ComponentProps<"span"> & {
        layoutId?: string;
        transition?: unknown;
    };
    return {
        motion: {
            span: ({ children, className }: MotionSpanProps) =>
                React.createElement("span", { className }, children),
        },
        useReducedMotion: () => true,
    };
});

vi.mock("@/components/ui/button", async () => {
    const React = await import("react");
    type ButtonProps = PropsWithChildren<{
        disabled?: boolean;
        onClick?: () => void;
    }>;
    return {
        Button: ({ children, disabled, onClick }: ButtonProps) => {
            buttonActionState.onClick = onClick ?? (() => undefined);
            const content = React.Children.toArray(children);
            return React.createElement("button", { disabled }, content.at(-1));
        },
    };
});

vi.mock("@/app/components/PermissionsUnavailable", async () => {
    const React = await import("react");
    return {
        default: ({ action, body, title }: { action?: ReactNode; body: string; title: string }) =>
            React.createElement(
                "section",
                null,
                React.createElement("h2", null, title),
                React.createElement("p", null, body),
                action,
            ),
    };
});

vi.mock("@/app/lib/api", () => ({
    getCapabilitiesResultFromCookie: capabilityResultMock,
    getMyWorkspacesResultFromCookie: workspaceResultMock,
}));

vi.mock("@/app/hooks/useWorkspace", () => ({
    useWorkspace: () => ({
        activeWorkspaceId: activeWorkspaceState.id,
        retrySelectionRecovery: retrySelectionRecoveryMock,
    }),
}));

vi.mock("@/app/components/WorkspaceSelectionUnavailable", async () => {
    const React = await import("react");
    return {
        default: ({ onRetry }: { onRetry: () => Promise<void> }) => {
            workspaceUnavailableState.onRetry = onRetry;
            return React.createElement("p", null, "workspace selection unavailable");
        },
    };
});

const MEMBER_WORKSPACE = {
    id: 7,
    name: "Member workspace",
    slug: "member-workspace",
    timezone: "Asia/Tokyo",
    identityVersion: 1,
    role: "member",
    orgId: 3,
    orgName: "Connex test",
    orgIdentityVersion: 1,
    orgRole: null,
} satisfies Workspace;

const OTHER_ORG_ADMIN_WORKSPACE = {
    ...MEMBER_WORKSPACE,
    id: 8,
    name: "Other organization workspace",
    slug: "other-organization-workspace",
    orgId: 4,
    orgName: "Other organization",
    orgRole: "admin",
} satisfies Workspace;

const ORG_ADMIN_ROUTES = [
    "/organization/overview",
    "/organization/members",
    "/organization/allowed-domains",
    "/organization/sso",
    "/organization/ai",
    "/organization/data-requests",
    "/organization/audit",
    "/organization/diagnostics",
] as const;

function workspaceSnapshot(workspace: Workspace): MyWorkspaces {
    return { workspaces: [workspace], activeWorkspaceId: workspace.id };
}

function installMinimalDocument(): HTMLElement {
    class HtmlIFrameElement {}

    const documentTarget = {
        nodeType: 9,
        activeElement: null,
        addEventListener: vi.fn(),
        removeEventListener: vi.fn(),
        createElement: vi.fn(() => containerTarget),
    };
    const windowTarget = {
        document: documentTarget,
        event: undefined,
        HTMLIFrameElement: HtmlIFrameElement,
        addEventListener: vi.fn(),
        removeEventListener: vi.fn(),
    };
    const containerTarget = {
        nodeType: 1,
        tagName: "DIV",
        nodeName: "DIV",
        namespaceURI: "http://www.w3.org/1999/xhtml",
        ownerDocument: documentTarget,
        firstChild: null,
        lastChild: null,
        parentNode: null,
        textContent: "",
        style: {},
        addEventListener: vi.fn(),
        removeEventListener: vi.fn(),
        appendChild: vi.fn(),
        insertBefore: vi.fn(),
        removeChild: vi.fn(),
        setAttribute: vi.fn(),
        removeAttribute: vi.fn(),
    };
    Object.assign(documentTarget, {
        defaultView: windowTarget,
        documentElement: containerTarget,
        body: containerTarget,
    });
    vi.stubGlobal("window", windowTarget);
    vi.stubGlobal("document", documentTarget);
    vi.stubGlobal("IS_REACT_ACT_ENVIRONMENT", true);
    return document.createElement("div");
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

function containsText(node: ReactNode, text: string): boolean {
    if (node === text) return true;
    if (Array.isArray(node)) return node.some((child) => containsText(child, text));
    if (!isValidElement(node) || !hasChildren(node.props)) return false;
    return containsText(node.props.children, text);
}

function hasAvailabilityState(value: unknown): value is { state: string } {
    return typeof value === "object" && value !== null && "state" in value
        && typeof value.state === "string";
}

function isOrgTabsProps(value: unknown): value is {
    isOrgAdmin: boolean;
    ssoAvailability: "enabled" | "disabled" | "unavailable";
} {
    return typeof value === "object"
        && value !== null
        && "isOrgAdmin" in value
        && typeof value.isOrgAdmin === "boolean"
        && "ssoAvailability" in value
        && (value.ssoAvailability === "enabled"
            || value.ssoAvailability === "disabled"
            || value.ssoAvailability === "unavailable");
}

beforeEach(() => {
    activeWorkspaceState.id = MEMBER_WORKSPACE.id;
    buttonActionState.onClick = () => undefined;
    capabilityResultMock.mockReset().mockResolvedValue({ ok: true, data: { sso: true } });
    redirectMock.mockReset();
    retrySelectionRecoveryMock.mockReset().mockImplementation(async () => {
        activeWorkspaceState.id = MEMBER_WORKSPACE.id;
    });
    routerRefreshMock.mockReset();
    workspaceResultMock.mockReset();
    workspaceUnavailableState.onRetry = async () => {};
});

afterEach(() => {
    vi.unstubAllGlobals();
});

describe("organization navigation authority", () => {
    it("renders no organization admin tab for a non-admin", () => {
        const html = renderToStaticMarkup(createElement(OrgTabs, {
            isOrgAdmin: false,
            ssoAvailability: "enabled",
        }));

        for (const route of ORG_ADMIN_ROUTES) expect(html).not.toContain(route);
    });

    it("keeps organization navigation available to an administrator", () => {
        const html = renderToStaticMarkup(createElement(OrgTabs, {
            isOrgAdmin: true,
            ssoAvailability: "enabled",
        }));

        for (const route of ORG_ADMIN_ROUTES) expect(html).toContain(route);
    });

    it("hides the SSO tab only after availability resolves as disabled", () => {
        const html = renderToStaticMarkup(createElement(OrgTabs, {
            isOrgAdmin: true,
            ssoAvailability: "disabled",
        }));

        expect(html).not.toContain("/organization/sso");
        expect(html).not.toContain("tabSsoAvailabilityUnknown");
    });

    it("gates the section on the server-provided active-workspace organization role", async () => {
        workspaceResultMock.mockResolvedValue({
            ok: true,
            data: workspaceSnapshot(MEMBER_WORKSPACE),
        });

        const rendered = await OrganizationLayout({ children: createElement("p", null, "admin content") });
        const tabs = findByType(rendered, OrgTabs);

        expect(workspaceResultMock).toHaveBeenCalledWith("JSESSIONID=session; connex_workspace=7");
        expect(capabilityResultMock).toHaveBeenCalledWith("JSESSIONID=session; connex_workspace=7");
        expect(isValidElement(rendered) ? rendered.type : null).toBe(OrganizationWorkspaceGuard);
        expect(findByType(rendered, NoAccessCard)).not.toBeNull();
        expect(tabs !== null && isOrgTabsProps(tabs.props) ? tabs.props.isOrgAdmin : null).toBe(false);
        expect(containsText(rendered, "admin content")).toBe(false);
    });

    it("starts capability resolution without waiting for the workspace lookup", async () => {
        let resolveWorkspace: (result: { ok: true; data: MyWorkspaces }) => void = () => undefined;
        workspaceResultMock.mockReturnValue(new Promise((resolve) => {
            resolveWorkspace = resolve;
        }));

        const rendering = OrganizationLayout({ children: createElement("p", null, "admin content") });
        await vi.waitFor(() => expect(capabilityResultMock).toHaveBeenCalledOnce());
        resolveWorkspace({ ok: true, data: workspaceSnapshot(MEMBER_WORKSPACE) });

        await rendering;
    });

    it("refuses authority held only in a different organization", async () => {
        workspaceResultMock.mockResolvedValue({
            ok: true,
            data: {
                workspaces: [OTHER_ORG_ADMIN_WORKSPACE, MEMBER_WORKSPACE],
                activeWorkspaceId: MEMBER_WORKSPACE.id,
            },
        });

        const rendered = await OrganizationLayout({ children: createElement("p", null, "admin content") });
        const tabs = findByType(rendered, OrgTabs);

        expect(findByType(rendered, NoAccessCard)).not.toBeNull();
        expect(tabs !== null && isOrgTabsProps(tabs.props) ? tabs.props.isOrgAdmin : null).toBe(false);
        expect(containsText(rendered, "admin content")).toBe(false);
    });

    it("renders an unavailable state when organization standing cannot be resolved", async () => {
        workspaceResultMock.mockResolvedValue({ ok: false });

        const rendered = await OrganizationLayout({ children: createElement("p", null, "admin content") });

        expect(isValidElement(rendered) ? rendered.type : null).toBe(WorkspaceUnavailablePage);
        expect(findByType(rendered, NoAccessCard)).toBeNull();
    });

    it.each([
        { workspaces: [], activeWorkspaceId: null },
        { workspaces: [MEMBER_WORKSPACE], activeWorkspaceId: 404 },
    ] satisfies MyWorkspaces[])(
        "renders unavailable when the workspace snapshot has no active membership: $activeWorkspaceId",
        async (snapshot) => {
            workspaceResultMock.mockResolvedValue({ ok: true, data: snapshot });

            const rendered = await OrganizationLayout({ children: createElement("p", null, "admin content") });

            expect(isValidElement(rendered) ? rendered.type : null).toBe(WorkspaceUnavailablePage);
            expect(findByType(rendered, NoAccessCard)).toBeNull();
        },
    );

    it("keeps organization routes available while marking unresolved SSO availability", async () => {
        workspaceResultMock.mockResolvedValue({
            ok: true,
            data: workspaceSnapshot({ ...MEMBER_WORKSPACE, orgRole: "owner" }),
        });
        capabilityResultMock.mockResolvedValue({ ok: false });

        const layout = await OrganizationLayout({ children: createElement("p", null, "admin content") });
        const ssoPage = await OrgSsoPage();
        const tabs = findByType(layout, OrgTabs);

        expect(isValidElement(layout) ? layout.type : null).toBe(OrganizationWorkspaceGuard);
        expect(containsText(layout, "admin content")).toBe(true);
        expect(tabs !== null && isOrgTabsProps(tabs.props) ? tabs.props.ssoAvailability : null)
            .toBe("unavailable");
        if (tabs === null) throw new Error("Organization navigation did not render");
        const tabHtml = renderToStaticMarkup(tabs);
        expect(tabHtml).toContain("/organization/sso");
        expect(tabHtml).toContain("tabSsoAvailabilityUnknown");
        expect(isValidElement(ssoPage) ? ssoPage.type : null).toBe(CapabilityUnavailablePage);
        expect(redirectMock).not.toHaveBeenCalled();

        const unavailable = await CapabilityUnavailablePage();
        const unavailableHtml = renderToStaticMarkup(unavailable);
        expect(unavailableHtml).toContain(">title</h2>");
        expect(unavailableHtml).toContain(">retry</button>");
        const container = installMinimalDocument();
        const { createRoot } = await import("react-dom/client");
        const root = createRoot(container);
        await act(async () => {
            root.render(unavailable);
        });
        await act(async () => {
            buttonActionState.onClick();
        });
        expect(routerRefreshMock).toHaveBeenCalledOnce();
        await act(async () => root.unmount());
    });

    it("explains a disabled-capability result in place instead of forwarding to Administrators", async () => {
        capabilityResultMock.mockResolvedValue({ ok: true, data: { sso: false } });

        const rendered = await OrgSsoPage();

        expect(isValidElement(rendered) ? rendered.type : null).toBe(SettingsAvailabilityNotice);
        expect(
            isValidElement(rendered) && hasAvailabilityState(rendered.props) ? rendered.props.state : null,
        ).toBe("not-enabled");
        expect(
            redirectMock,
            "#1340: an instance without single sign-on says so where single sign-on lives",
        ).not.toHaveBeenCalled();
        expect(renderToStaticMarkup(rendered)).toContain("notEnabledTitle");
    });

    it("renders SSO after a successful enabled-capability result without redirecting", async () => {
        capabilityResultMock.mockResolvedValue({ ok: true, data: { sso: true } });

        const rendered = await OrgSsoPage();

        expect(isValidElement(rendered) ? rendered.type : null).toBe(SsoPanel);
        expect(redirectMock).not.toHaveBeenCalled();
    });

    it("renders the section for a server-resolved organization administrator", async () => {
        workspaceResultMock.mockResolvedValue({
            ok: true,
            data: workspaceSnapshot({ ...MEMBER_WORKSPACE, orgRole: "admin" }),
        });

        const rendered = await OrganizationLayout({ children: createElement("p", null, "admin content") });
        const tabs = findByType(rendered, OrgTabs);

        expect(tabs !== null && isOrgTabsProps(tabs.props) ? tabs.props.isOrgAdmin : null).toBe(true);
        expect(findByType(rendered, NoAccessCard)).toBeNull();
        expect(containsText(rendered, "admin content")).toBe(true);
    });

    it("admits an organization owner", async () => {
        workspaceResultMock.mockResolvedValue({
            ok: true,
            data: workspaceSnapshot({ ...MEMBER_WORKSPACE, orgRole: "owner" }),
        });

        const rendered = await OrganizationLayout({ children: createElement("p", null, "owner content") });
        const tabs = findByType(rendered, OrgTabs);

        expect(tabs !== null && isOrgTabsProps(tabs.props) ? tabs.props.isOrgAdmin : null).toBe(true);
        expect(findByType(rendered, NoAccessCard)).toBeNull();
        expect(containsText(rendered, "owner content")).toBe(true);
    });

    it("withholds a retained organization payload and reconciles it through provider recovery", async () => {
        activeWorkspaceState.id = OTHER_ORG_ADMIN_WORKSPACE.id;

        const withheldHtml = renderToStaticMarkup(createElement(
            OrganizationWorkspaceGuard,
            { workspaceId: MEMBER_WORKSPACE.id },
            createElement("p", null, "stale admin content"),
        ));

        expect(withheldHtml).toContain("workspace selection unavailable");
        expect(withheldHtml).not.toContain("stale admin content");

        await workspaceUnavailableState.onRetry();

        const reconciledHtml = renderToStaticMarkup(createElement(
            OrganizationWorkspaceGuard,
            { workspaceId: MEMBER_WORKSPACE.id },
            createElement("p", null, "reconciled admin content"),
        ));
        expect(retrySelectionRecoveryMock).toHaveBeenCalledOnce();
        expect(reconciledHtml).toContain("reconciled admin content");
        expect(reconciledHtml).not.toContain("workspace selection unavailable");
    });
});
