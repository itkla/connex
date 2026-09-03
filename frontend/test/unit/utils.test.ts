import { readFileSync } from "node:fs";
import path from "node:path";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
    ensureUrlScheme,
    fixedOffsetSeconds,
    formatCurrency,
    formatFileSize,
    formatRelativeTime,
    initials,
    isLikelyUrl,
    normalizeHex,
    normalizeWebsiteForCompare,
    parseCalendarDate,
    parseMysqlDateTime,
    pickDominantCurrency,
    readableTextColor,
    safeHref,
    toMysqlDateTime,
    yearMonthInTimezone,
} from "@/app/lib/utils";

describe("parseMysqlDateTime", () => {
    it("treats a naive MySQL datetime as UTC", () => {
        expect(parseMysqlDateTime("2026-07-15 12:00:00")).toBe(Date.UTC(2026, 6, 15, 12));
    });

    it("respects an explicit timezone suffix", () => {
        expect(parseMysqlDateTime("2026-07-15T12:00:00Z")).toBe(Date.UTC(2026, 6, 15, 12));
        expect(parseMysqlDateTime("2026-07-15T12:00:00+09:00")).toBe(Date.UTC(2026, 6, 15, 3));
    });

    it("parses a bare calendar date as a local date", () => {
        expect(parseMysqlDateTime("2026-07-15")).toBe(new Date(2026, 6, 15).getTime());
    });

    it("returns NaN for empty input", () => {
        expect(parseMysqlDateTime(null)).toBeNaN();
        expect(parseMysqlDateTime("")).toBeNaN();
    });
});

describe("toMysqlDateTime", () => {
    it("round-trips with parseMysqlDateTime", () => {
        const ms = Date.UTC(2026, 6, 15, 12, 34, 56);
        expect(parseMysqlDateTime(toMysqlDateTime(ms))).toBe(ms);
    });

    it("throws on an invalid date", () => {
        expect(() => toMysqlDateTime("not a date")).toThrow();
    });
});

describe("parseCalendarDate", () => {
    it("rejects impossible calendar dates", () => {
        expect(parseCalendarDate("2026-02-30")).toBeNaN();
        expect(parseCalendarDate("2026-13-01")).toBeNaN();
    });

    it("accepts a real date and rejects other formats", () => {
        expect(parseCalendarDate("2026-07-15")).toBe(new Date(2026, 6, 15).getTime());
        expect(parseCalendarDate("15/07/2026")).toBeNaN();
    });
});

describe("formatRelativeTime", () => {
    const now = Date.UTC(2026, 6, 15, 12);

    it("labels sub-45-second differences as now", () => {
        expect(formatRelativeTime("2026-07-15 11:59:30", "en-US", now)).toBe("now");
    });

    it("uses minute, hour, and day units at the documented thresholds", () => {
        expect(formatRelativeTime("2026-07-15 11:55:00", "en-US", now)).toBe("5 min. ago");
        expect(formatRelativeTime("2026-07-15 09:00:00", "en-US", now)).toBe("3 hr. ago");
        expect(formatRelativeTime("2026-07-12 12:00:00", "en-US", now)).toBe("3 days ago");
    });

    it("falls back to an absolute date beyond ~30 days", () => {
        const out = formatRelativeTime("2026-05-01 12:00:00", "en-US", now);
        expect(out).not.toContain("ago");
        expect(out).toMatch(/May/);
    });

    it("returns a dash for invalid input", () => {
        expect(formatRelativeTime(null, "en-US", now)).toBe("—");
        expect(formatRelativeTime("garbage", "en-US", now)).toBe("—");
    });
});

describe("formatRelativeTime is deterministic across the hydration boundary", () => {
    const source = readFileSync(path.resolve(process.cwd(), "app/lib/utils.ts"), "utf8");

    afterEach(() => {
        vi.useRealTimers();
    });

    it("takes no ambient clock, so a caller cannot omit the reference time", () => {
        expect(formatRelativeTime.length).toBe(3);
        expect(source).not.toMatch(/now\s*:\s*number\s*=\s*Date\.now\(\)/);
    });

    it("renders identically for a server render and a hydration that share one clock", () => {
        const shared = Date.UTC(2026, 6, 15, 12, 0, 0);
        const justBeforeTheMinuteFlips = "2026-07-15 11:58:31";

        vi.useFakeTimers();
        vi.setSystemTime(shared);
        const serverRender = formatRelativeTime(justBeforeTheMinuteFlips, "en-US", shared);

        vi.setSystemTime(shared + 40_000);
        const hydration = formatRelativeTime(justBeforeTheMinuteFlips, "en-US", shared);

        expect(hydration).toBe(serverRender);
        expect(serverRender).toBe("1 min. ago");
    });

    it("does move between buckets when the reference time itself advances", () => {
        const base = Date.UTC(2026, 6, 15, 12, 0, 0);
        const value = "2026-07-15 11:58:31";

        expect(formatRelativeTime(value, "en-US", base)).toBe("1 min. ago");
        expect(formatRelativeTime(value, "en-US", base + 40_000)).toBe("2 min. ago");
    });
});

