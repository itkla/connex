import { describe, expect, it } from "vitest";

import {
    DAY_GRID_CELLS,
    WEEK_STARTS_ON,
    YEAR_PAGE_SIZE,
    bandCaps,
    endOfMonth,
    endOfYear,
    cellSeries,
    monthCellsForYear,
    parseDayKey,
    periodOverlaps,
    periodRange,
    rangeDays,
    spanPeriods,
    startOfYear,
    splitIntoWindows,
    sumSeries,
    visibleSpan,
    withinSpanLimit,
    yearCellsForPage,
    yearPageStart,
} from "@/app/lib/rangeCalendar";
import { dayKeyOf } from "@/app/lib/calendar";

describe("parseDayKey", () => {
    it("reads a well-formed key as a local-midnight date", () => {
        const parsed = parseDayKey("2026-08-10");
        expect(parsed?.getFullYear()).toBe(2026);
        expect(parsed?.getMonth()).toBe(7);
        expect(parsed?.getDate()).toBe(10);
        expect(parsed?.getHours()).toBe(0);
    });

    it("round-trips through dayKeyOf", () => {
        for (const key of ["2026-01-01", "2026-02-28", "2024-02-29", "2026-12-31"]) {
            expect(dayKeyOf(parseDayKey(key)!)).toBe(key);
        }
    });

    it("rejects malformed and calendar-invalid input", () => {
        for (const key of ["", "2026-8-10", "2026/08/10", "2026-02-30", "2026-13-01", "0026-01-01"]) {
            expect(parseDayKey(key)).toBeNull();
        }
    });
});

describe("period boundaries", () => {
    it("spans a month inclusively", () => {
        expect(periodRange("month", parseDayKey("2026-02-14")!)).toEqual({
            from: "2026-02-01",
            to: "2026-02-28",
        });
    });

    it("spans a leap February", () => {
        expect(periodRange("month", parseDayKey("2024-02-14")!)).toEqual({
            from: "2024-02-01",
            to: "2024-02-29",
        });
    });

    it("spans a year inclusively", () => {
        expect(periodRange("year", parseDayKey("2026-06-30")!)).toEqual({
            from: "2026-01-01",
            to: "2026-12-31",
        });
    });

    it("spans a single day", () => {
        expect(periodRange("day", parseDayKey("2026-06-30")!)).toEqual({
            from: "2026-06-30",
            to: "2026-06-30",
        });
    });

    it("derives month and year edges", () => {
        expect(dayKeyOf(startOfYear(parseDayKey("2026-06-30")!))).toBe("2026-01-01");
        expect(dayKeyOf(endOfYear(parseDayKey("2026-06-30")!))).toBe("2026-12-31");
        expect(dayKeyOf(endOfMonth(parseDayKey("2026-04-05")!))).toBe("2026-04-30");
    });
});

describe("spanPeriods", () => {
    it("is order-independent", () => {
        const a = parseDayKey("2026-03-10")!;
        const b = parseDayKey("2026-05-02")!;
        expect(spanPeriods("day", a, b)).toEqual({ from: "2026-03-10", to: "2026-05-02" });
        expect(spanPeriods("day", b, a)).toEqual({ from: "2026-03-10", to: "2026-05-02" });
    });

    it("widens each anchor to its month", () => {
        expect(spanPeriods("month", parseDayKey("2026-05-20")!, parseDayKey("2026-03-04")!)).toEqual({
            from: "2026-03-01",
            to: "2026-05-31",
        });
    });

    it("widens a single month anchor to the whole month", () => {
        const august = parseDayKey("2026-08-01")!;
        expect(spanPeriods("month", august, august)).toEqual({ from: "2026-08-01", to: "2026-08-31" });
    });

    it("widens each anchor to its year", () => {
        expect(spanPeriods("year", parseDayKey("2025-07-07")!, parseDayKey("2026-02-02")!)).toEqual({
            from: "2025-01-01",
            to: "2026-12-31",
        });
    });
});

