import { createElement, type PropsWithChildren } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";

import RelationshipDecay from "@/app/components/overview/analytics/RelationshipDecay";
import type { WarmthDecayCounts } from "@/app/lib/types";

vi.mock("next-intl", () => ({
    useTranslations: () => (key: string, values?: Record<string, unknown>) => (
        values ? `${key}(${JSON.stringify(values)})` : key
    ),
}));

vi.mock("next/link", () => ({
    default: ({ children, href, ...rest }: PropsWithChildren<{ href: string }>) =>
        createElement("a", { href, ...rest }, children),
}));

/** Every `href` the rendered figure offers, in document order. */
function hrefs(markup: string): string[] {
    return Array.from(markup.matchAll(/href="([^"]+)"/g), (match) => match[1]);
}

/** The counts rendered beside each horizon bar, in document order. */
function barCounts(markup: string): string[] {
    return Array.from(
        markup.matchAll(/tabular-nums text-muted-foreground[^>]*>(\d+)</g),
        (match) => match[1],
    );
}

function render(decay: WarmthDecayCounts): string {
    return renderToStaticMarkup(createElement(RelationshipDecay, { decay }));
}

describe("the relationship-decay figure's drill-through", () => {
    const decay: WarmthDecayCounts = { soon: 5, mid: 3, later: 2 };

    it("lands each horizon on the contacts browser filtered to that horizon", () => {
        const markup = render(decay);

        expect(hrefs(markup)).toEqual([
            "/radar?family=relationship_decay",
            "/records/contacts?goesColdWithinDays=30",
            "/records/contacts?goesColdWithinDays=60",
            "/records/contacts?goesColdWithinDays=90",
        ]);
    });

    it("shows each horizon the count its own link returns, not the disjoint bucket count", () => {
        const markup = render(decay);

        expect(barCounts(markup)).toEqual(["5", "8", "10"]);
    });

    it("closes the widest horizon on the same total the headline reports", () => {
        const markup = render(decay);
        const counts = barCounts(markup);

        expect(counts[counts.length - 1]).toBe("10");
        expect(markup).toContain(">10</div>");
    });

    it("names the destination and its size for a reader who cannot see the bar", () => {
        const markup = render(decay);

        expect(markup).toContain("bucketDrillThrough(");
        expect(markup).toContain("&quot;count&quot;:8");
    });

    it("offers no link at all when nothing is predicted to cool off", () => {
        const markup = render({ soon: 0, mid: 0, later: 0 });

        expect(hrefs(markup)).toEqual([]);
        expect(markup).toContain("empty");
    });
});
