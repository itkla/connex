import { readFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";

import { grantedCapabilities, resolveCan } from "@/app/lib/actions/permissions";
import { WORKSPACE_CAPABILITIES } from "@/app/lib/actions/types";
import type { OrgRole, Workspace, WorkspaceRole } from "@/app/lib/types";

const PERMISSIONS = "app/lib/actions/permissions.ts";
const TYPES = "app/lib/actions/types.ts";

function source(relativePath: string): string {
    return readFileSync(path.resolve(process.cwd(), relativePath), "utf8");
}

function workspace(role: WorkspaceRole, orgRole: OrgRole | null = null): Workspace {
    return {
        id: 1,
        name: "Acme",
        slug: "acme",
        role,
        orgId: 1,
        orgName: "Acme Inc",
        orgRole,
    };
}

function everyCapabilityAnsweredTrue(subject: Workspace | null): string[] {
    const can = resolveCan(subject);
    return WORKSPACE_CAPABILITIES.filter((capability) => can(capability));
}

describe("an unrecognised permission key is refused, not granted", () => {
    it("does not grant a key the client has no rule for, even to an owner", () => {
        expect(grantedCapabilities(workspace("owner", "owner")).has("REPORT_READ")).toBe(false);
    });

    it("does not grant a misspelling of a capability it does recognise", () => {
        const granted = grantedCapabilities(workspace("owner", "owner"));

        expect(granted.has("MEMBER_MANAGE")).toBe(true);
        expect(granted.has("MEMBER_MANGE")).toBe(false);
        expect(granted.has("member_manage")).toBe(false);
        expect(granted.has("ORGANISATION_VIEW")).toBe(false);
    });

    it("refuses an unrecognised key from every role, including one holding nothing", () => {
        for (const subject of [
            workspace("owner", "owner"),
            workspace("admin", "admin"),
            workspace("member"),
            null,
        ]) {
            expect(grantedCapabilities(subject).has("CUSTOM_FIELD_MANAGE")).toBe(false);
            expect(grantedCapabilities(subject).has("")).toBe(false);
        }
    });

    it("answers from the table alone, with no fallthrough left to grant an unknown key", () => {
        const permissions = source(PERMISSIONS);
        const predicate = permissions.slice(permissions.indexOf("export function resolveCan"));

        expect(predicate).toContain("return (permission) => granted.has(permission);");
        expect(predicate).not.toMatch(/\?[\s\S]*:\s*true/);
        expect(predicate).not.toContain("true");
    });

    it("closes the key space at the type level, so a typo cannot be written", () => {
        const types = source(TYPES);

        expect(types).toContain("export type PermissionCheck = (permission: WorkspaceCapability) => boolean");
        expect(types).not.toContain("export type PermissionCheck = (permission: string) => boolean");
    });
});

describe("the role-derived capability table", () => {
    it("grants an owner workspace administration but not organization access on its own", () => {
        expect(everyCapabilityAnsweredTrue(workspace("owner"))).toEqual(["WORKSPACE_MANAGE", "MEMBER_MANAGE"]);
    });

    it("grants an admin the same workspace administration as an owner", () => {
        expect(everyCapabilityAnsweredTrue(workspace("admin"))).toEqual(["WORKSPACE_MANAGE", "MEMBER_MANAGE"]);
    });

    it("grants a plain member nothing", () => {
        expect(everyCapabilityAnsweredTrue(workspace("member"))).toEqual([]);
    });

    it("grants nothing at all before a workspace is selected", () => {
        expect(everyCapabilityAnsweredTrue(null)).toEqual([]);
    });

    it("adds organization access from the organization role, independently of the workspace one", () => {
        expect(everyCapabilityAnsweredTrue(workspace("member", "admin"))).toEqual(["ORGANIZATION_VIEW"]);
        expect(everyCapabilityAnsweredTrue(workspace("owner", "owner"))).toEqual([
            "ORGANIZATION_VIEW",
            "WORKSPACE_MANAGE",
            "MEMBER_MANAGE",
        ]);
    });
});

describe("an action needing no capability says so by omission", () => {
    it("keeps isAvailable optional, so ungated is expressed without an unrecognised key", () => {
        const types = source(TYPES);

        expect(types).toContain("isAvailable?: (context: ActionContext) => boolean");
        expect(types).toContain("When omitted the action is always available");
    });

    it("gates every registered action on a key the table actually carries", () => {
        const registry = source("app/lib/actions/seedActions.ts");
        const keys = [...registry.matchAll(/context\.can\(["']([^"']+)["']\)/g)].map((match) => match[1]);

        expect(keys.length).toBeGreaterThan(0);
        for (const key of keys) {
            expect(WORKSPACE_CAPABILITIES).toContain(key);
        }
    });
});
