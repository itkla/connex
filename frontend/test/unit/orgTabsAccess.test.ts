import { readFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";

const ORG_TABS = "app/components/organization/OrgTabs.tsx";
const ORG_LAYOUT = "app/(app)/organization/layout.tsx";
const SIDEBAR = "app/components/Sidebar.tsx";

function source(relativePath: string): string {
    return readFileSync(path.resolve(process.cwd(), relativePath), "utf8");
}

describe("organization tabs are offered only to org admins", () => {
    it("gates the tab strip on the same orgRole signal Sidebar already uses", () => {
        const tabs = source(ORG_TABS);
        const sidebar = source(SIDEBAR);

        expect(tabs).toContain('useWorkspace()');
        expect(tabs).toContain("activeWorkspace?.orgRole != null");
        expect(sidebar).toContain("activeWorkspace?.orgRole != null");
        expect(tabs).toMatch(/if\s*\(\s*!isOrgAdmin\s*\)\s*return\s+null/);
    });

    it("keeps the SSO tab on the instance capability when the viewer is an org admin", () => {
        const tabs = source(ORG_TABS);

        expect(tabs).toContain("ssoEnabled");
        expect(tabs).toContain('tab.key !== "tabSso" || ssoEnabled');
    });

    it("redirects non-admins away from the organization section server-side", () => {
        const layout = source(ORG_LAYOUT);

        expect(layout).toContain("getMyWorkspacesFromCookie");
        expect(layout).toContain("orgRole == null");
        expect(layout).toContain('redirect("/dashboard")');
    });
});
