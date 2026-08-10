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
import { beforeEach, describe, expect, it, vi } from "vitest";

import OrganizationLayout from "@/app/(app)/organization/layout";
import OrgSsoPage from "@/app/(app)/organization/sso/page";
import CapabilityUnavailablePage from "@/app/components/CapabilityUnavailablePage";
import { NoAccessCard } from "@/app/components/organization/OrgPrimitives";
import OrgTabs from "@/app/components/organization/OrgTabs";
import OrganizationWorkspaceGuard from "@/app/components/organization/OrganizationWorkspaceGuard";
import WorkspaceUnavailablePage from "@/app/components/WorkspaceUnavailablePage";
import type { MyWorkspaces, Workspace } from "@/app/lib/types";

const { activeWorkspaceState, capabilityResultMock, redirectMock, workspaceResultMock } = vi.hoisted(() => ({
    activeWorkspaceState: { id: 7 as number | null },
    capabilityResultMock: vi.fn(),
    redirectMock: vi.fn(),
    workspaceResultMock: vi.fn(),
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
    useRouter: () => ({ refresh: vi.fn() }),
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

vi.mock("@/app/lib/api", () => ({
    getCapabilitiesResultFromCookie: capabilityResultMock,
    getMyWorkspacesResultFromCookie: workspaceResultMock,
}));

vi.mock("@/app/hooks/useWorkspace", () => ({
    useWorkspace: () => ({ activeWorkspaceId: activeWorkspaceState.id }),
}));

vi.mock("@/app/components/WorkspaceSelectionUnavailable", async () => {
    const React = await import("react");
    return {
        default: () => React.createElement("p", null, "workspace selection unavailable"),
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

function isOrgTabsProps(value: unknown): value is { isOrgAdmin: boolean } {
    return typeof value === "object"
        && value !== null
        && "isOrgAdmin" in value
        && typeof value.isOrgAdmin === "boolean";
}

beforeEach(() => {
    activeWorkspaceState.id = MEMBER_WORKSPACE.id;
    capabilityResultMock.mockReset().mockResolvedValue({ ok: true, data: { sso: true } });
    redirectMock.mockReset();
    workspaceResultMock.mockReset();
});

describe("organization navigation authority", () => {
    it("renders no organization admin tab for a non-admin", () => {
        const html = renderToStaticMarkup(createElement(OrgTabs, {
            isOrgAdmin: false,
            ssoEnabled: true,
        }));

        for (const route of ORG_ADMIN_ROUTES) expect(html).not.toContain(route);
    });

    it("keeps organization navigation available to an administrator", () => {
        const html = renderToStaticMarkup(createElement(OrgTabs, {
            isOrgAdmin: true,
            ssoEnabled: true,
        }));

        for (const route of ORG_ADMIN_ROUTES) expect(html).toContain(route);
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

    it("renders retryable unavailability instead of treating a failed capability read as disabled", async () => {
        workspaceResultMock.mockResolvedValue({
            ok: true,
            data: workspaceSnapshot({ ...MEMBER_WORKSPACE, orgRole: "owner" }),
        });
        capabilityResultMock.mockResolvedValue({ ok: false });

        const layout = await OrganizationLayout({ children: createElement("p", null, "admin content") });
        const ssoPage = await OrgSsoPage();

        expect(isValidElement(layout) ? layout.type : null).toBe(CapabilityUnavailablePage);
        expect(isValidElement(ssoPage) ? ssoPage.type : null).toBe(CapabilityUnavailablePage);
        expect(redirectMock).not.toHaveBeenCalled();
    });

    it("redirects the SSO route only after a successful disabled-capability result", async () => {
        capabilityResultMock.mockResolvedValue({ ok: true, data: { sso: false } });

        await OrgSsoPage();

        expect(redirectMock).toHaveBeenCalledWith("/organization/members");
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

    it("withholds a retained organization payload after the active workspace changes", () => {
        activeWorkspaceState.id = OTHER_ORG_ADMIN_WORKSPACE.id;

        const html = renderToStaticMarkup(createElement(
            OrganizationWorkspaceGuard,
            { workspaceId: MEMBER_WORKSPACE.id },
            createElement("p", null, "stale admin content"),
        ));

        expect(html).toContain("workspace selection unavailable");
        expect(html).not.toContain("stale admin content");
    });
});
