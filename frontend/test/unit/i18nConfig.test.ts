import { describe, expect, it } from "vitest";
import {
    defaultLocale,
    localeFromAcceptLanguage,
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

describe("localeFromAcceptLanguage", () => {
    it.each([
        ["ja", "ja"],
        ["ja-JP,ja;q=0.9,en-US;q=0.8,en;q=0.7", "ja"],
        ["en-GB,en;q=0.9,ja;q=0.8", "en"],
        ["JA-jp", "ja"],
        ["fr-FR,de;q=0.9,ja-JP;q=0.7,en;q=0.5", "ja"],
        ["ja;q=0.4,en;q=0.9", "en"],
        ["en;q=0.5,ja;q=0.9", "ja"],
        ["ja;q=0.8,en;q=0.8", "ja"],
        ["en;q=0.8,ja;q=0.8", "en"],
        ["en;q=0,ja;q=0.1", "ja"],
        ["ja;q=0,en;q=0.5", "en"],
        ["*;q=1,ja;q=0.5", "ja"],
        ["fr-FR,de;q=0.9", "en"],
        ["*", "en"],
        ["ja;q=0", "en"],
        ["ja;q=oops,en", "en"],
        ["ja;q=1.1,en", "en"],
        ["ja;q=-1,en", "en"],
        ["ja;q=0.1234,en", "en"],
        ["ja;q=,en", "en"],
        ["ja;q=0.9;q=0.8,en", "en"],
        ["ja_JP,en", "en"],
        ["japanese,en", "en"],
        ["garbage!,ja", "ja"],
        [" , ja-JP ; q=0.9 , en ; q=0.5 ", "ja"],
        ["", "en"],
        [null, "en"],
        [undefined, "en"],
    ])("resolves %j to %s", (header, locale) => {
        expect(localeFromAcceptLanguage(header)).toBe(locale);
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
