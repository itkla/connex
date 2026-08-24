import {
    createElement,
    isValidElement,
    type AnchorHTMLAttributes,
    type ComponentProps,
    type PropsWithChildren,
    type ReactElement,
    type ReactNode,
} from "react";
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";

import { renderToStaticMarkup } from "react-dom/server";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import OrganizationSettingsLayout from "@/app/(app)/settings/organization/layout";
import OrganizationWorkspaceGuard from "@/app/components/organization/OrganizationWorkspaceGuard";
import SettingsAvailabilityNotice from "@/app/components/settings/SettingsAvailabilityNotice";
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

describe("the canonical organization shell refuses authority the active workspace does not carry", () => {
    it("admits an organization administrator, so the refusals below mean something", async () => {
        workspaceResultMock.mockResolvedValue({
            ok: true,
            data: workspaceSnapshot({ ...MEMBER_WORKSPACE, orgRole: "admin" }),
        });

        const rendered = await OrganizationSettingsLayout({
            children: createElement("p", null, "admin content"),
        });

        expect(findByType(rendered, OrganizationWorkspaceGuard)).not.toBeNull();
        expect(findByType(rendered, SettingsAvailabilityNotice)).toBeNull();
        expect(containsText(rendered, "admin content")).toBe(true);
    });

    it("refuses authority held only in a different organization", async () => {
        workspaceResultMock.mockResolvedValue({
            ok: true,
            data: {
                workspaces: [MEMBER_WORKSPACE, OTHER_ORG_ADMIN_WORKSPACE],
                activeWorkspaceId: MEMBER_WORKSPACE.id,
            },
        });

        const rendered = await OrganizationSettingsLayout({
            children: createElement("p", null, "administration content"),
        });
        const refusal = findByType(rendered, SettingsAvailabilityNotice);

        expect(
            refusal,
            "an organization role is authority over one organization; holding it elsewhere is not standing here",
        ).not.toBeNull();
        expect(
            refusal !== null && hasAvailabilityState(refusal.props) ? refusal.props.state : null,
        ).toBe("ask-admin");
        expect(containsText(rendered, "administration content")).toBe(false);
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

/**
 * The legacy organization shell is gone, and this is what keeps it gone.
 *
 * Every `/organization/*` address now forwards to the canonical destination that absorbed it, which
 * left the tab strip with nothing to link and the layout with nothing but redirect stubs to wrap.
 * Deleting them is the point of #1340 WS4.6; re-adding either would rebuild the competing
 * administration shell the epic exists to remove, and would do it quietly, because a second shell
 * over redirect stubs renders nothing a test would otherwise notice.
 */
describe("the legacy organization shell stays retired", () => {
    it("keeps no organization tab strip and no organization layout", () => {
        const retired = [
            "app/components/organization/OrgTabs.tsx",
            "app/(app)/organization/layout.tsx",
        ].filter((file) => existsSync(join(process.cwd(), file)));

        expect(
            retired,
            "the organization routes redirect; a shell above them would wrap nothing and compete with /settings",
        ).toEqual([]);
    });

    it("leaves every organization address forwarding rather than rendering", () => {
        const rendering = ORG_ADMIN_ROUTES.filter(
            (route) => !/permanentRedirect\(/.test(
                readFileSync(join(process.cwd(), "app", "(app)", route, "page.tsx"), "utf8"),
            ),
        );

        expect(rendering).toEqual([]);
    });
});
