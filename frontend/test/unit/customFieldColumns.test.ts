import { readFileSync } from "node:fs";
import path from "node:path";
import { afterEach, describe, expect, it, vi } from "vitest";

import { getCustomFieldSchema } from "@/app/lib/api";

const COLUMNS = "app/components/records/CustomFieldColumns.tsx";
const PANEL = "app/components/settings/CustomFieldsPanel.tsx";
const APP_LAYOUT = "app/(app)/layout.tsx";

function source(relativePath: string): string {
    return readFileSync(path.resolve(process.cwd(), relativePath), "utf8");
}

function stubFetch(): { urls: string[] } {
    const urls: string[] = [];
    vi.stubGlobal("fetch", (input: string) => {
        urls.push(String(input));
        return Promise.resolve(
            new Response("[]", { status: 200, headers: { "content-type": "application/json" } }),
        );
    });
    return { urls };
}

describe("the schema a records list reads", () => {
    afterEach(() => {
        vi.unstubAllGlobals();
    });

    it("is the member-facing projection, not the admin catalog", async () => {
        const { urls } = stubFetch();

        await getCustomFieldSchema("person");

        expect(urls).toHaveLength(1);
        expect(urls[0]).toContain("/api/custom-fields/schema?entityType=person");
        expect(urls[0]).not.toMatch(/\/api\/custom-fields\?/);
    });
});

describe("custom-field columns are readable by every member", () => {
    const columns = source(COLUMNS);

    it("loads the member schema unconditionally, so a member with no rows still gets columns", () => {
        expect(columns).toContain("getCustomFieldSchema(entityType)");
        expect(columns).not.toContain("getEntityCustomFields(");
        expect(columns).not.toMatch(/rows\[0\]\.id/);
    });

    it("builds the columns from that schema rather than from the gated catalog", () => {
        expect(columns).toMatch(/const fields = useMemo\(\(\) => schema\.map\(schemaField\)/);
    });

    it("only requests the admin catalog when the viewer may actually manage fields", () => {
        expect(columns).toContain("getCustomFields(entityType)");
        expect(columns.indexOf("if (!canManage)")).toBeGreaterThan(-1);
        expect(columns.indexOf("if (!canManage)")).toBeLessThan(columns.indexOf("getCustomFields(entityType)"));
    });

    it("never lets a failed request decide what the viewer may manage", () => {
        expect(columns).not.toContain("setCanManage");
    });

    it("derives the manage affordances from the viewer's effective permissions", () => {
        expect(columns).toMatch(/usePermission\(["']CUSTOM_FIELD_MANAGE["']\)/);
    });
});

describe("custom-field administration stays gated", () => {
    it("refuses the settings panel by permission rather than by catching a 403", () => {
        const panel = source(PANEL);

        expect(panel).toMatch(/usePermission\(["']CUSTOM_FIELD_MANAGE["']\)/);
        expect(panel).toMatch(/if \(!canManage \|\|/);
        expect(panel).toMatch(/if \(!workspaceId \|\| !canManage\) return;/);
    });

    it("is fed effective permissions by the app shell, fail-closed", () => {
        const layout = source(APP_LAYOUT);

        expect(layout).toMatch(/permissionsResult\.ok \? permissionsResult\.data : \[\]/);
        expect(layout).toMatch(/<PermissionsProvider permissions=\{effectivePermissions\}>/);
    });
});
