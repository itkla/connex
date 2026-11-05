import { describe, expect, it } from "vitest";
import type { DealMetrics } from "@/app/lib/types";
import { currencyCountsForDeals, currencyCountsForMetrics, resolveCurrency } from "@/app/components/overview/analytics/currencyScope";

function metrics(entries: Array<[string, number, number]>): DealMetrics {
    return {
        totalCount: entries.reduce((total, [, open, closed]) => total + open + closed, 0),
        byCurrency: entries.map(([currency, openCount, closedCount]) => ({
            currency, openCount, closedCount, openValue: 0, closedForecast: 0, closedRevenue: 0, wonCount: 0, lostCount: 0,
        })),
    };
}

describe("shared Analytics currency scope", () => {
    it("counts both open and closed deals and defaults to the most common currency", () => {
        const counts = currencyCountsForMetrics(metrics([["JPY", 6, 0], ["USD", 4, 6]]));
        expect([...counts]).toEqual([["JPY", 6], ["USD", 10]]);
        expect(resolveCurrency(counts, null)).toBe("USD");
    });

    it("honors a valid selection and falls back for unknown or empty selections", () => {
        const counts = currencyCountsForMetrics(metrics([["JPY", 6, 0], ["USD", 10, 0]]));
        expect(resolveCurrency(counts, "JPY")).toBe("JPY");
        expect(resolveCurrency(counts, "EUR")).toBe("USD");
        expect(resolveCurrency(counts, "")).toBe("USD");
    });

    it("preserves Analytics' first-entry tie break", () => {
        expect(resolveCurrency(currencyCountsForMetrics(metrics([["JPY", 3, 3], ["USD", 6, 0]])), null)).toBe("JPY");
    });

    it("keeps single-currency and empty defaults", () => {
        expect(resolveCurrency(currencyCountsForMetrics(metrics([["JPY", 1, 0]])), null)).toBe("JPY");
        expect(resolveCurrency(currencyCountsForMetrics(metrics([])), null)).toBe("USD");
    });

    it("rebuilds the same counts and default from loaded deals when metrics are unavailable", () => {
        const deals = [
            ...Array.from({ length: 10 }, () => ({ currency: "USD" })),
            ...Array.from({ length: 6 }, () => ({ currency: "JPY" })),
        ];
        const fallback = currencyCountsForDeals(deals);
        expect(fallback).toEqual(currencyCountsForMetrics(metrics([["JPY", 5, 1], ["USD", 7, 3]])));
        expect(resolveCurrency(fallback, null)).toBe("USD");
        expect(resolveCurrency(fallback, "JPY")).toBe("JPY");
    });

    it("preserves the metrics endpoint's currency ordering for ties and the empty fallback", () => {
        expect(resolveCurrency(currencyCountsForDeals([{ currency: "USD" }, { currency: "JPY" }]), null)).toBe("JPY");
        expect(resolveCurrency(currencyCountsForDeals([]), null)).toBe("USD");
    });
});
