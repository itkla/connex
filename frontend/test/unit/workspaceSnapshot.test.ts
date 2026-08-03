import { readFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";

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
            /if \(!switching && publishedWorkspaces !== initialWorkspaces\) \{\s*setPublishedWorkspaces\(initialWorkspaces\);\s*setWorkspaces\(initialWorkspaces\);\s*\}/,
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

    it("holds adoption while a switch or create is running, so neither is clobbered", () => {
        const guarded = seeding();

        expect(guarded).toContain("if (!switching && ");
        expect(guarded.indexOf("const [switching, setSwitching]")).toBeLessThan(
            guarded.indexOf("if (!switching && "),
        );
    });

    it("leaves the active workspace client-owned, because only this provider ever moves it", () => {
        const provider = source(PROVIDER);

        expect(seeding()).not.toContain("setActiveWorkspaceId(initialActiveId)");
        expect(provider).toContain("const [activeWorkspaceId, setActiveWorkspaceId] = useState<number | null>(initialActiveId)");
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
