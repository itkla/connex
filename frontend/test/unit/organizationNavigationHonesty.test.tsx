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
import { NoAccessCard } from "@/app/components/organization/OrgPrimitives";
import OrgTabs from "@/app/components/organization/OrgTabs";
import WorkspaceUnavailablePage from "@/app/components/WorkspaceUnavailablePage";
import type { MyWorkspaces, Workspace } from "@/app/lib/types";

const { workspaceResultMock } = vi.hoisted(() => ({
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
    usePathname: () => "/organization/members",
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
    DEFAULT_CAPABILITIES: { sso: false },
    getCapabilities: () => Promise.resolve({ sso: true }),
    getMyWorkspacesResultFromCookie: workspaceResultMock,
}));

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
});