describe("rangeDays", () => {
    it("counts both bounds", () => {
        expect(rangeDays({ from: "2026-08-10", to: "2026-08-10" })).toBe(1);
        expect(rangeDays({ from: "2026-08-10", to: "2026-08-11" })).toBe(2);
        expect(rangeDays({ from: "2026-01-01", to: "2026-12-31" })).toBe(365);
        expect(rangeDays({ from: "2024-01-01", to: "2024-12-31" })).toBe(366);
    });

    it("counts a two-year span at the backend limit", () => {
        expect(rangeDays({ from: "2025-01-01", to: "2026-12-31" })).toBe(730);
    });

    it("returns 0 for reversed or unparseable ranges", () => {
        expect(rangeDays({ from: "2026-08-11", to: "2026-08-10" })).toBe(0);
        expect(rangeDays({ from: "nope", to: "2026-08-10" })).toBe(0);
    });

    it("survives a daylight-saving transition", () => {
        expect(rangeDays({ from: "2026-03-07", to: "2026-03-09" })).toBe(3);
        expect(rangeDays({ from: "2026-10-31", to: "2026-11-02" })).toBe(3);
    });
});

describe("withinSpanLimit", () => {
    const limit = 731;

    it("accepts a range exactly at the cap", () => {
        const anchor = parseDayKey("2026-01-01")!;
        expect(rangeDays({ from: "2026-01-01", to: "2028-01-01" })).toBe(limit);
        expect(withinSpanLimit("day", anchor, parseDayKey("2028-01-01")!, limit)).toBe(true);
    });

    it("rejects the first day past the cap", () => {
        expect(withinSpanLimit("day", parseDayKey("2026-01-01")!, parseDayKey("2028-01-02")!, limit)).toBe(false);
    });

    it("caps in both directions from the anchor", () => {
        const anchor = parseDayKey("2026-01-01")!;
        expect(withinSpanLimit("day", anchor, parseDayKey("2024-01-02")!, limit)).toBe(true);
        expect(withinSpanLimit("day", anchor, parseDayKey("2024-01-01")!, limit)).toBe(false);
    });

    it("measures month cells by their whole month", () => {
        const anchor = parseDayKey("2026-01-01")!;
        expect(withinSpanLimit("month", anchor, parseDayKey("2027-12-01")!, limit)).toBe(true);
        expect(withinSpanLimit("month", anchor, parseDayKey("2028-01-01")!, limit)).toBe(false);
    });

    it("allows two whole years but not three", () => {
        const anchor = parseDayKey("2025-06-01")!;
        expect(withinSpanLimit("year", anchor, parseDayKey("2026-06-01")!, limit)).toBe(true);
        expect(withinSpanLimit("year", anchor, parseDayKey("2027-06-01")!, limit)).toBe(false);
    });
});

describe("periodOverlaps", () => {
    const range = { from: "2026-03-15", to: "2026-05-10" };

    it("includes a month clipped by the range", () => {
        expect(periodOverlaps("month", parseDayKey("2026-03-01")!, range)).toBe(true);
        expect(periodOverlaps("month", parseDayKey("2026-05-01")!, range)).toBe(true);
    });

    it("excludes months outside the range", () => {
        expect(periodOverlaps("month", parseDayKey("2026-02-01")!, range)).toBe(false);
        expect(periodOverlaps("month", parseDayKey("2026-06-01")!, range)).toBe(false);
    });

    it("includes only days inside the range", () => {
        expect(periodOverlaps("day", parseDayKey("2026-03-15")!, range)).toBe(true);
        expect(periodOverlaps("day", parseDayKey("2026-03-14")!, range)).toBe(false);
    });
});

