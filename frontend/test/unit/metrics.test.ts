import { describe, expect, it } from "vitest";
import {
    buildCalendarBuckets,
    buildTimeBuckets,
    clampGranularity,
    computeKpis,
    isGranularity,
    isRangeKey,
    localIsoDate,
    periodStartOf,
    projectionWindow,
    resolveAnalyticsWindow,
} from "@/app/components/overview/analytics/metrics";
import type { Deal } from "@/app/lib/types";

const DAY = 86400000;

/** 2026-07-15T12:00:00Z — a fixed Wednesday anchor so every assertion is deterministic. */
const NOW = Date.UTC(2026, 6, 15, 12, 0, 0);

function deal(overrides: Partial<Deal>): Deal {
    return {
        id: 1,
        name: "Deal",
        value: 0,
        actualValue: 0,
        currency: "USD",
        pipeline: null,
        stage: null,
        position: 0,
        company: null,
        createdAt: "2026-07-01 00:00:00",
        updatedAt: "2026-07-01 00:00:00",
        ...overrides,
    };
}

function iso(ms: number): string {
    return new Date(ms).toISOString().replace("T", " ").slice(0, 19);
}

describe("isRangeKey / isGranularity", () => {
    it("accepts every documented range key and rejects everything else", () => {
        for (const key of ["30d", "90d", "12m", "this-week", "this-month", "last-month", "this-quarter"]) {
            expect(isRangeKey(key)).toBe(true);
        }
        expect(isRangeKey(null)).toBe(false);
        expect(isRangeKey("7d")).toBe(false);
        expect(isRangeKey("")).toBe(false);
    });

    it("accepts only the three grains", () => {
        expect(isGranularity("day")).toBe(true);
        expect(isGranularity("week")).toBe(true);
        expect(isGranularity("month")).toBe(true);
        expect(isGranularity("year")).toBe(false);
        expect(isGranularity(null)).toBe(false);
    });
});

describe("clampGranularity", () => {
    it("passes a supported choice through", () => {
        expect(clampGranularity("90d", "month")).toBe("month");
        expect(clampGranularity("this-month", "week")).toBe("week");
    });

    it("falls back to the range default when the choice is unsupported", () => {
        expect(clampGranularity("12m", "day")).toBe("month");
        expect(clampGranularity("this-week", "month")).toBe("day");
        expect(clampGranularity("30d", "month")).toBe("week");
    });

    it("falls back to the range default when the choice is null", () => {
        expect(clampGranularity("30d", null)).toBe("week");
        expect(clampGranularity("this-quarter", null)).toBe("week");
    });
});

describe("resolveAnalyticsWindow", () => {
    it("resolves rolling ranges to inclusive windows ending today", () => {
        expect(resolveAnalyticsWindow("30d", NOW, "UTC")).toEqual({ from: "2026-06-16", to: "2026-07-15" });
        expect(resolveAnalyticsWindow("90d", NOW, "UTC")).toEqual({ from: "2026-04-17", to: "2026-07-15" });
        expect(resolveAnalyticsWindow("12m", NOW, "UTC")).toEqual({ from: "2025-08-01", to: "2026-07-15" });
    });

    it("starts this-week on Monday and spans seven days", () => {
        expect(resolveAnalyticsWindow("this-week", NOW, "UTC")).toEqual({ from: "2026-07-13", to: "2026-07-19" });
        const sundayNoon = Date.UTC(2026, 6, 19, 12, 0, 0);
        expect(resolveAnalyticsWindow("this-week", sundayNoon, "UTC")).toEqual({
            from: "2026-07-13",
            to: "2026-07-19",
        });
        const mondayMidnight = Date.UTC(2026, 6, 13, 0, 0, 0);
        expect(resolveAnalyticsWindow("this-week", mondayMidnight, "UTC")).toEqual({
            from: "2026-07-13",
            to: "2026-07-19",
        });
    });

    it("covers whole calendar months and quarters", () => {
        expect(resolveAnalyticsWindow("this-month", NOW, "UTC")).toEqual({ from: "2026-07-01", to: "2026-07-31" });
        expect(resolveAnalyticsWindow("last-month", NOW, "UTC")).toEqual({ from: "2026-06-01", to: "2026-06-30" });
        expect(resolveAnalyticsWindow("this-quarter", NOW, "UTC")).toEqual({ from: "2026-07-01", to: "2026-09-30" });
    });

    it("handles a year boundary for last-month", () => {
        const january = Date.UTC(2026, 0, 10);
        expect(resolveAnalyticsWindow("last-month", january, "UTC")).toEqual({
            from: "2025-12-01",
            to: "2025-12-31",
        });
    });

    it("shifts the local date for a legacy fixed-offset timezone", () => {
        const lateUtc = Date.UTC(2026, 6, 15, 20, 0, 0);
        expect(resolveAnalyticsWindow("this-week", lateUtc, "UTC+9").to).toBe("2026-07-19");
        expect(localIsoDate(lateUtc, "UTC+9")).toBe("2026-07-16");
        expect(localIsoDate(lateUtc, "UTC")).toBe("2026-07-15");
    });

    it("resolves IANA timezones and falls back to UTC for garbage", () => {
        expect(localIsoDate(Date.UTC(2026, 6, 15, 20, 0, 0), "Asia/Tokyo")).toBe("2026-07-16");
        expect(localIsoDate(NOW, "Not/AZone")).toBe("2026-07-15");
    });
});

