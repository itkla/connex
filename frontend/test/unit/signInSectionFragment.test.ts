import { describe, expect, it } from "vitest";

import { withSectionFragment } from "@/app/lib/sessionExpiry";

describe("the section a retired settings address forwarded to survives sign-in", () => {
    it("re-attaches the section the sign-in page kept to a return path that lost it", () => {
        expect(withSectionFragment("/settings/workspace/people", "#roles")).toBe(
            "/settings/workspace/people#roles",
        );
        expect(withSectionFragment("/settings/workspace/people?tab=1", "#ai-provider")).toBe(
            "/settings/workspace/people?tab=1#ai-provider",
        );
    });

    it("keeps a return path's own fragment rather than replacing it", () => {
        expect(withSectionFragment("/settings/workspace/crm#qualification", "#roles")).toBe(
            "/settings/workspace/crm#qualification",
        );
    });

    it("carries only a plain section slug, never anything else a fragment might hold", () => {
        for (const fragment of ["", "#", "#Roles", "#a b", "#access_token=x", "#/settings", "#-roles", "#roles-"]) {
            expect(withSectionFragment("/settings/workspace/people", fragment), fragment).toBe(
                "/settings/workspace/people",
            );
        }
    });
});
