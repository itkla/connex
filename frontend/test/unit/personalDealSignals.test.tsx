/** @vitest-environment jsdom */
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import PersonalDealSignals from "@/app/components/me/PersonalDealSignals";
import { personalDealKpis } from "@/app/components/me/personalDealKpis";
import type { Deal, DealMetrics } from "@/app/lib/types";
import { computeKpis, resolveAnalyticsWindow } from "@/app/components/overview/analytics/metrics";
import { toTiles } from "@/app/components/overview/analytics/currencyScope";
import en from "@/messages/en/me.json";
import ja from "@/messages/ja/me.json";

const fixture = vi.hoisted<{ currency: string | null }>(() => ({ currency: null }));
const queryWriter = vi.hoisted(() => vi.fn());
vi.mock("next/navigation", () => ({
    useSearchParams: () => new URLSearchParams(fixture.currency ? { currency: fixture.currency } : {}),
}));
vi.mock("motion/react", () => ({ useReducedMotion: () => true }));
vi.mock("@/app/hooks/useOwnedUrlParams", () => ({ useOwnedUrlParams: queryWriter }));
vi.mock("@/app/components/overview/analytics/CurrencyControl", () => ({
    default: ({ counts, onChange }: { counts: ReadonlyMap<string, number>; onChange: (currency: string) => void }) => (
        <div>{[...counts.keys()].map((currency) => <button key={currency} onClick={() => onChange(currency)}>{currency}</button>)}</div>
    ),
}));

const NOW = Date.parse("2026-07-15T23:30:00Z");
const WINDOW = resolveAnalyticsWindow("90d", NOW, "Asia/Tokyo");
const metrics: DealMetrics = {
    totalCount: 16,
    byCurrency: [
        { currency: "JPY", openCount: 5, closedCount: 1, openValue: 0, closedForecast: 0, closedRevenue: 1_200_000, wonCount: 1, lostCount: 0 },
        { currency: "USD", openCount: 7, closedCount: 3, openValue: 0, closedForecast: 0, closedRevenue: 35_400, wonCount: 2, lostCount: 1 },
    ],
};

function deal(overrides: Partial<Deal> = {}): Deal {
    return {
        id: 1, name: "Deal", ownerId: 51, currency: "USD", value: 0, actualValue: 0,
        pipeline: null, stage: null, position: 0, company: null,
        createdAt: "2026-07-01 00:00:00", updatedAt: "2026-07-01 00:00:00", ...overrides,
    };
}

const deals: Deal[] = [
    ...[10_000_000, 10_000_000, 10_000_000, 10_000_000, 5_000_000, 450_000].map((value, index) => deal({
        id: 100 + index, currency: "JPY", value,
        ...(index === 0 ? { createdAt: "2026-06-11 00:00:00", closedAt: "2026-07-11 00:00:00", won: true, actualValue: 1_200_000 } : {}),
    })),
    ...[100_000, 100_000, 100_000, 100_000, 100_000, 100_000, 100_000, 25_000, 10_000, 0].map((value, index) => deal({
        id: 200 + index, value, actualValue: index === 0 ? 25_400 : index === 1 ? 10_000 : 0,
        ...(index < 3 ? { closedAt: "2026-07-11 00:00:00", won: index < 2 } : {}),
    })),
];

function mount(locale: "en" | "ja" = "en", data: DealMetrics | null = metrics, personalDeals = deals) {
    return render(
        <NextIntlClientProvider locale={locale} messages={locale === "ja" ? ja : en}>
            <PersonalDealSignals deals={personalDeals} dealMetrics={data} timezone="Asia/Tokyo" />
        </NextIntlClientProvider>,
    );
}

