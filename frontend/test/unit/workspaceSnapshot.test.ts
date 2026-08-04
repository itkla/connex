import { readFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";

import type { MyWorkspaces, Workspace } from "@/app/lib/types";
import {
    adoptWorkspaces,
    applyOrganizationIdentity,
    applyWorkspaceIdentity,
    resolveWorkspaceTimezone,
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
        expect(providerBodyBeforeItsHandlers()).toMatch(
            /if \(publishedWorkspaces !== initialWorkspaces\) \{\s*setPublishedWorkspaces\(initialWorkspaces\);\s*setWorkspaces\(adoptWorkspaces\(workspaces, publishedWorkspaces, initialWorkspaces\)\);\s*\}/,
        );
    });

    it("adopts it during render, so no frame commits the role the viewer just left", () => {
        expect(providerBodyBeforeItsHandlers()).not.toMatch(/useEffect\([\s\S]*setWorkspaces\(initialWorkspaces\)/);
    });

    it("tracks what it has already consumed, so adopting cannot loop", () => {
        expect(providerBodyBeforeItsHandlers()).toContain(
            "const [publishedWorkspaces, setPublishedWorkspaces] = useState(initialWorkspaces)",
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
            role: "owner",
            orgId: 1,
            orgName: "Acme",
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
        role: "admin",
        orgId: 4,
        orgName: "Old organization",
        orgRole: "admin",
    };

    it("replaces mutable workspace identity without changing the viewer's roles", () => {
        expect(applyWorkspaceIdentity([workspace], {
            id: 7,
            name: "After",
            slug: "before",
            timezone: "Asia/Tokyo",
        })).toEqual([{
            ...workspace,
            name: "After",
            timezone: "Asia/Tokyo",
        }]);
    });

    it("updates the organization label across its workspaces only", () => {
        const other = { ...workspace, id: 8, orgId: 5, orgName: "Other" };

        expect(applyOrganizationIdentity([workspace, other], {
            id: 4,
            name: "New organization",
        })).toEqual([{ ...workspace, orgName: "New organization" }, other]);
    });

    it("restores a failed optimistic identity while that exact value remains published", () => {
        const optimistic = { id: 7, name: "Optimistic", slug: "before", timezone: "Asia/Tokyo" };
        const previous = { id: 7, name: "Before", slug: "before", timezone: null };

        expect(restoreWorkspaceIdentity(
            [{ ...workspace, name: optimistic.name, timezone: optimistic.timezone }],
            optimistic,
            previous,
        )).toEqual([workspace]);
    });

    it("does not overwrite a newer server identity while rolling back an older request", () => {
        const optimistic = { id: 7, name: "Optimistic", slug: "before", timezone: "Asia/Tokyo" };
        const previous = { id: 7, name: "Before", slug: "before", timezone: null };
        const newer = [{ ...workspace, name: "Newer server value", timezone: "UTC" }];

        expect(restoreWorkspaceIdentity(newer, optimistic, previous)).toBe(newer);
    });
});

describe("workspace timezone resolution", () => {
    const active: Workspace = {
        id: 7,
        name: "Active",
        slug: "active",
        timezone: "Asia/Tokyo",
        role: "owner",
        orgId: 4,
        orgName: "Organization",
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
