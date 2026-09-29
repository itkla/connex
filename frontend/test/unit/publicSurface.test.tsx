// @vitest-environment jsdom

import Link from "next/link";
import type { ReactElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { createTranslator, NextIntlClientProvider } from "next-intl";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import LandingNav from "@/app/components/landing/LandingNav";
import LegalArticle from "@/app/components/legal/LegalArticle";
import LegalDisclosureList from "@/app/components/legal/LegalDisclosureList";
import DisclosurePage, { generateMetadata as disclosureMetadata } from "@/app/disclosure/page";
import DocsLayout from "@/app/docs/layout";
import TermsPage, { generateMetadata as termsMetadata } from "@/app/legal/page";
import RootNotFound from "@/app/not-found";
import PrivacyPage, { generateMetadata as privacyMetadata } from "@/app/privacy/page";
import TokushohoPage, { generateMetadata as tokushohoMetadata } from "@/app/tokushoho/page";
import enCommon from "@/messages/en/common.json";
import enDocs from "@/messages/en/docs.json";
import enErrors from "@/messages/en/errors.json";
import enLegal from "@/messages/en/legal.json";
import jaCommon from "@/messages/ja/common.json";
import jaDocs from "@/messages/ja/docs.json";
import jaErrors from "@/messages/ja/errors.json";
import jaLegal from "@/messages/ja/legal.json";

const PRELAUNCH_HOST = "connexcrm.jp";
const PRODUCT_HOST = "app.connexcrm.jp";
const ACCOUNT_LINKS = 'a[href="/auth/register"], a[href="/auth/login"], a[href="/dashboard"]';

const session = vi.hoisted(() => ({ locale: "en" as "en" | "ja", host: "app.connexcrm.jp", signedIn: false }));

vi.mock("next/headers", () => ({ headers: async () => new Headers({ host: session.host }) }));
vi.mock("next-intl/server", () => ({
    getTranslations: async (namespace: ServerNamespace) => translator(namespace),
}));
vi.mock("@/app/lib/api", () => ({
    getPublicPageUserFromCookie: async () => (session.signedIn ? { id: 1 } : null),
}));
vi.mock("next/navigation", () => ({
    useRouter: () => ({ refresh: vi.fn() }),
    usePathname: () => "/docs",
}));
vi.mock("@/app/components/landing/LandingFooter", () => ({
    default: ({ showLogin = true }: { showLogin?: boolean }) =>
        showLogin ? <Link href="/auth/login">footer login</Link> : null,
}));

/** Namespaces the public server components request from `getTranslations`. */
type ServerNamespace = "Legal" | "CommonHome" | "NotFound";

function catalog(locale: "en" | "ja") {
    return locale === "en"
        ? { ...enCommon, ...enDocs, ...enErrors, ...enLegal }
        : { ...jaCommon, ...jaDocs, ...jaErrors, ...jaLegal };
}

function translator(namespace: ServerNamespace) {
    const messages = catalog(session.locale);
    switch (namespace) {
        case "Legal":
            return createTranslator({ locale: session.locale, messages, namespace: "Legal" });
        case "CommonHome":
            return createTranslator({ locale: session.locale, messages, namespace: "CommonHome" });
        case "NotFound":
            return createTranslator({ locale: session.locale, messages, namespace: "NotFound" });
    }
}

function parse(element: ReactElement) {
    const markup = renderToStaticMarkup(
        <NextIntlClientProvider locale={session.locale} messages={catalog(session.locale)} timeZone="UTC">
            {element}
        </NextIntlClientProvider>,
    );
    return new DOMParser().parseFromString(markup, "text/html");
}

const PUBLIC_SURFACES = [
    ["/privacy", PrivacyPage],
    ["/legal", TermsPage],
    ["/disclosure", DisclosurePage],
    ["/tokushoho", TokushohoPage],
    ["/404", RootNotFound],
    ["/docs", () => DocsLayout({ children: null })],
] as const;

const LEGAL_DOCUMENTS = [
    ["privacy", privacyMetadata],
    ["terms", termsMetadata],
    ["disclosure", disclosureMetadata],
    ["tokushoho", tokushohoMetadata],
] as const;

beforeEach(() => {
    vi.stubEnv("CONNEX_LANDING_MODE", "product");
    vi.stubEnv("CONNEX_LANDING_PRELAUNCH_HOSTS", PRELAUNCH_HOST);
    session.locale = "en";
    session.host = PRODUCT_HOST;
    session.signedIn = false;
});

afterEach(() => {
    vi.unstubAllEnvs();
});

describe("public surfaces on a prelaunch host", () => {
    it.each(PUBLIC_SURFACES)("offers no account action on %s", async (_route, render) => {
        session.host = PRELAUNCH_HOST;
        const doc = parse(await render());

        expect(doc.querySelectorAll(ACCOUNT_LINKS)).toHaveLength(0);
        expect(doc.querySelectorAll('a[href="/#launch-signup"]').length).toBeGreaterThan(0);
    });

    it("ignores the session on the docs surface", async () => {
        session.host = PRELAUNCH_HOST;
        session.signedIn = true;
        const doc = parse(await DocsLayout({ children: null }));

        expect(doc.querySelectorAll(ACCOUNT_LINKS)).toHaveLength(0);
    });
});

describe("public surfaces on a product host", () => {
    it.each(PUBLIC_SURFACES)("keeps the account actions on %s", async (_route, render) => {
        const doc = parse(await render());

        expect(doc.querySelectorAll('a[href="/auth/register"]').length).toBeGreaterThan(0);
        expect(doc.querySelectorAll('a[href="/auth/login"]').length).toBeGreaterThan(0);
        expect(doc.querySelectorAll('a[href="/#launch-signup"]')).toHaveLength(0);
    });
});

describe("the real marketing footer", () => {
    it("drops the login link before launch", async () => {
        const { default: RealLandingFooter } = await vi.importActual<typeof import("@/app/components/landing/LandingFooter")>(
            "@/app/components/landing/LandingFooter",
        );
        const doc = parse(await RealLandingFooter({ showLogin: false }));

        expect(doc.querySelectorAll(ACCOUNT_LINKS)).toHaveLength(0);
        expect(doc.querySelectorAll('a[href="/privacy"]')).toHaveLength(1);
    });

    it("keeps the login link after launch", async () => {
        const { default: RealLandingFooter } = await vi.importActual<typeof import("@/app/components/landing/LandingFooter")>(
            "@/app/components/landing/LandingFooter",
        );
        const doc = parse(await RealLandingFooter({ showLogin: true }));

        expect(doc.querySelectorAll('a[href="/auth/login"]')).toHaveLength(1);
    });
});

describe("the header's compact call to action", () => {
    it.each(["en", "ja"] as const)("anchors to the launch signup before launch in %s", (locale) => {
        session.locale = locale;
        const messages = catalog(locale);
        const doc = parse(<LandingNav ctaHref="/auth/register" ctaLabel="unused" preLaunch />);
        const cta = doc.querySelector<HTMLAnchorElement>("[data-landing-mobile] a");

        expect(cta?.getAttribute("href")).toBe("/#launch-signup");
        expect(cta?.textContent).toBe(messages.CommonHome.prelaunch.navCta);
    });

    it.each(["en", "ja"] as const)("routes to the product call to action after launch in %s", (locale) => {
        session.locale = locale;
        const messages = catalog(locale);
        const label = messages.CommonHome.ctaGetStarted;
        const doc = parse(<LandingNav ctaHref="/auth/register" ctaLabel={label} />);
        const cta = doc.querySelector<HTMLAnchorElement>("[data-landing-mobile] a");

        expect(cta?.getAttribute("href")).toBe("/auth/register");
        expect(cta?.textContent).toBe(label);
    });
    it("keeps the language switcher in the phone bar and moves the theme toggle into the menu", () => {
        const home = catalog("en").CommonHome;
        const doc = parse(<LandingNav ctaHref="/auth/register" ctaLabel="Get started" preLaunch />);
        const cluster = doc.querySelector("[data-landing-mobile]");
        const languageSwitcher = cluster?.querySelector(`button[aria-label^="${home.languageLabel}:"]`);
        const themeToggle = cluster?.querySelector(`button[aria-label="${home.toggleLightDarkMode}"]`);

        expect(languageSwitcher).not.toBeNull();
        expect(languageSwitcher?.closest(".hidden")).toBeNull();
        expect(themeToggle?.parentElement?.className).toContain("hidden sm:flex");
    });
});

describe("legal document metadata", () => {
    it.each(LEGAL_DOCUMENTS)("describes %s in both locales", async (legalDocument, metadata) => {
        for (const locale of ["en", "ja"] as const) {
            session.locale = locale;
            const messages = catalog(locale);
            const { description } = await metadata();

            expect(description, `${legalDocument} ${locale}`).toBe(messages.Legal[legalDocument].metaDescription);
            expect(description?.length ?? 0, `${legalDocument} ${locale}`).toBeLessThanOrEqual(160);
        }
    });

    it("keeps the draft notice on every unresolved document", async () => {
        for (const [, render] of PUBLIC_SURFACES.slice(0, 4)) {
            const doc = parse(await render());
            expect(doc.querySelector('[role="note"]')?.textContent).toBe(enLegal.Legal.draftNotice);
        }
    });
});

describe("the legal shells", () => {
    const sections = [{ id: "one", heading: "One", body: "Body" }];
    const rows = [{ id: "one", term: "Term", description: "Value" }];

    it("drops the draft banner when no notice is supplied", () => {
        const article = parse(
            <LegalArticle title="T" updated="U" lede="L" tocLabel="On this page" sections={sections} />,
        );
        const list = parse(<LegalDisclosureList title="T" updated="U" lede="L" rows={rows} />);

        expect(article.querySelector('[role="note"]')).toBeNull();
        expect(list.querySelector('[role="note"]')).toBeNull();
        expect(article.body.textContent).toContain("One");
        expect(list.body.textContent).toContain("Term");
    });

    it("keeps the draft banner when one is supplied", () => {
        const article = parse(
            <LegalArticle
                title="T"
                updated="U"
                lede="L"
                notice="Draft"
                tocLabel="On this page"
                sections={sections}
            />,
        );

        expect(article.querySelector('[role="note"]')?.textContent).toBe("Draft");
    });
});
