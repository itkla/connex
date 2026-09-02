import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";

import TodayPriorities from "@/app/components/dashboard/TodayPriorities";
import type { CookieResult } from "@/app/lib/api";
import type { WorkItemSummary } from "@/app/lib/types";

vi.mock("next/link", async () => {
    const React = await import("react");
    return {
        default: ({ href, children }: { href: string; children: React.ReactNode }) =>
            React.createElement("a", { href }, children),
    };
});

vi.mock("next-intl/server", () => ({
    getTranslations: async () => (key: string, values?: Record<string, unknown>) =>
        values ? `${key}:${JSON.stringify(values)}` : key,
}));

function summaryOf(overrides: Partial<WorkItemSummary> = {}): WorkItemSummary {
    return {
        knownTotal: 0,
        knownCritical: 0,
        totalsComplete: true,
        availability: "available",
        sourceStatuses: [],
        asOf: "2026-09-01T00:00:00Z",
        ...overrides,
    };
}

async function render(summary: CookieResult<WorkItemSummary>): Promise<string> {
    return renderToStaticMarkup(await TodayPriorities({ summary }));
}

describe("Home My Work count", () => {
    it("names the all-clear for a fresh, complete zero instead of vanishing", async () => {
        const html = await render({ ok: true, data: summaryOf() });
        expect(html).toContain("allClear");
        expect(html).toContain('href="/me"');
    });

    it("links exact counts and the critical chip to /me", async () => {
        const html = await render({ ok: true, data: summaryOf({ knownTotal: 4, knownCritical: 2 }) });
        expect(html).toContain('href="/me"');
        expect(html).toContain("myWorkCount");
        expect(html).toContain("myWorkCritical");
        expect(html).not.toContain("atLeast");
    });

    it("degrades to at-least language when totals are incomplete", async () => {
        const html = await render({
            ok: true,
            data: summaryOf({ knownTotal: 3, totalsComplete: false, availability: "partial" }),
        });
        expect(html).toContain("atLeast");
        expect(html).toContain("partial");
        expect(html).not.toContain("myWorkCount:");
    });

    it("never renders an incomplete zero as an all-clear", async () => {
        const html = await render({
            ok: true,
            data: summaryOf({ availability: "partial", totalsComplete: false }),
        });
        expect(html).not.toContain("allClear");
        expect(html).toContain("atLeast");
    });

    it("qualifies the critical count when the projection is incomplete", async () => {
        const html = await render({
            ok: true,
            data: summaryOf({ knownTotal: 3, knownCritical: 2, totalsComplete: false, availability: "partial" }),
        });
        expect(html).toContain("myWorkCriticalAtLeast");
        expect(html).not.toContain("myWorkCritical:");
    });

    it("clamps contradictory counts defensively", async () => {
        const html = await render({
            ok: true,
            data: summaryOf({ knownTotal: -2, knownCritical: -1 }),
        });
        expect(html).toContain("allClear");
    });

    it("says the count is unavailable rather than staying silent on failure", async () => {
        const failed = await render({ ok: false });
        expect(failed).toContain("unavailable");
        expect(failed).toContain('href="/me"');
        const down = await render({ ok: true, data: summaryOf({ availability: "unavailable" }) });
        expect(down).toContain("unavailable");
    });
});