describe("grid pages", () => {
    it("lists the twelve months of a year in order", () => {
        const cells = monthCellsForYear(parseDayKey("2026-07-04")!);
        expect(cells).toHaveLength(12);
        expect(dayKeyOf(cells[0])).toBe("2026-01-01");
        expect(dayKeyOf(cells[11])).toBe("2026-12-01");
    });

    it("aligns year pages to fixed blocks so paging is stable", () => {
        expect(yearPageStart(parseDayKey("2026-07-04")!).getFullYear()).toBe(2016);
        expect(yearPageStart(parseDayKey("2016-01-01")!).getFullYear()).toBe(2016);
        expect(yearPageStart(parseDayKey("2015-12-31")!).getFullYear()).toBe(2004);
    });

    it("lists a full year page", () => {
        const cells = yearCellsForPage(parseDayKey("2026-07-04")!);
        expect(cells).toHaveLength(YEAR_PAGE_SIZE);
        expect(dayKeyOf(cells[0])).toBe("2016-01-01");
        expect(dayKeyOf(cells[11])).toBe("2027-01-01");
    });
});

describe("bandCaps", () => {
    const week = [false, true, true, true, false, false, false];

    it("caps the band where it meets unselected cells", () => {
        expect(bandCaps(week, 1, 7)).toEqual({ start: true, end: false });
        expect(bandCaps(week, 2, 7)).toEqual({ start: false, end: false });
        expect(bandCaps(week, 3, 7)).toEqual({ start: false, end: true });
    });

    it("reports no caps for an unselected cell", () => {
        expect(bandCaps(week, 0, 7)).toEqual({ start: false, end: false });
    });

    it("caps the band at row edges so it never bleeds across a wrap", () => {
        const twoRows = [true, true, true, true, true, true, true, true, true];
        expect(bandCaps(twoRows, 0, 7)).toEqual({ start: true, end: false });
        expect(bandCaps(twoRows, 6, 7)).toEqual({ start: false, end: true });
        expect(bandCaps(twoRows, 7, 7)).toEqual({ start: true, end: false });
    });
});

describe("cellSeries", () => {
    const series = new Map([
        ["2026-01-01", 3],
        ["2026-01-31", 5],
        ["2026-02-14", 7],
        ["2027-03-02", 11],
    ]);

    it("gives a day its own magnitude, and zero when absent", () => {
        expect(cellSeries("day", parseDayKey("2026-01-01")!, series)).toEqual([3]);
        expect(cellSeries("day", parseDayKey("2026-01-02")!, series)).toEqual([0]);
    });

    it("gives a month one bar per day, in calendar order", () => {
        const january = cellSeries("month", parseDayKey("2026-01-17")!, series);
        expect(january).toHaveLength(31);
        expect(january[0]).toBe(3);
        expect(january[30]).toBe(5);
        expect(january[1]).toBe(0);
    });

    it("sizes a month's bars to its real length", () => {
        expect(cellSeries("month", parseDayKey("2026-02-01")!, series)).toHaveLength(28);
        expect(cellSeries("month", parseDayKey("2024-02-01")!, series)).toHaveLength(29);
    });

    it("gives a year one bar per month, each the month's total", () => {
        const year = cellSeries("year", parseDayKey("2026-06-06")!, series);
        expect(year).toHaveLength(12);
        expect(year[0]).toBe(8);
        expect(year[1]).toBe(7);
        expect(year[2]).toBe(0);
    });

    it("keeps years apart", () => {
        expect(cellSeries("year", parseDayKey("2027-01-01")!, series)[2]).toBe(11);
        expect(cellSeries("year", parseDayKey("2026-01-01")!, series)[2]).toBe(0);
    });
});

