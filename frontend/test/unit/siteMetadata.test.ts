import { createTranslator } from "next-intl";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { openGraphLocales } from "@/app/lib/siteMetadata";
import en from "@/messages/en/common.json";
import ja from "@/messages/ja/common.json";

const session = vi.hoisted(() => ({ headers: new Headers(), locale: "en" as "en" | "ja" }));

vi.mock("next/headers", () => ({ headers: async () => session.headers }));
vi.mock("next/font/google", () => ({
    Schibsted_Grotesk: () => ({ variable: "landing-display" }),
    Source_Sans_3: () => ({ variable: "landing-body" }),
}));
type CommonNamespace = "AppMetadata" | "CommonHome";

vi.mock("next-intl/server", () => ({
    getLocale: async () => session.locale,
    getTranslations: async (namespace: CommonNamespace) =>
        session.locale === "en"
            ? createTranslator({ locale: "en", messages: en, namespace })
            : createTranslator({ locale: "ja", messages: ja, namespace }),
}));
vi.mock("@/app/lib/api", () => ({ getPublicPageUserFromCookie: async () => null }));

const { generateMetadata } = await import("@/app/page");

beforeEach(() => {
    vi.stubEnv("CONNEX_LANDING_MODE", "product");
    vi.stubEnv("CONNEX_LANDING_PRELAUNCH_HOSTS", "");
    session.headers = new Headers({ host: "connexcrm.jp", "x-forwarded-proto": "https" });
    session.locale = "en";
});

afterEach(() => {
    vi.unstubAllEnvs();
});

describe("openGraphLocales", () => {
    it("tags the rendered locale and offers the other one as an alternate", () => {
        expect(openGraphLocales("en")).toEqual({ locale: "en_US", alternateLocale: ["ja_JP"] });
        expect(openGraphLocales("ja")).toEqual({ locale: "ja_JP", alternateLocale: ["en_US"] });
    });
});

describe("landing metadata", () => {
    it("describes the launched product on a product host", async () => {
        const metadata = await generateMetadata();
        expect(metadata.title).toEqual({ absolute: en.CommonHome.metaTitle });
        expect(metadata.description).toBe(en.CommonHome.metaDescription);
        expect(metadata.openGraph?.title).toBe(en.CommonHome.metaTitle);
        expect(metadata.openGraph?.description).toBe(en.CommonHome.metaDescription);
    });

    it("describes the waitlist on a prelaunch host instead of a product nobody can visit", async () => {
        vi.stubEnv("CONNEX_LANDING_PRELAUNCH_HOSTS", "connexcrm.jp");
        const metadata = await generateMetadata();
        expect(metadata.title).toEqual({ absolute: en.CommonHome.prelaunch.metaTitle });
        expect(metadata.description).toBe(en.CommonHome.prelaunch.metaDescription);
        expect(metadata.openGraph?.title).toBe(en.CommonHome.prelaunch.metaTitle);
        expect(metadata.twitter?.description).toBe(en.CommonHome.prelaunch.metaDescription);
    });

    it("follows the deployment default when the host is not listed", async () => {
        vi.stubEnv("CONNEX_LANDING_MODE", "prelaunch");
        const metadata = await generateMetadata();
        expect(metadata.title).toEqual({ absolute: en.CommonHome.prelaunch.metaTitle });
    });

    it("translates the prelaunch card for a Japanese reader", async () => {
        vi.stubEnv("CONNEX_LANDING_PRELAUNCH_HOSTS", "connexcrm.jp");
        session.locale = "ja";
        const metadata = await generateMetadata();
        expect(metadata.title).toEqual({ absolute: ja.CommonHome.prelaunch.metaTitle });
        expect(metadata.openGraph?.locale).toBe("ja_JP");
        expect(metadata.openGraph?.alternateLocale).toEqual(["en_US"]);
    });

    it("names the site and its address, and leaves the card type to the image that exists", async () => {
        const metadata = await generateMetadata();
        expect(metadata.openGraph?.siteName).toBe(en.CommonHome.brand);
        expect(metadata.openGraph?.url).toBe("/");
        expect(metadata.alternates?.canonical).toBe("/");
        expect(metadata.twitter).not.toHaveProperty("card");
    });
});
