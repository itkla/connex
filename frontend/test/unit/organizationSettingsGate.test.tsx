import { createElement, isValidElement, type ReactElement, type ReactNode } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import OrganizationSettingsLayout from "@/app/(app)/settings/organization/layout";
import OrganizationWorkspaceGuard from "@/app/components/organization/OrganizationWorkspaceGuard";
import SettingsAvailabilityNotice from "@/app/components/settings/SettingsAvailabilityNotice";
import WorkspaceUnavailablePage from "@/app/components/WorkspaceUnavailablePage";
import type { MyWorkspaces, Workspace } from "@/app/lib/types";

/**
 * Gate over the organization gate itself (#1340 PR 6).
 *
 * The five canonical organization destinations live under `/settings`, whose layout knows only
 * about the workspace, so the only thing standing between an ordinary member and five organization
 * administration surfaces is this layout. That makes it worth testing by behavior rather than by
 * reading its source.
 *
 * The absent-role case is the one that matters and the one a hand-written fixture misses. The
 * workspace payload types `orgRole` as `OrgRole | null`, but the backend omits the field entirely
 * for a viewer holding no organization role — so a fixture that spells `orgRole: null` passes a gate
 * that the real payload walks straight through. A live pass against a member of someone else's
 * workspace is what found it; this suite is what keeps it found.
 */
const { workspaceResultMock } = vi.hoisted(() => ({ workspaceResultMock: vi.fn() }));

vi.mock("next/headers", () => ({
    headers: () => Promise.resolve(new Headers({ cookie: "JSESSIONID=session; connex_workspace=7" })),
}));

vi.mock("next-intl/server", () => ({
    getTranslations: () => Promise.resolve((key: string) => key),
}));

vi.mock("@/app/lib/api", () => ({ getMyWorkspacesResultFromCookie: workspaceResultMock }));

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

/**
 * The shape the backend actually sends a member: the role key is absent, not null.
 *
 * The cast is the point rather than a convenience. `Workspace` declares `orgRole` as a required
 * `OrgRole | null`, so a payload that omits it is not assignable — and that mismatch between the
 * declared type and the wire format is exactly what let the identity check ship.
 */
function withoutOrgRole(workspace: Workspace): Workspace {
    const fields: Record<string, unknown> = { ...workspace };
    delete fields.orgRole;
    return fields as Workspace;
}

const ROLELESS_WORKSPACE = withoutOrgRole(MEMBER_WORKSPACE);

function snapshot(workspace: Workspace): MyWorkspaces {
    return { workspaces: [workspace], activeWorkspaceId: workspace.id };
}

function findByType(node: ReactNode, type: unknown): ReactElement | null {
    if (!isValidElement(node)) return null;
    if (node.type === type) return node;
    const children = (node.props as { children?: ReactNode }).children;
    if (Array.isArray(children)) {
        for (const child of children) {
            const found = findByType(child, type);
            if (found !== null) return found;
        }
        return null;
    }
    return findByType(children, type);
}

function containsText(node: ReactNode, text: string): boolean {
    if (typeof node === "string") return node.includes(text);
    if (Array.isArray(node)) return node.some((child) => containsText(child, text));
    if (!isValidElement(node)) return false;
    return containsText((node.props as { children?: ReactNode }).children, text);
}

async function render(data: MyWorkspaces | null) {
    workspaceResultMock.mockResolvedValue(
        data === null ? { ok: false, error: "unavailable" } : { ok: true, data },
    );
    return OrganizationSettingsLayout({
        children: createElement("p", null, "organization content"),
    });
}

describe("the canonical organization destinations refuse a viewer without organization standing", () => {
    beforeEach(() => {
        workspaceResultMock.mockReset();
    });

    it.each([
        ["a null organization role", MEMBER_WORKSPACE],
        ["an organization role the payload omits entirely", ROLELESS_WORKSPACE],
    ])("withholds every destination from %s", async (_case, workspace) => {
        const rendered = await render(snapshot(workspace));

        expect(findByType(rendered, SettingsAvailabilityNotice)).not.toBeNull();
        expect(
            containsText(rendered, "organization content"),
            "the settings shell carries no organization gate, so this layout is the only one there is",
        ).toBe(false);
    });

    it("keeps the refusal in the posture the manifest declares, in the organization's own words", async () => {
        const rendered = await render(snapshot(ROLELESS_WORKSPACE));
        const notice = findByType(rendered, SettingsAvailabilityNotice);
        const props = notice?.props as { state?: string; title?: string; body?: string } | undefined;

        expect(props?.state).toBe("ask-admin");
        expect(props?.title, "a general notice would say to ask a workspace administrator").toBe(
            "noAccessTitle",
        );
        expect(props?.body).toBe("noAccessBody");
    });

    it.each(["admin", "owner"] as const)("admits an organization %s", async (orgRole) => {
        const rendered = await render(snapshot({ ...MEMBER_WORKSPACE, orgRole }));

        expect(
            isValidElement(rendered) ? rendered.type : null,
            "standing resolved for one workspace stops applying when the reader switches out of it",
        ).toBe(OrganizationWorkspaceGuard);
        expect(containsText(rendered, "organization content")).toBe(true);
    });

    it("withholds the destinations when the membership lookup itself failed", async () => {
        const rendered = await render(null);

        expect(isValidElement(rendered) ? rendered.type : null).toBe(WorkspaceUnavailablePage);
        expect(containsText(rendered, "organization content")).toBe(false);
    });

    it("withholds them when the active workspace is not one the viewer holds", async () => {
        const rendered = await render({ workspaces: [MEMBER_WORKSPACE], activeWorkspaceId: 999 });

        expect(isValidElement(rendered) ? rendered.type : null).toBe(WorkspaceUnavailablePage);
        expect(containsText(rendered, "organization content")).toBe(false);
    });
});