describe("sumSeries", () => {
    const series = new Map([
        ["2026-01-01", 3],
        ["2026-01-02", 4],
        ["2026-01-03", 5],
    ]);

    it("totals an inclusive range", () => {
        expect(sumSeries(series, { from: "2026-01-01", to: "2026-01-03" })).toBe(12);
        expect(sumSeries(series, { from: "2026-01-02", to: "2026-01-02" })).toBe(4);
    });

    it("ignores days outside the series", () => {
        expect(sumSeries(series, { from: "2025-12-30", to: "2026-01-01" })).toBe(3);
    });

    it("returns 0 for an unparseable range", () => {
        expect(sumSeries(series, { from: "nope", to: "2026-01-03" })).toBe(0);
    });

    it("counts every day across a daylight-saving transition", () => {
        const dst = new Map([
            ["2026-03-07", 1],
            ["2026-03-08", 1],
            ["2026-03-09", 1],
            ["2026-11-01", 1],
            ["2026-11-02", 1],
        ]);
        expect(sumSeries(dst, { from: "2026-03-07", to: "2026-03-09" })).toBe(3);
        expect(sumSeries(dst, { from: "2026-10-31", to: "2026-11-02" })).toBe(2);
    });
});

describe("visibleSpan", () => {
    it("covers both six-week day grids of a two-panel view", () => {
        expect(visibleSpan("day", parseDayKey("2026-08-01")!, 2)).toEqual({
            from: "2026-07-26",
            to: "2026-10-10",
        });
    });

    it("covers a single grid when only one panel is shown", () => {
        expect(visibleSpan("day", parseDayKey("2026-08-01")!, 1)).toEqual({
            from: "2026-07-26",
            to: "2026-09-05",
        });
    });

    it("starts on the configured first day of the week", () => {
        const span = visibleSpan("day", parseDayKey("2026-08-01")!, 1);
        expect(parseDayKey(span.from)!.getDay()).toBe(WEEK_STARTS_ON);
        expect(rangeDays(span)).toBe(DAY_GRID_CELLS);
    });

    it("covers the whole year at month zoom", () => {
        expect(visibleSpan("month", parseDayKey("2026-08-01")!, 2)).toEqual({
            from: "2026-01-01",
            to: "2026-12-31",
        });
    });

    it("covers the whole page at year zoom", () => {
        expect(visibleSpan("year", parseDayKey("2026-08-01")!, 2)).toEqual({
            from: "2016-01-01",
            to: "2027-12-31",
        });
    });
});

describe("splitIntoWindows", () => {
    it("leaves a span already within the limit whole", () => {
        expect(splitIntoWindows({ from: "2026-01-01", to: "2026-03-01" }, 731)).toEqual([
            { from: "2026-01-01", to: "2026-03-01" },
        ]);
    });

    it("splits a twelve-year page into windows the backend accepts", () => {
        const range = { from: "2016-01-01", to: "2027-12-31" };
        const windows = splitIntoWindows(range, 731);
        expect(windows.length).toBe(Math.ceil(rangeDays(range) / 731));
        for (const window of windows) expect(rangeDays(window)).toBeLessThanOrEqual(731);
        expect(windows.length).toBeGreaterThan(1);
        expect(windows[0].from).toBe("2016-01-01");
        expect(windows.at(-1)!.to).toBe("2027-12-31");
    });

    it("covers the span exactly, with no gap or overlap between windows", () => {
        const range = { from: "2016-01-01", to: "2027-12-31" };
        const windows = splitIntoWindows(range, 731);
        expect(windows.reduce((total, window) => total + rangeDays(window), 0)).toBe(rangeDays(range));
        for (let index = 1; index < windows.length; index++) {
            const previousEnd = parseDayKey(windows[index - 1].to)!;
            expect(windows[index].from).toBe(dayKeyOf(new Date(previousEnd.getFullYear(), previousEnd.getMonth(), previousEnd.getDate() + 1)));
        }
    });

    it("returns nothing for a reversed or unparseable span", () => {
        expect(splitIntoWindows({ from: "2026-03-01", to: "2026-01-01" }, 731)).toEqual([]);
        expect(splitIntoWindows({ from: "nope", to: "2026-01-01" }, 731)).toEqual([]);
    });
});
