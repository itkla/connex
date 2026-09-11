// @vitest-environment jsdom

import { act, type ReactElement } from "react";
import { createRoot, type Root } from "react-dom/client";
import { renderToStaticMarkup } from "react-dom/server";
import { createTranslator, NextIntlClientProvider, useTranslations } from "next-intl";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { TooltipProvider } from "@/components/ui/tooltip";
import Home from "@/app/page";
import GuidedExample from "@/app/components/landing/GuidedExample";
import { AskConnexPreview } from "@/app/components/landing/ProductPreviews";
import { SAMPLE_WORKSPACE } from "@/app/components/landing/sampleWorkspace";
import { getArticle } from "@/app/lib/docs/registry";
import en from "@/messages/en/common.json";
import ja from "@/messages/ja/common.json";

const session = vi.hoisted(() => ({ signedIn: false, locale: "en" as "en" | "ja" }));
vi.mock("next/headers", () => ({ headers: async () => new Headers() }));
vi.mock("next/font/google", () => ({
    Schibsted_Grotesk: () => ({ variable: "landing-display" }),
    Source_Sans_3: () => ({ variable: "landing-body" }),
}));
vi.mock("next-intl/server", () => ({
    getTranslations: async () => createTranslator({ locale: session.locale, messages: session.locale === "en" ? en : ja, namespace: "CommonHome" }),
}));
vi.mock("@/app/lib/api", () => ({ getPublicPageUserFromCookie: async () => session.signedIn ? { id: 1 } : null }));
vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh: vi.fn() }) }));
// Footer is an unchanged async server component; the page under test remains the real tree.
vi.mock("@/app/components/landing/LandingFooter", () => ({ default: () => null }));

(globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
let container: HTMLDivElement;
let root: Root;
let fetchSpy: ReturnType<typeof vi.fn>;

beforeEach(() => {
    session.signedIn = false;
    session.locale = "en";
    fetchSpy = vi.fn();
    vi.stubGlobal("fetch", fetchSpy);
    Object.defineProperty(window, "matchMedia", { configurable: true, value: (query: string) => ({
        matches: true, media: query, addListener: () => undefined, removeListener: () => undefined,
        addEventListener: () => undefined, removeEventListener: () => undefined,
    }) });
    container = document.createElement("div");
    document.body.append(container);
    root = createRoot(container);
});

afterEach(async () => {
    await act(async () => root.unmount());
    container.remove();
    vi.unstubAllGlobals();
});

function localized(element: ReactElement, locale: "en" | "ja") {
    return <NextIntlClientProvider locale={locale} messages={locale === "en" ? en : ja} timeZone="UTC"><TooltipProvider>{element}</TooltipProvider></NextIntlClientProvider>;
}

function AskExample() {
    const t = useTranslations("CommonHome");
    return <AskConnexPreview t={t} />;
}

function button(label: string) {
    const result = Array.from(container.querySelectorAll("button")).find((item) => item.textContent === label);
    if (!result) throw new Error(`Missing button: ${label}`);
    return result;
}

describe.each(["en", "ja"] as const)("landing product story in %s", (locale) => {
    it("renders the CRM breadth and every feature before JavaScript, with working anchors and docs paths", async () => {
        session.locale = locale;
        const messages = locale === "en" ? en.CommonHome : ja.CommonHome;
        const html = renderToStaticMarkup(localized(await Home(), locale));
        const doc = new DOMParser().parseFromString(html, "text/html");
        for (const heading of [messages.contextHeading, messages.featuresHeading, messages.askName, messages.mapName, messages.workflowName, messages.teamHeading, messages.startHeading, messages.ctaHeading]) {
            expect(doc.body.textContent).toContain(heading);
        }
        for (const id of ["main", "product", "features", "deploy", "workflow"]) expect(doc.getElementById(id)).not.toBeNull();
        expect(doc.querySelectorAll("h1")).toHaveLength(1);
        expect(doc.querySelectorAll("#features [data-sample-source]")).toHaveLength(2);
        expect(doc.querySelector("noscript")?.textContent).toContain(messages.attention_risk_who);
        expect(doc.querySelector("noscript")?.textContent).toContain(messages.attention_introduction_who);
        for (const link of doc.querySelectorAll<HTMLAnchorElement>('a[href^="/docs/"]')) {
            const [category, article] = link.pathname.replace("/docs/", "").split("/");
            expect(getArticle(category, article), link.pathname).toBeDefined();
        }
        expect(fetchSpy).not.toHaveBeenCalled();
    });

    it.each([false, true])("routes every primary CTA for signedIn=%s", async (signedIn) => {
        session.locale = locale;
        session.signedIn = signedIn;
        const messages = locale === "en" ? en.CommonHome : ja.CommonHome;
        const label = signedIn ? messages.ctaDashboard : messages.heroCtaPrimary;
        const html = renderToStaticMarkup(localized(await Home(), locale));
        const doc = new DOMParser().parseFromString(html, "text/html");
        const links = Array.from(doc.querySelectorAll("a")).filter((link) => link.textContent === label);
        expect(links.length).toBeGreaterThanOrEqual(3);
        for (const link of links) expect(link.getAttribute("href")).toBe(signedIn ? "/dashboard" : "/auth/register");
    });

    it("selects evidence by pointer and keyboard without making requests", async () => {
        const m = locale === "en" ? en.CommonHome : ja.CommonHome;
        await act(async () => root.render(localized(<GuidedExample />, locale)));
        expect(container.querySelector('[data-attention-example="cooling"]')).not.toBeNull();
        await act(async () => button(m.attention_risk_tab).click());
        expect(button(m.attention_risk_tab).getAttribute("aria-pressed")).toBe("true");
        expect(container.querySelector('[data-sample-source="close"]')?.textContent).toContain(m.source_close_body);
        await act(async () => button(m.attention_risk_tab).dispatchEvent(new KeyboardEvent("keydown", { key: "ArrowRight", bubbles: true })));
        expect(document.activeElement).toBe(button(m.attention_introduction_tab));
        const source = container.querySelector<HTMLDetailsElement>('[data-sample-source="introduction"]');
        expect(source?.open).toBe(false);
        await act(async () => source?.querySelector("summary")?.click());
        expect(source?.open).toBe(true);
        expect(source?.textContent).toContain(m.source_introduction_body);
        await act(async () => button(m.attention_cooling_tab).click());
        expect(container.querySelector('[data-sample-source="introduction"]')).toBeNull();
        expect(container.querySelector<HTMLDetailsElement>('[data-sample-source="review"]')?.open).toBe(false);
        expect(fetchSpy).not.toHaveBeenCalled();
    });

    it("opens each Ask Connex source onto its matching local activity", async () => {
        const messages = locale === "en" ? en : ja;
        await act(async () => root.render(localized(<AskExample />, locale)));
        for (const source of ["review", "pricing"] as const) {
            const details = container.querySelector<HTMLDetailsElement>(`[data-sample-source="${source}"]`);
            await act(async () => details?.querySelector("summary")?.click());
            expect(details?.open).toBe(true);
            expect(details?.textContent).toContain(messages.CommonHome[`source_${source}_body`]);
            expect(details?.querySelector("time")?.dateTime).toBe(SAMPLE_WORKSPACE.sources[source].date);
        }
        expect(fetchSpy).not.toHaveBeenCalled();
    });
});

it("keeps bilingual keys and dated risk evidence consistent", () => {
    expect(Object.keys(en.CommonHome).sort()).toEqual(Object.keys(ja.CommonHome).sort());
    const days = (earlier: string) => (Date.parse(SAMPLE_WORKSPACE.asOf) - Date.parse(earlier)) / 86_400_000;
    expect(days(SAMPLE_WORKSPACE.lastContact)).toBe(35);
    expect(days(SAMPLE_WORKSPACE.expectedClose)).toBe(12);
    expect(SAMPLE_WORKSPACE.sources.review.date).toBe(SAMPLE_WORKSPACE.lastContact);
    expect(SAMPLE_WORKSPACE.sources.close.date).toBe(SAMPLE_WORKSPACE.expectedClose);
});
