import { readFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";

import type { MyWorkspaces, Workspace } from "@/app/lib/types";
import {
    adoptWorkspaces,
    applyOrganizationIdentity,
    applyWorkspaceIdentity,
    preservePublishedOrganizationIdentities,
    preservePublishedWorkspaceIdentities,
    resolveWorkspaceTimezone,
    restoreOrganizationIdentity,
    restoreWorkspaceIdentity,
} from "@/app/lib/workspaceSnapshot";

const PROVIDER = "app/hooks/useWorkspace.tsx";
const MEMBERS_PANEL = "app/components/settings/MembersPanel.tsx";
const APP_LAYOUT = "app/(app)/layout.tsx";

function source(relativePath: string): string {
    return readFileSync(path.resolve(process.cwd(), relativePath), "utf8");
}

function providerBodyBeforeItsHandlers(): string {
    const provider = source(PROVIDER);
    return provider.slice(
        provider.indexOf("export function WorkspaceProvider"),
        provider.indexOf("const runInWorkspace = useCallback"),
    );
}

describe("the workspace snapshot follows the server, not only the first render", () => {
    it("is seeded from the app shell's server-side read", () => {
        expect(source(APP_LAYOUT)).toContain(
            "<WorkspaceProvider initialWorkspaces={workspaces} initialActiveId={activeWorkspaceId}>",
        );
    });

    it("adopts a later payload instead of ignoring it", () => {
        const body = providerBodyBeforeItsHandlers();

        expect(body).toContain("workspaceSnapshot.consumedWorkspaces !== initialWorkspaces");
        expect(body).toContain("const adopted = adoptWorkspaces(");
        expect(body).toContain("consumedWorkspaces: initialWorkspaces");
    });

    it("adopts it during render, so no frame commits the role the viewer just left", () => {
        expect(providerBodyBeforeItsHandlers()).not.toMatch(/useEffect\([\s\S]*setWorkspaces\(initialWorkspaces\)/);
    });

    it("tracks what it has already consumed, so adopting cannot loop", () => {
        expect(providerBodyBeforeItsHandlers()).toContain(
            "consumedWorkspaces: initialWorkspaces",
        );
    });

    it("does not gate adoption on an operation being idle, which only defers the same overwrite", () => {
        expect(providerBodyBeforeItsHandlers()).not.toContain("if (!switching &&");
    });

    it("keeps the pure merge out of the component file, so Fast Refresh still preserves state", () => {
        const provider = source(PROVIDER);

        expect(provider).toContain('from "@/app/lib/workspaceSnapshot"');
        expect(provider).not.toContain("export function adoptWorkspaces");
        expect(provider.match(/^export /gm) ?? []).toHaveLength(2);
    });

    it("does not blindly adopt the active workspace from a refreshed prop", () => {
        const provider = source(PROVIDER);

        expect(providerBodyBeforeItsHandlers()).not.toContain("setActiveWorkspaceId(initialActiveId)");
        expect(provider).toContain(
            "const [activeWorkspaceId, setActiveWorkspaceId] = useState<number | null>(initialActiveId)",
        );
        expect(provider).toContain("never adopted from a refreshed prop");
        expect(provider).not.toMatch(/props can only ever echo a decision/);
    });
});

describe("a payload cannot erase a workspace the server has not seen yet", () => {
    function workspace(id: number, name = `W${id}`): Workspace {
        return {
            id,
            name,
            slug: name.toLowerCase(),
            timezone: null,
            identityVersion: 0,
            role: "owner",
            orgId: 1,
            orgName: "Acme",
            orgIdentityVersion: 0,
            orgRole: "owner",
        };
    }

    const a = workspace(1, "Alpha");
    const b = workspace(2, "Beta");
    const created = workspace(3, "Created");
    const accepted = workspace(4, "Accepted");

    it("takes the server's list when it has nothing local to preserve", () => {
        expect(adoptWorkspaces([a], [a], [a, b])).toEqual([a, b]);
    });

    it("takes the server's version of a workspace, so a changed role actually lands", () => {
        const demoted = { ...a, role: "member" as const };

        expect(adoptWorkspaces([a], [a], [demoted])).toEqual([demoted]);
    });

    it("takes server membership state without regressing newer identity versions", () => {
        const current = {
            ...a,
            name: "Current workspace",
            identityVersion: 2,
            orgName: "Current organization",
            orgIdentityVersion: 3,
        };
        const stale = {
            ...a,
            role: "member" as const,
            name: "Stale workspace",
            identityVersion: 1,
            orgName: "Stale organization",
            orgIdentityVersion: 2,
        };

        expect(adoptWorkspaces([current], [a], [stale])).toEqual([{
            ...stale,
            name: current.name,
            slug: current.slug,
            timezone: current.timezone,
            identityVersion: current.identityVersion,
            orgName: current.orgName,
            orgIdentityVersion: current.orgIdentityVersion,
        }]);
    });

    it("keeps a just-created workspace an older payload does not mention", () => {
        expect(adoptWorkspaces([a, created], [a], [a])).toEqual([a, created]);
    });

    it("keeps a just-accepted workspace absent from both consumed and arriving payloads", () => {
        expect(adoptWorkspaces([a, accepted], [a], [a])).toEqual([a, accepted]);
    });

    it("drops a workspace the viewer left, rather than resurrecting it", () => {
        expect(adoptWorkspaces([a, b], [a, b], [a])).toEqual([a]);
    });

    it("still drops it when the viewer left one and created another in the same window", () => {
        expect(adoptWorkspaces([a, b, created], [a, b], [a])).toEqual([a, created]);
    });

    it("never leaves the active workspace missing from the list it was created in", () => {
        const adopted = adoptWorkspaces([a, created], [a], [a]);

        expect(adopted.some((entry) => entry.id === created.id)).toBe(true);
    });

    it("returns the payload itself when nothing was held back, so identity settles", () => {
        const arriving = [a, b];

        expect(adoptWorkspaces([a], [a], arriving)).toBe(arriving);
    });
});

describe("identity mutations preserve membership state", () => {
    const workspace: Workspace = {
        id: 7,
        name: "Before",
        slug: "before",
        timezone: null,
        identityVersion: 0,
        role: "admin",
        orgId: 4,
        orgName: "Old organization",
        orgIdentityVersion: 0,
        orgRole: "admin",
    };

    it("replaces mutable workspace identity without changing the viewer's roles", () => {
        expect(applyWorkspaceIdentity([workspace], {
            id: 7,
            name: "After",
            slug: "before",
            timezone: "Asia/Tokyo",
            identityVersion: 1,
        })).toEqual([{
            ...workspace,
            name: "After",
            timezone: "Asia/Tokyo",
            identityVersion: 1,
        }]);
    });

    it("updates the organization label across its workspaces only", () => {
        const other = { ...workspace, id: 8, orgId: 5, orgName: "Other" };

        expect(applyOrganizationIdentity([workspace, other], {
            id: 4,
            name: "New organization",
            identityVersion: 1,
        })).toEqual([{
            ...workspace,
            orgName: "New organization",
            orgIdentityVersion: 1,
        }, other]);
    });

    it("restores a failed optimistic identity while that exact value remains published", () => {
        const optimistic = {
            id: 7,
            name: "Optimistic",
            slug: "before",
            timezone: "Asia/Tokyo",
            identityVersion: 0,
        };
        const previous = {
            id: 7,
            name: "Before",
            slug: "before",
            timezone: null,
            identityVersion: 0,
        };

        expect(restoreWorkspaceIdentity(
            [{ ...workspace, name: optimistic.name, timezone: optimistic.timezone }],
            optimistic,
            previous,
        )).toEqual([workspace]);
    });

    it("does not overwrite a newer server identity while rolling back an older request", () => {
        const optimistic = {
            id: 7,
            name: "Optimistic",
            slug: "before",
            timezone: "Asia/Tokyo",
            identityVersion: 0,
        };
        const previous = {
            id: 7,
            name: "Before",
            slug: "before",
            timezone: null,
            identityVersion: 0,
        };
        const newer = [{
            ...workspace,
            name: "Newer server value",
            timezone: "UTC",
            identityVersion: 1,
        }];

        expect(restoreWorkspaceIdentity(newer, optimistic, previous)).toBe(newer);
    });

    it("restores an optimistic organization label only while it is still published", () => {
        const optimistic = { id: 4, name: "Optimistic", identityVersion: 0 };
        const previous = { id: 4, name: "Old organization", identityVersion: 0 };

        expect(restoreOrganizationIdentity(
            [{ ...workspace, orgName: optimistic.name }],
            optimistic,
            previous,
        )).toEqual([workspace]);
        expect(restoreOrganizationIdentity(
            [{ ...workspace, orgName: "Newer organization", orgIdentityVersion: 1 }],
            optimistic,
            previous,
        )).toEqual([{
            ...workspace,
            orgName: "Newer organization",
            orgIdentityVersion: 1,
        }]);
    });

    it("holds a published workspace identity until the server acknowledges it", () => {
        const published = {
            id: 7,
            name: "Published",
            slug: "before",
            timezone: "Asia/Tokyo",
            identityVersion: 1,
        };
        const stale = [{ ...workspace, name: "Before", timezone: null }];

        const protectedResult = preservePublishedWorkspaceIdentities(stale, [published]);
        expect(protectedResult.workspaces).toEqual([{
            ...workspace,
            name: "Published",
            timezone: "Asia/Tokyo",
            identityVersion: 1,
        }]);
        expect(protectedResult.pending).toEqual([published]);

        const acknowledged = preservePublishedWorkspaceIdentities(
            protectedResult.workspaces,
            [published],
        );
        expect(acknowledged.pending).toEqual([]);

        const newer = [{
            ...workspace,
            name: "Newer",
            timezone: "UTC",
            identityVersion: 2,
        }];
        const superseded = preservePublishedWorkspaceIdentities(newer, [published]);
        expect(superseded.workspaces).toBe(newer);
        expect(superseded.pending).toEqual([]);
    });

    it("holds an organization label until every workspace in its snapshot acknowledges it", () => {
        const sibling = { ...workspace, id: 8 };
        const published = { id: 4, name: "Published organization", identityVersion: 1 };

        const protectedResult = preservePublishedOrganizationIdentities(
            [{ ...workspace, orgName: published.name }, sibling],
            [published],
        );
        expect(protectedResult.workspaces).toEqual([
            { ...workspace, orgName: published.name, orgIdentityVersion: 1 },
            { ...sibling, orgName: published.name, orgIdentityVersion: 1 },
        ]);
        expect(protectedResult.pending).toEqual([published]);

        const acknowledged = preservePublishedOrganizationIdentities(
            protectedResult.workspaces,
            [published],
        );
        expect(acknowledged.pending).toEqual([]);

        const newer = [{
            ...workspace,
            orgName: "Newer organization",
            orgIdentityVersion: 2,
        }];
        const superseded = preservePublishedOrganizationIdentities(newer, [published]);
        expect(superseded.workspaces).toEqual(newer);
        expect(superseded.pending).toEqual([]);
    });

    it("normalizes a held workspace to a newer organization snapshot", () => {
        const arriving = {
            ...workspace,
            orgName: "Newer organization",
            orgIdentityVersion: 2,
        };
        const held = { ...workspace, id: 8 };

        expect(preservePublishedOrganizationIdentities([arriving, held], []).workspaces)
            .toEqual([arriving, {
                ...held,
                orgName: arriving.orgName,
                orgIdentityVersion: arriving.orgIdentityVersion,
            }]);
    });
});

describe("workspace timezone resolution", () => {
    const active: Workspace = {
        id: 7,
        name: "Active",
        slug: "active",
        timezone: "Asia/Tokyo",
        identityVersion: 0,
        role: "owner",
        orgId: 4,
        orgName: "Organization",
        orgIdentityVersion: 0,
        orgRole: "owner",
    };

    function snapshot(workspaces: Workspace[], activeWorkspaceId: number | null): MyWorkspaces {
        return { workspaces, activeWorkspaceId };
    }

    it("prefers the active workspace reporting timezone", () => {
        expect(resolveWorkspaceTimezone(snapshot([active], active.id), "UTC")).toBe("Asia/Tokyo");
    });

    it("falls back to the account timezone when the active workspace has no override", () => {
        expect(resolveWorkspaceTimezone(
            snapshot([{ ...active, timezone: null }], active.id),
            "Pacific/Honolulu",
        )).toBe("Pacific/Honolulu");
    });

    it("falls back to the account timezone when the active workspace is absent", () => {
        expect(resolveWorkspaceTimezone(snapshot([active], 99), "UTC")).toBe("UTC");
    });
});

describe("members administration reads a snapshot that can go stale", () => {
    const panel = source(MEMBERS_PANEL);

    it("gates its own chrome on the workspace snapshot this sync keeps fresh", () => {
        expect(panel).toContain("const role = activeWorkspace?.role;");
        expect(panel).toContain('const isAdmin = role === "admin" || role === "owner";');
        expect(panel).toContain('const isOwner = role === "owner";');
    });

    it("asks the server for a new snapshot after a role changes", () => {
        expect(panel).toContain("await updateMemberRole(");
        expect(panel).toContain("await assignMemberCustomRole(");
        expect(panel).toContain("router.refresh()");
    });

    it("keeps a member's own row editable, because the backend supports stepping down", () => {
        expect(panel).toContain("const editable = isAdmin && (member.roleId == null || isOwner);");
        expect(panel).not.toMatch(/const editable = [^;]*!isSelf/);
    });

    it("still withholds removal of one's own row, which is leaving rather than demotion", () => {
        expect(panel).toContain("{isAdmin && !isSelf && (");
    });
});
