import { readFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";

import {
    CREATE_CUSTOM_FIELD,
    IGNORE_COLUMN,
    resolveColumnTarget,
} from "@/app/lib/import";
import {
    checkPermission,
    permissionsDrifted,
    type PermissionsStatus,
} from "@/app/lib/permissionState";

const PROVIDER = "app/hooks/usePermissions.tsx";
const APP_LAYOUT = "app/(app)/layout.tsx";
const CUSTOM_FIELDS_PANEL = "app/components/settings/CustomFieldsPanel.tsx";
const MEMBERS_PANEL = "app/components/settings/MembersPanel.tsx";
const ROLES_PANEL = "app/components/settings/RolesPanel.tsx";
const IMPORT_DIALOG = "app/components/import/ImportDialog.tsx";
const UNAVAILABLE = "app/components/PermissionsUnavailable.tsx";
const UNAVAILABLE_PAGE = "app/components/PermissionsUnavailablePage.tsx";

function source(relativePath: string): string {
    return readFileSync(path.resolve(process.cwd(), relativePath), "utf8");
}

type MessageValue = string | { [key: string]: MessageValue };

function isMessageTree(value: unknown): value is { [key: string]: MessageValue } {
    return typeof value === "object" && value !== null && !Array.isArray(value);
}

/** Reads one leaf string from a locale catalog, so a missing or restructured key fails loudly. */
function message(locale: "en" | "ja", key: string): string {
    const parsed: unknown = JSON.parse(source(`messages/${locale}/errors.json`));
    if (!isMessageTree(parsed)) {
        throw new Error(`messages/${locale}/errors.json is not a JSON object`);
    }
    const scope = parsed.PermissionsUnavailable;
    if (!isMessageTree(scope)) {
        throw new Error(`messages/${locale}/errors.json has no PermissionsUnavailable namespace`);
    }
    const value = scope[key];
    if (typeof value !== "string") {
        throw new Error(`messages/${locale}/errors.json is missing PermissionsUnavailable.${key}`);
    }
    return value;
}

const RESOLVED: PermissionsStatus = "resolved";
const UNRESOLVED: PermissionsStatus = "unavailable";

describe("a failed permission lookup is not a denial", () => {
    it("reports a permission the viewer genuinely lacks as denied", () => {
        expect(checkPermission(RESOLVED, new Set(["REPORT_READ"]), "CUSTOM_FIELD_MANAGE")).toBe("denied");
    });

    it("reports a permission the viewer holds as granted", () => {
        expect(checkPermission(RESOLVED, new Set(["CUSTOM_FIELD_MANAGE"]), "CUSTOM_FIELD_MANAGE")).toBe(
            "granted",
        );
    });

    it("reports an unresolved lookup as unavailable rather than denied", () => {
        expect(checkPermission(UNRESOLVED, new Set(), "CUSTOM_FIELD_MANAGE")).toBe("unavailable");
    });

    it("never lets an unresolved lookup answer granted, even if a set leaked through", () => {
        expect(checkPermission(UNRESOLVED, new Set(["CUSTOM_FIELD_MANAGE"]), "CUSTOM_FIELD_MANAGE")).toBe(
            "unavailable",
        );
    });

});

describe("the app shell publishes the lookup outcome", () => {
    const layout = source(APP_LAYOUT);
    const provider = source(PROVIDER);

    it("still resolves to an empty list when the lookup failed", () => {
        expect(layout).toMatch(/permissionsResult\.ok \? permissionsResult\.data : \[\]/);
    });

    it("carries whether the lookup succeeded alongside it", () => {
        expect(layout).toMatch(/permissionsResult\.ok \? "resolved" : "unavailable"/);
        expect(layout).toContain("status={permissionsStatus}");
    });

    it("derives the boolean hook from the three-way check, so both stay in step", () => {
        expect(provider).toMatch(/usePermissionCheck\(permission\) === "granted"/);
    });
});

describe("a route-level refusal tells the truth about which one it is", () => {
    const panel = source(CUSTOM_FIELDS_PANEL);

    it("answers an unresolved lookup with the permissions-unavailable state", () => {
        expect(panel).toMatch(/if \(manageCheck === "unavailable"\)/);
        expect(panel).toContain("PermissionsUnavailableSection");
    });

    it("reaches that branch before the access-denied one, so a failure is never a denial", () => {
        const unavailableAt = panel.indexOf('if (manageCheck === "unavailable")');
        const deniedAt = panel.indexOf('<AccessDenied variant="inline"');

        expect(unavailableAt).toBeGreaterThan(-1);
        expect(deniedAt).toBeGreaterThan(-1);
        expect(unavailableAt).toBeLessThan(deniedAt);
    });

    it("offers a way out, because the fix for a failed check is to retry", () => {
        expect(panel).toContain("usePermissionsRefresh");
        expect(panel).toMatch(/await refreshPermissions\(\)/);
    });

    it("claims no outcome the retry cannot observe", () => {
        expect(panel).not.toMatch(/refreshPermissions\(\)\)\)/);
        expect(source(PROVIDER)).toContain("refresh: () => Promise<void>");
    });

    it("reuses the shared state rather than hand-rolling a card", () => {
        expect(panel).toContain('from "@/app/components/PermissionsUnavailable"');
        expect(source(UNAVAILABLE_PAGE)).toContain("@/app/components/PermissionsUnavailable");
    });

    it("keeps the shared state free of hooks, so route-level dead ends ship no client bundle", () => {
        const unavailable = source(UNAVAILABLE);

        expect(unavailable).not.toMatch(/^\s*['"]use client['"]/m);
        expect(unavailable).not.toMatch(/\buse[A-Z]\w*\s*\(/);
    });

    it("ships the copy it renders in both locales, actually translated", () => {
        for (const key of ["title", "sectionBody", "retry", "retrying"]) {
            expect(message("en", key).length).toBeGreaterThan(0);
            expect(message("ja", key)).not.toBe(message("en", key));
            expect(message("ja", key)).toMatch(/[^\x00-\x7F]/);
        }
    });

    it("says the check failed rather than implying a verdict", () => {
        expect(message("en", "title")).toMatch(/couldn't confirm/i);
        expect(message("en", "sectionBody")).not.toMatch(/access denied|don't have access/i);
    });
});

describe("a stale permission snapshot is re-read, not waited out", () => {
    const provider = source(PROVIDER);

    it("treats a changed permission set as drift", () => {
        expect(permissionsDrifted(RESOLVED, new Set(["A", "B"]), ["A"])).toBe(true);
        expect(permissionsDrifted(RESOLVED, new Set(["A"]), ["A", "B"])).toBe(true);
        expect(permissionsDrifted(RESOLVED, new Set(["A"]), ["B"])).toBe(true);
    });

    it("does not mistake a reordered response for a role change", () => {
        expect(permissionsDrifted(RESOLVED, new Set(["A", "B", "C"]), ["C", "A", "B"])).toBe(false);
        expect(permissionsDrifted(RESOLVED, new Set(["A", "B"]), ["B", "A", "A"])).toBe(false);
    });

    it("treats any answer at all as drift when the published state had none", () => {
        expect(permissionsDrifted(UNRESOLVED, new Set(), [])).toBe(true);
        expect(permissionsDrifted(UNRESOLVED, new Set(), ["A"])).toBe(true);
    });

    it("leaves an unchanged answer alone, so an ordinary tab-back costs one small read", () => {
        expect(permissionsDrifted(RESOLVED, new Set(), [])).toBe(false);
        expect(permissionsDrifted(RESOLVED, new Set(["A", "B"]), ["A", "B"])).toBe(false);
    });

    it("re-reads the permission list on regaining focus, both ways a tab can regain it", () => {
        expect(provider).toContain('window.addEventListener("focus", refreshWhenVisible)');
        expect(provider).toContain('document.addEventListener("visibilitychange", refreshWhenVisible)');
        expect(provider).toContain('window.removeEventListener("focus", refreshWhenVisible)');
        expect(provider).toContain('document.removeEventListener("visibilitychange", refreshWhenVisible)');
    });

    it("re-reads it for real instead of polling or expiring a cache", () => {
        expect(provider).toContain("await getEffectivePermissions()");
        expect(provider).not.toMatch(/\bsetInterval\s*\(|\bsetTimeout\s*\(/);
    });

    it("re-renders the server tree only when the answer actually changed", () => {
        expect(provider).toMatch(
            /if \(permissionsDrifted\(status, granted, probed\)\) router\.refresh\(\)/,
        );
    });

    it("leaves a good snapshot alone when the probe itself fails", () => {
        const refresh = provider.slice(
            provider.indexOf("const refresh = useCallback"),
            provider.indexOf("useEffect(()"),
        );

        expect(refresh).toMatch(/catch \{/);
        expect(refresh).not.toMatch(/catch \{[\s\S]*?router\.refresh\(\)/);
    });

    it("shares one in-flight probe rather than stacking them", () => {
        expect(provider).toContain("if (probe.current !== null) return probe.current");
    });

    it("clears the in-flight probe after the assignment can never be undone by it", () => {
        expect(provider).toMatch(/\}\)\(\)\.finally\(\(\) => \{\s*probe\.current = null;\s*\}\);/);
    });
});

describe("a role change re-seeds the shell it was made from", () => {
    const members = source(MEMBERS_PANEL);
    const roles = source(ROLES_PANEL);

    /** The body of one handler, so an assertion cannot reach past it into its neighbour. */
    function handler(file: string, declaration: string, next: string): string {
        const start = file.indexOf(declaration);
        const end = file.indexOf(next, start);

        expect(start).toBeGreaterThan(-1);
        expect(end).toBeGreaterThan(start);
        return file.slice(start, end);
    }

    const changeRole = handler(members, "const changeRole = async", "const assignCustom = async");
    const assignCustom = handler(members, "const assignCustom = async", "const confirmRemove = async");
    const submitRole = handler(roles, "const submitRole = async", "const confirmRemove = async");
    const deleteRole = handler(roles, "const confirmRemove = async", "if (accessDenied)");

    it("refreshes after a member's built-in role changes", () => {
        expect(changeRole).toContain("await updateMemberRole(");
        expect(changeRole).toContain("router.refresh()");
    });

    it("refreshes after a member is assigned a custom role", () => {
        expect(assignCustom).toContain("await assignMemberCustomRole(");
        expect(assignCustom).toContain("router.refresh()");
    });

    it("refreshes after a custom role is created or its permission set is edited", () => {
        expect(submitRole).toContain("await updateWorkspaceRole(");
        expect(submitRole).toContain("await createWorkspaceRole(");
        expect(submitRole.match(/router\.refresh\(\)/g) ?? []).toHaveLength(1);
        expect(submitRole.indexOf("router.refresh()")).toBeGreaterThan(
            submitRole.indexOf("await createWorkspaceRole("),
        );
    });

    it("refreshes after a custom role is deleted", () => {
        expect(deleteRole).toContain("await deleteWorkspaceRole(");
        expect(deleteRole).toContain("router.refresh()");
    });

    it("does not refresh from a failed mutation, which changed nothing", () => {
        for (const body of [changeRole, assignCustom, submitRole, deleteRole]) {
            expect(body).not.toMatch(/catch \([\s\S]*?router\.refresh\(\)/);
        }
    });
});

describe("the import wizard offers only mappings the viewer may commit", () => {
    const dialog = source(IMPORT_DIALOG);

    it("gates the create-custom-field option on the permission the backend enforces", () => {
        expect(dialog).toMatch(/usePermission\(['"]CUSTOM_FIELD_MANAGE['"]\)/);
        expect(dialog).toMatch(
            /\{canCreateCustomField && \(\s*<SelectItem value=\{CREATE_CUSTOM_FIELD\}>/,
        );
    });

    it("resolves a create choice the viewer may no longer make to an ignored column", () => {
        expect(resolveColumnTarget(CREATE_CUSTOM_FIELD, false)).toBe(IGNORE_COLUMN);
        expect(resolveColumnTarget(CREATE_CUSTOM_FIELD, true)).toBe(CREATE_CUSTOM_FIELD);
    });

    it("leaves every standard mapping alone regardless of the permission", () => {
        for (const canCreate of [true, false]) {
            expect(resolveColumnTarget("name", canCreate)).toBe("name");
            expect(resolveColumnTarget("tags", canCreate)).toBe("tags");
            expect(resolveColumnTarget(IGNORE_COLUMN, canCreate)).toBe(IGNORE_COLUMN);
            expect(resolveColumnTarget(undefined, canCreate)).toBe(IGNORE_COLUMN);
        }
    });

    it("resolves the target everywhere a mapping is built, counted or displayed", () => {
        const uses = dialog.match(/resolveColumnTarget\(/g) ?? [];

        expect(uses.length).toBeGreaterThanOrEqual(3);
        expect(dialog).toMatch(/const target = resolveColumnTarget\(col\?\.target, canCreateCustomField\)/);
        expect(dialog).toContain("<Select value={target}");
    });

    it("never commits a create mapping the server would refuse", () => {
        const buildMapping = dialog.slice(
            dialog.indexOf("function buildMapping()"),
            dialog.indexOf("const mappedCount"),
        );

        expect(buildMapping).toContain("resolveColumnTarget(col?.target, canCreateCustomField)");
        expect(buildMapping).not.toMatch(/col\.target === CREATE_CUSTOM_FIELD/);
    });
});
