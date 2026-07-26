import { describe, expect, it } from "vitest";
import {
    defaultLocale,
    localeFromCookieHeader,
    localePreferenceFromCookieHeader,
    resolveLocale,
} from "@/i18n/config";

describe("resolveLocale", () => {
    it("passes supported locales through and defaults everything else", () => {
        expect(resolveLocale("ja")).toBe("ja");
        expect(resolveLocale("en")).toBe("en");
        expect(resolveLocale("fr")).toBe(defaultLocale);
        expect(resolveLocale(null)).toBe(defaultLocale);
        expect(resolveLocale(undefined)).toBe(defaultLocale);
    });
});

describe("localePreferenceFromCookieHeader", () => {
    it("extracts the NEXT_LOCALE cookie from a multi-cookie header", () => {
        expect(localePreferenceFromCookieHeader("JSESSIONID=abc; NEXT_LOCALE=ja; other=1")).toBe("ja");
    });

    it("URL-decodes the value and rejects unsupported or malformed ones", () => {
        expect(localePreferenceFromCookieHeader("NEXT_LOCALE=j%61")).toBe("ja");
        expect(localePreferenceFromCookieHeader("NEXT_LOCALE=fr")).toBeNull();
        expect(localePreferenceFromCookieHeader("NEXT_LOCALE=%E0%A4%A")).toBeNull();
        expect(localePreferenceFromCookieHeader("NEXT_LOCALE=")).toBeNull();
    });

    it("returns null when the cookie or header is absent", () => {
        expect(localePreferenceFromCookieHeader("JSESSIONID=abc")).toBeNull();
        expect(localePreferenceFromCookieHeader(null)).toBeNull();
        expect(localePreferenceFromCookieHeader(undefined)).toBeNull();
    });
});

describe("localeFromCookieHeader", () => {
    it("falls back to the default locale instead of null", () => {
        expect(localeFromCookieHeader("NEXT_LOCALE=ja")).toBe("ja");
        expect(localeFromCookieHeader("NEXT_LOCALE=xx")).toBe(defaultLocale);
        expect(localeFromCookieHeader(null)).toBe(defaultLocale);
    });
});