describe("periodStartOf", () => {
    it("returns the Monday of the containing ISO week", () => {
        expect(periodStartOf("2026-07-15", "week")).toBe("2026-07-13");
        expect(periodStartOf("2026-07-13", "week")).toBe("2026-07-13");
        expect(periodStartOf("2026-07-19", "week")).toBe("2026-07-13");
    });

    it("returns the first of the month for month grain and identity for day grain", () => {
        expect(periodStartOf("2026-07-15", "month")).toBe("2026-07-01");
        expect(periodStartOf("2026-07-15", "day")).toBe("2026-07-15");
    });
});

describe("projectionWindow", () => {
    it("leaves calendar presets untouched", () => {
        const window = { from: "2026-07-01", to: "2026-07-31" };
        expect(projectionWindow(window, "this-month", "week")).toEqual(window);
    });

    it("extends rolling ranges to the end of the third following period", () => {
        const window = resolveAnalyticsWindow("30d", NOW, "UTC");
        const projected = projectionWindow(window, "30d", "week");
        expect(projected.from).toBe(window.from);
        expect(projected.to).toBe("2026-08-09");
    });

    it("extends day grain by exactly three days past the window end", () => {
        const projected = projectionWindow({ from: "2026-01-01", to: "2026-12-31" }, "12m", "day");
        expect(projected.to).toBe("2027-01-03");
    });
});

describe("buildTimeBuckets", () => {
    it("builds 12 contiguous month buckets for 12m", () => {
        const buckets = buildTimeBuckets("12m", NOW, "en-US");
        expect(buckets).toHaveLength(12);
        for (let i = 1; i < buckets.length; i++) {
            expect(buckets[i].start).toBe(buckets[i - 1].end);
        }
        const last = new Date(buckets[11].start);
        expect(last.getMonth()).toBe(6);
        expect(last.getFullYear()).toBe(2026);
    });

    it("builds 6 equal buckets for 30d and 9 for 90d", () => {
        const b30 = buildTimeBuckets("30d", NOW, "en-US");
        expect(b30).toHaveLength(6);
        expect(b30[0].start).toBe(NOW - 30 * DAY);
        expect(b30[5].end).toBe(NOW);
        expect(buildTimeBuckets("90d", NOW, "en-US")).toHaveLength(9);
    });
});

describe("buildCalendarBuckets", () => {
    it("returns empty for an inverted or unparseable window", () => {
        expect(buildCalendarBuckets({ from: "2026-07-10", to: "2026-07-01" }, "day", "en-US")).toEqual([]);
        expect(buildCalendarBuckets({ from: "garbage", to: "2026-07-01" }, "day", "en-US")).toEqual([]);
    });

    it("enumerates one bucket per day inclusive of both ends", () => {
        const buckets = buildCalendarBuckets({ from: "2026-07-01", to: "2026-07-05" }, "day", "en-US");
        expect(buckets).toHaveLength(5);
        expect(buckets[0].end - buckets[0].start).toBe(DAY);
    });

    it("aligns week buckets to Monday and covers the window", () => {
        const buckets = buildCalendarBuckets({ from: "2026-07-15", to: "2026-07-21" }, "week", "en-US");
        expect(buckets).toHaveLength(2);
        expect(new Date(buckets[0].start).getDay()).toBe(1);
    });

    it("caps enumeration at 400 buckets", () => {
        const buckets = buildCalendarBuckets({ from: "2020-01-01", to: "2026-12-31" }, "day", "en-US");
        expect(buckets).toHaveLength(400);
    });
});