describe("safeHref", () => {
    it("allows http(s) and app-relative URLs", () => {
        expect(safeHref("https://example.com/x")).toBe("https://example.com/x");
        expect(safeHref("http://example.com")).toBe("http://example.com");
        expect(safeHref("/records/contacts/1")).toBe("/records/contacts/1");
    });

    it("collapses script-bearing and protocol-relative URLs to #", () => {
        expect(safeHref("javascript:alert(1)")).toBe("#");
        expect(safeHref("data:text/html,x")).toBe("#");
        expect(safeHref("//evil.example")).toBe("#");
        expect(safeHref("/\\evil.example")).toBe("#");
    });

    it("rejects control characters and empty values", () => {
        expect(safeHref("https://example.com/\u0000x")).toBe("#");
        expect(safeHref("java\tscript:alert(1)")).toBe("#");
        expect(safeHref(null)).toBe("#");
        expect(safeHref("")).toBe("#");
    });
});

describe("url helpers", () => {
    it("ensureUrlScheme prepends https only when missing", () => {
        expect(ensureUrlScheme("example.com")).toBe("https://example.com");
        expect(ensureUrlScheme("http://example.com")).toBe("http://example.com");
        expect(ensureUrlScheme("  ")).toBe("");
    });

    it("isLikelyUrl requires a scheme and dotted host", () => {
        expect(isLikelyUrl("https://example.com")).toBe(true);
        expect(isLikelyUrl("example.com")).toBe(false);
        expect(isLikelyUrl("https://localhost")).toBe(false);
    });

    it("normalizeWebsiteForCompare unifies scheme, www, case, and trailing slashes", () => {
        expect(normalizeWebsiteForCompare("http://www.Example.com/")).toBe("example.com");
        expect(normalizeWebsiteForCompare("https://example.com//")).toBe("example.com");
        expect(normalizeWebsiteForCompare(null)).toBe("");
    });
});

describe("formatFileSize", () => {
    it("formats byte counts across unit boundaries", () => {
        expect(formatFileSize(0)).toBe("0 B");
        expect(formatFileSize(1023)).toBe("1023 B");
        expect(formatFileSize(1024)).toBe("1 KB");
        expect(formatFileSize(1536)).toBe("1.5 KB");
        expect(formatFileSize(10 * 1024 * 1024)).toBe("10 MB");
    });

    it("returns a dash for missing or negative input", () => {
        expect(formatFileSize(undefined)).toBe("—");
        expect(formatFileSize(-1)).toBe("—");
    });
});

describe("formatCurrency", () => {
    it("falls back to localized numeric formatting for non-ISO product currencies", () => {
        expect(formatCurrency(1234.5, "USDT", "en-US")).toBe("1,234.5");
    });
});

describe("color helpers", () => {
    it("normalizeHex expands shorthand and lowercases", () => {
        expect(normalizeHex("#ABC")).toBe("#aabbcc");
        expect(normalizeHex("336699")).toBe("#336699");
        expect(normalizeHex("#12")).toBeNull();
        expect(normalizeHex("zzz")).toBeNull();
    });

    it("readableTextColor picks white on dark and near-black on light", () => {
        expect(readableTextColor("#000000")).toBe("#ffffff");
        expect(readableTextColor("#ffffff")).toBe("#171717");
    });
});

describe("initials / pickDominantCurrency", () => {
    it("takes the first letters of at most two name parts, uppercased", () => {
        expect(initials("Jane Doe")).toBe("JD");
        expect(initials("Jane Alexandra Doe")).toBe("JA");
        expect(initials("jane")).toBe("J");
    });

    it("picks the most frequent currency, defaulting to USD", () => {
        expect(pickDominantCurrency([])).toBe("USD");
        expect(
            pickDominantCurrency([{ currency: "JPY" }, { currency: "JPY" }, { currency: "EUR" }]),
        ).toBe("JPY");
        expect(pickDominantCurrency([{ currency: null }])).toBe("USD");
    });
});

describe("fixedOffsetSeconds", () => {
    it("parses legacy fixed offsets in their documented forms", () => {
        expect(fixedOffsetSeconds("UTC+9")).toBe(9 * 3600);
        expect(fixedOffsetSeconds("+09:30")).toBe(9 * 3600 + 30 * 60);
        expect(fixedOffsetSeconds("GMT-5")).toBe(-5 * 3600);
        expect(fixedOffsetSeconds("Z")).toBe(0);
        expect(fixedOffsetSeconds("UTC")).toBe(0);
    });

    it("returns null for IANA names and out-of-range offsets", () => {
        expect(fixedOffsetSeconds("Asia/Tokyo")).toBeNull();
        expect(fixedOffsetSeconds("+19:00")).toBeNull();
        expect(fixedOffsetSeconds("UTC+18:30")).toBeNull();
        expect(fixedOffsetSeconds("UTC+18")).toBe(18 * 3600);
    });
});

describe("yearMonthInTimezone", () => {
    const lateUtc = Date.UTC(2026, 11, 31, 20);

    it("rolls into the next month for an eastern offset at year end", () => {
        expect(yearMonthInTimezone(lateUtc, "UTC+9")).toEqual({ year: 2027, month: 1 });
        expect(yearMonthInTimezone(lateUtc, "UTC")).toEqual({ year: 2026, month: 12 });
    });

    it("resolves IANA zones and falls back to UTC for garbage", () => {
        expect(yearMonthInTimezone(lateUtc, "Asia/Tokyo")).toEqual({ year: 2027, month: 1 });
        expect(yearMonthInTimezone(lateUtc, "Not/AZone")).toEqual({ year: 2026, month: 12 });
    });
});
