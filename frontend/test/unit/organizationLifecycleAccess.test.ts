import { describe, expect, it } from "vitest";

import { organizationLifecycleAccess } from "@/app/lib/organizationLifecycleAccess";

describe("organization lifecycle UI access", () => {
    it("gives a workspace owner with no organization role no lifecycle surface", () => {
        expect(organizationLifecycleAccess(null)).toEqual({
            canExport: false,
            canTeardown: false,
        });
    });

    it("allows organization admins to export but not tear down", () => {
        expect(organizationLifecycleAccess("admin")).toEqual({
            canExport: true,
            canTeardown: false,
        });
    });

    it("allows organization owners to export and tear down", () => {
        expect(organizationLifecycleAccess("owner")).toEqual({
            canExport: true,
            canTeardown: true,
        });
    });
});
