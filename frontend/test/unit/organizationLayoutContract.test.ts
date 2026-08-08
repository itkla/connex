import { readFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";

const LAYOUT = readFileSync(
    path.resolve(process.cwd(), "app/components/organization/OrganizationLayoutPanel.tsx"),
    "utf8",
);
const OVERVIEW = readFileSync(
    path.resolve(process.cwd(), "app/components/organization/OrganizationOverviewPanel.tsx"),
    "utf8",
);
const LIFECYCLE = readFileSync(
    path.resolve(process.cwd(), "app/components/organization/OrganizationLifecyclePanel.tsx"),
    "utf8",
);

describe("organization layout presentation contract", () => {
    it("uses a task-adaptive mobile list instead of a horizontally scrolling table", () => {
        expect(LAYOUT).toContain('className="divide-y divide-border md:hidden"');
        expect(LAYOUT).toContain('className="hidden md:block"');
        expect(LAYOUT).not.toContain("overflow-x-auto");
        expect(LAYOUT).not.toContain("min-w-2xl");
    });

    it("uses the shared bounded failure state inside a section boundary", () => {
        expect(OVERVIEW).toContain('from "@/app/components/SectionBoundary"');
        expect(OVERVIEW).toContain('from "@/app/components/SectionUnavailable"');
        expect(OVERVIEW).toContain("<SectionBoundary");
        expect(OVERVIEW).toContain("<SectionUnavailable");
    });

    it("keeps interface icons on the Heroicons set", () => {
        expect(LAYOUT).toContain("ArrowPathIcon");
        expect(LAYOUT).not.toContain("lucide-react");
    });

    it("gates destructive controls on organization authority rather than workspace ownership", () => {
        expect(OVERVIEW).toContain("if (orgRole === null || state.accessDenied)");
        expect(OVERVIEW).toContain("orgRole={orgRole}");
        expect(LIFECYCLE).toContain("access.canTeardown ?");
        expect(LIFECYCLE).not.toContain('activeWorkspace.role === "owner"');
        expect(LIFECYCLE).not.toContain('workspace.role === "owner"');
        expect(LIFECYCLE).toContain("confirmation === teardownTarget.slug");
        expect(LIFECYCLE).not.toContain("confirmation.trim()");
    });

    it("keeps the product open while the browser handles the streamed export", () => {
        expect(LIFECYCLE).toContain('target="_blank"');
        expect(LIFECYCLE).toContain('rel="noopener noreferrer"');
        expect(LIFECYCLE).not.toContain("window.location.assign(downloadPath)");
    });
});