describe("computeKpis", () => {
    it("returns all-zero KPIs with flat deltas for no deals", () => {
        const kpis = computeKpis([], NOW, 30);
        const byKey = Object.fromEntries(kpis.map((k) => [k.key, k]));
        expect(byKey.wonRevenue.value).toBe(0);
        expect(byKey.wonRevenue.delta).toBe(0);
        expect(byKey.winRate.delta).toBeNull();
        expect(byKey.avgCycle.delta).toBeNull();
        expect(byKey.wonRevenue.series).toHaveLength(12);
    });

    it("routes created value to newPipeline and actualValue to wonRevenue", () => {
        const kpis = computeKpis(
            [
                deal({ createdAt: iso(NOW - 5 * DAY), value: 1000 }),
                deal({
                    createdAt: iso(NOW - 20 * DAY),
                    closedAt: iso(NOW - 2 * DAY),
                    won: true,
                    value: 500,
                    actualValue: 750,
                }),
            ],
            NOW,
            30,
        );
        const byKey = Object.fromEntries(kpis.map((k) => [k.key, k]));
        expect(byKey.newPipeline.value).toBe(1500);
        expect(byKey.wonRevenue.value).toBe(750);
        expect(byKey.wonRevenue.series.reduce((a, b) => a + b, 0)).toBe(750);
    });

    it("computes win rate over closed deals only and deltas only when both periods closed deals", () => {
        const kpis = computeKpis(
            [
                deal({ createdAt: iso(NOW - 25 * DAY), closedAt: iso(NOW - 1 * DAY), won: true }),
                deal({ createdAt: iso(NOW - 25 * DAY), closedAt: iso(NOW - 2 * DAY), won: false }),
                deal({ createdAt: iso(NOW - 25 * DAY), closedAt: iso(NOW - 3 * DAY), won: false }),
                deal({ createdAt: iso(NOW - 25 * DAY) }),
            ],
            NOW,
            30,
        );
        const winRate = kpis.find((k) => k.key === "winRate");
        expect(winRate?.value).toBeCloseTo(1 / 3);
        expect(winRate?.delta).toBeNull();
    });

    it("compares against the immediately preceding window", () => {
        const kpis = computeKpis(
            [
                deal({
                    createdAt: iso(NOW - 50 * DAY),
                    closedAt: iso(NOW - 40 * DAY),
                    won: true,
                    actualValue: 100,
                }),
                deal({
                    createdAt: iso(NOW - 20 * DAY),
                    closedAt: iso(NOW - 10 * DAY),
                    won: true,
                    actualValue: 300,
                }),
            ],
            NOW,
            30,
        );
        const won = kpis.find((k) => k.key === "wonRevenue");
        expect(won?.value).toBe(300);
        expect(won?.delta).toBe(2);
    });

    it("reports a null delta when the previous period is zero but the current is not", () => {
        const kpis = computeKpis(
            [deal({ createdAt: iso(NOW - 5 * DAY), value: 100 })],
            NOW,
            30,
        );
        expect(kpis.find((k) => k.key === "newPipeline")?.delta).toBeNull();
    });

    it("ignores deals closed in the future and cycles where closed precedes created", () => {
        const kpis = computeKpis(
            [
                deal({ createdAt: iso(NOW - 5 * DAY), closedAt: iso(NOW + DAY), won: true, actualValue: 999 }),
                deal({ createdAt: iso(NOW - 2 * DAY), closedAt: iso(NOW - 5 * DAY), won: true, actualValue: 50 }),
            ],
            NOW,
            30,
        );
        const byKey = Object.fromEntries(kpis.map((k) => [k.key, k]));
        expect(byKey.wonRevenue.value).toBe(50);
        expect(byKey.avgCycle.value).toBe(0);
    });

    it("computes the average sales cycle in days and marks it bad-when-up", () => {
        const kpis = computeKpis(
            [
                deal({ createdAt: iso(NOW - 20 * DAY), closedAt: iso(NOW - 10 * DAY), won: true }),
                deal({ createdAt: iso(NOW - 25 * DAY), closedAt: iso(NOW - 5 * DAY), won: true }),
            ],
            NOW,
            30,
        );
        const cycle = kpis.find((k) => k.key === "avgCycle");
        expect(cycle?.value).toBe(15);
        expect(cycle?.goodWhenUp).toBe(false);
    });

    it("clamps bucket assignment to the series bounds", () => {
        const kpis = computeKpis(
            [deal({ createdAt: iso(NOW - 30 * DAY), value: 40 })],
            NOW,
            30,
        );
        const pipeline = kpis.find((k) => k.key === "newPipeline");
        expect(pipeline?.series[0]).toBe(40);
    });
});
