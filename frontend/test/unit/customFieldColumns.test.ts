import { readFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";

const COLUMNS = "app/components/records/CustomFieldColumns.tsx";
const PANEL = "app/components/settings/CustomFieldsPanel.tsx";
const APP_LAYOUT = "app/(app)/layout.tsx";

function source(relativePath: string): string {
    return readFileSync(path.resolve(process.cwd(), relativePath), "utf8");
}

describe("custom-field columns are readable by every member", () => {
    const columns = source(COLUMNS);

    it("loads the catalog unconditionally, so a member with no rows still gets columns", () => {
        expect(columns).toContain("getCustomFields(entityType)");
        expect(columns).not.toContain("getEntityCustomFields(");
        expect(columns).not.toMatch(/rows\[0\]\.id/);
    });

    it("never lets a failed catalog request decide what the viewer may manage", () => {
        const failureBranch = columns.slice(columns.indexOf(".catch("));

        expect(failureBranch).not.toContain("setCanManage");
        expect(columns).not.toContain("setCanManage");
    });

    it("derives the manage affordances from the viewer's effective permissions", () => {
        expect(columns).toContain('import { usePermission } from "@/app/hooks/usePermissions";');
        expect(columns).toContain('const canManage = usePermission("CUSTOM_FIELD_MANAGE");');
    });
});

describe("custom-field administration stays gated", () => {
    it("refuses the settings panel by permission rather than by catching a 403", () => {
        const panel = source(PANEL);

        expect(panel).toContain('const canManage = usePermission("CUSTOM_FIELD_MANAGE");');
        expect(panel).toContain("if (!canManage || accessDenied) {");
        expect(panel).toContain("if (!workspaceId || !canManage) return;");
    });

    it("is fed effective permissions by the app shell, fail-closed", () => {
        const layout = source(APP_LAYOUT);

        expect(layout).toContain(
            "const effectivePermissions = permissionsResult.ok ? permissionsResult.data : [];",
        );
        expect(layout).toContain("<PermissionsProvider permissions={effectivePermissions}>");
    });
});