beforeEach(() => {
    vi.clearAllMocks();
    fixture.currency = null;
    vi.spyOn(Date, "now").mockReturnValue(NOW);
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

describe("personal currency aggregation", () => {
    it("separates all monetary scalars and series in the reported mixed-currency deal set", () => {
        const usd = personalDealKpis(deals, "USD", WINDOW, "Asia/Tokyo");
        const jpy = personalDealKpis(deals, "JPY", WINDOW, "Asia/Tokyo");
        expect(usd).toMatchObject({ newPipeline: 735_000, wonRevenue: 35_400, wonCount: 2, lostCount: 1, avgCycleDays: 10 });
        expect(jpy).toMatchObject({ newPipeline: 45_450_000, wonRevenue: 1_200_000, wonCount: 1, lostCount: 0, avgCycleDays: 30 });
        for (const kpis of [usd, jpy]) {
            expect(kpis.newPipelineSeries.reduce((sum, value) => sum + value, 0)).toBe(kpis.newPipeline);
            expect(kpis.wonSeries.reduce((sum, value) => sum + value, 0)).toBe(kpis.wonRevenue);
        }
    });

    it("scopes previous-period baselines and percentage changes to the selected currency", () => {
        const previous = [
            deal({ value: 100_000, actualValue: 17_700, createdAt: "2026-04-01 00:00:00", closedAt: "2026-04-11 00:00:00", won: true }),
            deal({ currency: "JPY", value: 99_000_000, actualValue: 99_000_000, createdAt: "2026-04-01 00:00:00", closedAt: "2026-04-11 00:00:00", won: true }),
        ];
        const kpis = personalDealKpis([...deals, ...previous], "USD", WINDOW, "Asia/Tokyo");
        expect(kpis).toMatchObject({ wonRevenuePrev: 17_700, newPipelinePrev: 100_000, wonCountPrev: 1, lostCountPrev: 0, avgCycleDaysPrev: 10 });
        expect(toTiles(kpis).find((tile) => tile.key === "wonRevenue")?.delta).toBe(1);
        expect(toTiles(kpis).find((tile) => tile.key === "newPipeline")?.delta).toBe(6.35);
    });

    it("matches Analytics' workspace-local inclusive dates across a DST transition", () => {
        const data = [
            deal({ value: 999, createdAt: "2026-03-08T04:59:59Z" }),
            deal({ value: 10, createdAt: "2026-03-08T05:00:00Z" }),
            deal({ value: 20, createdAt: "2026-03-10T03:59:59Z" }),
            deal({ value: 999, createdAt: "2026-03-10T04:00:00Z" }),
        ];
        const kpis = personalDealKpis(data, "USD", { from: "2026-03-08", to: "2026-03-09" }, "America/New_York");
        expect(kpis.newPipeline).toBe(30);
        expect(kpis.newPipelineSeries).toEqual([10, 20]);
    });

    it("pins Analytics' 90-day calendar boundary instead of the former trailing-hour cutoff", () => {
        const now = Date.parse("2026-07-15T12:00:00Z");
        const window = resolveAnalyticsWindow("90d", now, "UTC");
        expect(window).toEqual({ from: "2026-04-17", to: "2026-07-15" });
        const data = [
            deal({ value: 1_000, actualValue: 100, won: true, createdAt: "2026-04-16T23:59:59Z", closedAt: "2026-04-16T23:59:59Z" }),
            deal({ value: 200, actualValue: 20, won: true, createdAt: "2026-04-17T00:00:00Z", closedAt: "2026-04-17T00:00:00Z" }),
            deal({ value: 300, actualValue: 30, won: true, createdAt: "2026-07-15T23:59:59Z", closedAt: "2026-07-15T23:59:59Z" }),
            deal({ value: 4_000, actualValue: 400, won: true, createdAt: "2026-07-16T00:00:00Z", closedAt: "2026-07-16T00:00:00Z" }),
        ];
        const kpis = personalDealKpis(data, "USD", window, "UTC");
        expect(kpis).toMatchObject({ newPipeline: 500, wonRevenue: 50, newPipelinePrev: 1_000, wonRevenuePrev: 100, wonCount: 2 });
        expect(kpis.newPipelineSeries.reduce((sum, value) => sum + value, 0)).toBe(500);
        expect(kpis.wonSeries.reduce((sum, value) => sum + value, 0)).toBe(50);
        const legacy = computeKpis(data, now, 90);
        expect(legacy.find((tile) => tile.key === "newPipeline")?.value).toBe(1_200);
        expect(legacy.find((tile) => tile.key === "wonRevenue")?.value).toBe(120);
    });

    it("pins Analytics' same-day cycle at zero UTC calendar days", () => {
        const data = [deal({ won: true, createdAt: "2026-07-10T00:01:00Z", closedAt: "2026-07-10T23:59:00Z" })];
        expect(personalDealKpis(data, "USD", WINDOW, "Asia/Tokyo").avgCycleDays).toBe(0);
        const legacy = computeKpis(data, NOW, 90).find((tile) => tile.key === "avgCycle");
        expect(Math.round(legacy?.value ?? 0)).toBe(1);
    });

    it("uses Analytics' UTC calendar-day cycle length and excludes invalid cycles", () => {
        const data = [
            deal({ won: true, actualValue: 10, createdAt: "2026-07-10 23:55:00", closedAt: "2026-07-11 00:05:00" }),
            deal({ won: true, actualValue: 20, createdAt: "2026-07-12 00:00:00", closedAt: "2026-07-11 00:05:00" }),
        ];
        expect(personalDealKpis(data, "USD", WINDOW, "Asia/Tokyo")).toMatchObject({ wonRevenue: 30, wonCount: 2, avgCycleDays: 1 });
    });

    it("retains a real zero baseline but marks an absent baseline as unavailable", () => {
        const empty = personalDealKpis([], "USD", WINDOW, "UTC+9");
        expect(empty).toMatchObject({ wonRevenue: 0, newPipeline: 0, wonRevenuePrev: null, newPipelinePrev: null, wonCountPrev: null, avgCycleDaysPrev: null });
        const zero = personalDealKpis([deal({ value: 0, actualValue: 0, won: true, createdAt: "2026-04-01 00:00:00", closedAt: "2026-04-11 00:00:00" })], "USD", WINDOW, "UTC+9");
        expect(zero).toMatchObject({ wonRevenuePrev: 0, newPipelinePrev: 0, wonCountPrev: 1, lostCountPrev: 0, avgCycleDaysPrev: 10 });
    });

    it("preserves the existing single-currency figures for deals inside the reporting period", () => {
        const single = deals.filter((entry) => entry.currency === "JPY");
        const before = computeKpis(single, NOW, 90).map(({ key, value }) => ({ key, value }));
        const after = toTiles(personalDealKpis(single, "JPY", WINDOW, "Asia/Tokyo")).map(({ key, value }) => ({ key, value }));
        expect(after).toEqual(before);
    });
});

describe("personal deal signal tiles", () => {
    it("derives currency options and preserves every KPI when the metrics request fails", () => {
        mount("en", null);
        expect(screen.getByText("$735K")).toBeTruthy();
        expect(screen.getByText("$35.4K")).toBeTruthy();
        expect(screen.getByText("67")).toBeTruthy();
        expect(screen.getByText("10")).toBeTruthy();
        fireEvent.click(screen.getByRole("button", { name: "JPY" }));
        expect(screen.getByText("¥45.5M")).toBeTruthy();
        expect(screen.getByText("¥1.2M")).toBeTruthy();
        expect(screen.getByText("100")).toBeTruthy();
        expect(screen.getByText("30")).toBeTruthy();
        expect(screen.getAllByText("JPY only")).toHaveLength(2);
    });

    it("shows only the dominant currency, with a visible scope on both money tiles", () => {
        mount();
        expect(screen.getByText("$735K")).toBeTruthy();
        expect(screen.getByText("$35.4K")).toBeTruthy();
        expect(screen.getAllByText("USD only")).toHaveLength(2);
        expect(screen.getByText("67")).toBeTruthy();
        expect(screen.getByText("10")).toBeTruthy();
        expect(screen.queryByText("$46.2M")).toBeNull();
        expect(screen.queryByText("$1.2M")).toBeNull();
    });

    it("switches every KPI to the other currency immediately and persists the selection", () => {
        mount();
        fireEvent.click(screen.getByRole("button", { name: "JPY" }));
        expect(screen.getByText("¥45.5M")).toBeTruthy();
        expect(screen.getByText("¥1.2M")).toBeTruthy();
        expect(screen.getAllByText("JPY only")).toHaveLength(2);
        expect(screen.getByText("100")).toBeTruthy();
        expect(screen.getByText("30")).toBeTruthy();
        expect(screen.queryByText("$735K")).toBeNull();
        expect(queryWriter).toHaveBeenLastCalledWith({ currency: "JPY" });
    });

    it("restores a currency deep link and rejects a currency absent from the owner's metrics", () => {
        fixture.currency = "JPY";
        mount();
        expect(screen.getByText("¥45.5M")).toBeTruthy();
        cleanup();
        fixture.currency = "EUR";
        mount();
        expect(screen.getByText("$735K")).toBeTruthy();
        expect(queryWriter).toHaveBeenLastCalledWith({ currency: undefined });
    });

    it("keeps single-currency figures and labels both money tiles in Japanese", () => {
        mount("ja", { totalCount: 6, byCurrency: [metrics.byCurrency[0]] }, deals.filter((entry) => entry.currency === "JPY"));
        expect(screen.getByText("￥4545万")).toBeTruthy();
        expect(screen.getByText("￥120万")).toBeTruthy();
        expect(screen.getAllByText("JPY建て")).toHaveLength(2);
    });
});
