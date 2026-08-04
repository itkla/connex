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
});
