import { readFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";

import { adoptWorkspaces } from "@/app/hooks/useWorkspace";
import type { Workspace } from "@/app/lib/types";

const PROVIDER = "app/hooks/useWorkspace.tsx";
const MEMBERS_PANEL = "app/components/settings/MembersPanel.tsx";
const APP_LAYOUT = "app/(app)/layout.tsx";

function source(relativePath: string): string {
    return readFileSync(path.resolve(process.cwd(), relativePath), "utf8");
}

/** The provider body up to its first callback, so an assertion cannot reach into the handlers. */
function seeding(): string {
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
        expect(seeding()).toMatch(
            /if \(publishedWorkspaces !== initialWorkspaces\) \{\s*setPublishedWorkspaces\(initialWorkspaces\);\s*setWorkspaces\(adoptWorkspaces\(workspaces, publishedWorkspaces, initialWorkspaces\)\);\s*\}/,
        );
    });

    it("adopts it during render, so no frame commits the role the viewer just left", () => {
        expect(seeding()).not.toMatch(/useEffect\([\s\S]*setWorkspaces\(initialWorkspaces\)/);
    });

    it("tracks what it has already consumed, so adopting cannot loop", () => {
        expect(seeding()).toContain(
            "const [publishedWorkspaces, setPublishedWorkspaces] = useState(initialWorkspaces)",
        );
    });

    it("does not gate adoption on an operation being idle, which only defers the same overwrite", () => {
        expect(seeding()).not.toContain("if (!switching &&");
    });

    it("does not adopt the active workspace, and does not claim it cannot diverge", () => {
        const provider = source(PROVIDER);

        expect(seeding()).not.toContain("setActiveWorkspaceId(initialActiveId)");
        expect(provider).toContain(
            "const [activeWorkspaceId, setActiveWorkspaceId] = useState<number | null>(initialActiveId)",
        );
        expect(provider).toContain("known gap rather than an\n * invariant");
        expect(provider).not.toMatch(/props can only ever echo a decision/);
    });
});

describe("a payload cannot erase a workspace the server has not seen yet", () => {
    function workspace(id: number, name = `W${id}`): Workspace {
        return { id, name, slug: name.toLowerCase(), role: "owner", orgId: 1, orgName: "Acme", orgRole: "owner" };
    }

    const a = workspace(1, "Alpha");
    const b = workspace(2, "Beta");
    const created = workspace(3, "Created");

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
