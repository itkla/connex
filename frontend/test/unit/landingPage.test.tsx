// @vitest-environment jsdom

import { act, type ReactElement } from "react";
import { createRoot, type Root } from "react-dom/client";
import { renderToStaticMarkup } from "react-dom/server";
import { createTranslator, NextIntlClientProvider, useTranslations } from "next-intl";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { TooltipProvider } from "@/components/ui/tooltip";
import Home from "@/app/page";
import GuidedExample from "@/app/components/landing/GuidedExample";
import { AskConnexPreview, ConnectedRecordPreview, WorkflowPreview } from "@/app/components/landing/ProductPreviews";
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
let reducedMotion: boolean;
let pageHidden: boolean;
let motionListeners: Set<() => void>;
let notifyIntersection: (visible: boolean, ratio?: number) => void;

beforeEach(() => {
    session.signedIn = false;
    session.locale = "en";
    reducedMotion = true;
    pageHidden = false;
    motionListeners = new Set();
    notifyIntersection = () => undefined;
    fetchSpy = vi.fn();
    vi.stubGlobal("fetch", fetchSpy);
    Object.defineProperty(window, "matchMedia", { configurable: true, value: (query: string) => ({
        get matches() { return reducedMotion; }, media: query, addListener: () => undefined, removeListener: () => undefined,
        addEventListener: (_: string, listener: () => void) => motionListeners.add(listener),
        removeEventListener: (_: string, listener: () => void) => motionListeners.delete(listener),
    }) });
    Object.defineProperty(document, "hidden", { configurable: true, get: () => pageHidden });
    vi.stubGlobal("IntersectionObserver", class {
        constructor(callback: (entries: { isIntersecting: boolean; intersectionRatio: number }[]) => void) {
            notifyIntersection = (visible, ratio = visible ? 1 : 0) => callback([{ isIntersecting: visible, intersectionRatio: ratio }]);
        }
        observe() {}
        disconnect() {}
    });
    container = document.createElement("div");
    document.body.append(container);
    root = createRoot(container);
});

afterEach(async () => {
    await act(async () => root.unmount());
    container.remove();
    vi.useRealTimers();
    vi.unstubAllGlobals();
});

function localized(element: ReactElement, locale: "en" | "ja") {
    return <NextIntlClientProvider locale={locale} messages={locale === "en" ? en : ja} timeZone="UTC"><TooltipProvider>{element}</TooltipProvider></NextIntlClientProvider>;
}

function AskExample() {
    const t = useTranslations("CommonHome");
    return <AskConnexPreview t={t} />;
}

function WorkflowExample() {
    const t = useTranslations("CommonHome");
    return <WorkflowPreview t={t} />;
}

function ConnectedExample() {
    const t = useTranslations("CommonHome");
    return <ConnectedRecordPreview t={t} />;
}

function button(label: string) {
    const result = Array.from(container.querySelectorAll("button")).find((item) => item.getAttribute("aria-label") === label || item.textContent === label);
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
        const conversation = doc.querySelector('[data-ask-phase="complete"]');
        expect(conversation?.textContent).toContain(messages.askPrompt);
        expect(conversation?.textContent).toContain(messages.askFinding);
        expect(conversation?.querySelector("[inert]")).toBeNull();
        expect(conversation?.querySelector("button")?.closest("[hidden]")).not.toBeNull();
        expect(doc.querySelector("#deploy noscript")?.textContent).toContain(messages.attention_risk_who);
        expect(doc.querySelector("#deploy noscript")?.textContent).toContain(messages.attention_introduction_who);
        for (const link of doc.querySelectorAll<HTMLAnchorElement>('a[href^="/docs/"]')) {
            const [category, article] = link.pathname.replace("/docs/", "").split("/");
            expect(getArticle(category, article), link.pathname).toBeDefined();
        }
        expect(fetchSpy).not.toHaveBeenCalled();
    });

    it("keeps all six record explanations readable without JavaScript", () => {
        const m = locale === "en" ? en.CommonHome.connectedRecords : ja.CommonHome.connectedRecords;
        const html = renderToStaticMarkup(localized(<ConnectedExample />, locale));
        const doc = new DOMParser().parseFromString(html, "text/html");
        expect(doc.querySelector<HTMLElement>('[role="tablist"]')?.style.display).toBe("none");
        expect(doc.querySelector('[role="tabpanel"]')?.textContent).toContain(m.company.body);
        for (const id of ["contacts", "deals", "activities", "notes", "tasks"] as const) {
            expect(doc.querySelector("noscript")?.textContent).toContain(m[id].heading);
            expect(doc.querySelector("noscript")?.textContent).toContain(m[id].body);
        }
        expect(fetchSpy).not.toHaveBeenCalled();
    });

    it("reveals the selected record explanation by pointer and keyboard", async () => {
        const m = locale === "en" ? en.CommonHome.connectedRecords : ja.CommonHome.connectedRecords;
        await act(async () => root.render(localized(<ConnectedExample />, locale)));
        expect(container.querySelectorAll('[role="tab"]')).toHaveLength(6);
        expect(container.querySelector('[role="tabpanel"]:not([hidden])')?.textContent).toContain(m.company.body);
        for (const id of ["contacts", "deals", "activities", "notes", "tasks"] as const) {
            const tab = button(m[id].label);
            await act(async () => tab.dispatchEvent(new MouseEvent("mousedown", { button: 0, bubbles: true })));
            expect(tab.getAttribute("aria-selected")).toBe("true");
            const panel = container.querySelector('[role="tabpanel"]:not([hidden])');
            expect(panel?.getAttribute("aria-labelledby")).toBe(tab.id);
            expect(panel?.textContent).toContain(m[id].body);
            expect(container.querySelectorAll('[role="tabpanel"]:not([hidden])')).toHaveLength(1);
        }
        await act(async () => button(m.tasks.label).focus());
        await act(async () => {
            button(m.tasks.label).dispatchEvent(new KeyboardEvent("keydown", { key: "Home", bubbles: true }));
            await new Promise((resolve) => setTimeout(resolve, 0));
        });
        expect(document.activeElement).toBe(button(m.company.label));
        expect(button(m.company.label).getAttribute("aria-selected")).toBe("true");
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
        const evidence = container.querySelector<HTMLDetailsElement>("[data-attention-evidence]");
        expect(evidence?.open).toBe(false);
        await act(async () => evidence?.querySelector("summary")?.click());
        expect(evidence?.open).toBe(true);
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
        const evidence = container.querySelector<HTMLDetailsElement>("[data-ask-sources]");
        expect(evidence?.open).toBe(false);
        await act(async () => evidence?.querySelector("summary")?.click());
        expect(evidence?.open).toBe(true);
        for (const source of ["review", "pricing"] as const) {
            const details = container.querySelector<HTMLDetailsElement>(`[data-sample-source="${source}"]`);
            await act(async () => details?.querySelector("summary")?.click());
            expect(details?.open).toBe(true);
            expect(details?.textContent).toContain(messages.CommonHome[`source_${source}_body`]);
            expect(details?.querySelector("time")?.dateTime).toBe(SAMPLE_WORKSPACE.sources[source].date);
        }
        expect(fetchSpy).not.toHaveBeenCalled();
    });

    it("plays the sample message and answer once, then leaves the sources usable", async () => {
        vi.useFakeTimers();
        reducedMotion = false;
        const messages = locale === "en" ? en.CommonHome : ja.CommonHome;
        await act(async () => root.render(localized(<AskExample />, locale)));
        const conversation = container.querySelector("[data-ask-phase]");
        await act(async () => notifyIntersection(true, 0.1));
        expect(conversation?.getAttribute("data-ask-phase")).toBe("complete");
        await act(async () => notifyIntersection(true));
        expect(conversation?.getAttribute("data-ask-phase")).toBe("typing");
        await act(async () => vi.advanceTimersByTime(800));
        const typing = conversation?.querySelector('p.relative [aria-hidden="true"]')?.textContent;
        expect(typing?.length).toBeGreaterThan(0);
        expect(typing?.length).toBeLessThan(messages.askPrompt.length);
        await act(async () => vi.advanceTimersByTime(800));
        expect(conversation?.getAttribute("data-ask-phase")).toBe("thinking");
        expect(conversation?.textContent).toContain(messages.askThinking);
        await act(async () => vi.advanceTimersByTime(800));
        expect(conversation?.getAttribute("data-ask-phase")).toBe("responding");
        await act(async () => vi.advanceTimersByTime(1000));
        expect(conversation?.getAttribute("data-ask-phase")).toBe("complete");
        expect(conversation?.querySelector("[inert]")).toBeNull();
        expect(vi.getTimerCount()).toBe(0);
        await act(async () => conversation?.querySelector<HTMLDetailsElement>("[data-ask-sources]")?.querySelector("summary")?.click());
        const source = conversation?.querySelector<HTMLDetailsElement>('[data-sample-source="review"]');
        await act(async () => source?.querySelector("summary")?.click());
        expect(source?.open).toBe(true);
        await act(async () => { notifyIntersection(false); notifyIntersection(true); });
        expect(conversation?.getAttribute("data-ask-phase")).toBe("complete");
        expect(fetchSpy).not.toHaveBeenCalled();
    });
});

it("shows the workflow order once without hiding labels or repeating on scroll", async () => {
    vi.useFakeTimers();
    reducedMotion = false;
    await act(async () => root.render(localized(<WorkflowExample />, "en")));
    const sequence = container.querySelector("[data-workflow-active]");
    await act(async () => notifyIntersection(true, 0.1));
    expect(sequence?.getAttribute("data-workflow-active")).toBe("false");
    await act(async () => notifyIntersection(true));
    expect(sequence?.getAttribute("data-workflow-active")).toBe("true");
    expect(sequence?.textContent).toContain(en.CommonHome.workflow_task_title);
    expect(sequence?.querySelector("[hidden], [inert]")).toBeNull();
    await act(async () => vi.advanceTimersByTime(1900));
    expect(sequence?.getAttribute("data-workflow-active")).toBe("false");
    await act(async () => { notifyIntersection(false); notifyIntersection(true); });
    expect(sequence?.getAttribute("data-workflow-active")).toBe("false");
    expect(vi.getTimerCount()).toBe(0);
    expect(fetchSpy).not.toHaveBeenCalled();
});

it("keeps the workflow static with reduced motion and cancels its sequence when hidden", async () => {
    vi.useFakeTimers();
    await act(async () => root.render(localized(<WorkflowExample />, "ja")));
    const sequence = container.querySelector("[data-workflow-active]");
    await act(async () => notifyIntersection(true));
    expect(sequence?.getAttribute("data-workflow-active")).toBe("false");
    await act(async () => { reducedMotion = false; motionListeners.forEach((listener) => listener()); notifyIntersection(true); });
    expect(sequence?.getAttribute("data-workflow-active")).toBe("true");
    await act(async () => { pageHidden = true; document.dispatchEvent(new Event("visibilitychange")); });
    expect(sequence?.getAttribute("data-workflow-active")).toBe("false");
    expect(vi.getTimerCount()).toBe(0);
    await act(async () => root.render(null));
    expect(motionListeners.size).toBe(0);
});

it("resets a conversation when the locale changes during playback", async () => {
    vi.useFakeTimers();
    reducedMotion = false;
    await act(async () => root.render(localized(<AskExample />, "en")));
    await act(async () => notifyIntersection(true));
    await act(async () => vi.advanceTimersByTime(400));
    await act(async () => root.render(localized(<AskExample />, "ja")));
    const conversation = container.querySelector("[data-ask-phase]");
    expect(conversation?.getAttribute("data-ask-phase")).toBe("complete");
    expect(conversation?.textContent).toContain(ja.CommonHome.askPrompt);
    expect(vi.getTimerCount()).toBe(0);
    await act(async () => notifyIntersection(true));
    await act(async () => vi.advanceTimersByTime(3400));
    expect(conversation?.getAttribute("data-ask-phase")).toBe("complete");
    expect(conversation?.textContent).toContain(ja.CommonHome.askFinding);
});

it("allows replay and immediate skip, and settles when reduced motion changes", async () => {
    vi.useFakeTimers();
    reducedMotion = false;
    await act(async () => root.render(localized(<AskExample />, "en")));
    const conversation = container.querySelector("[data-ask-phase]");
    await act(async () => button(en.CommonHome.askReplay).click());
    expect(conversation?.getAttribute("data-ask-phase")).toBe("typing");
    await act(async () => button(en.CommonHome.askSkip).click());
    expect(conversation?.getAttribute("data-ask-phase")).toBe("complete");
    expect(vi.getTimerCount()).toBe(0);
    await act(async () => button(en.CommonHome.askReplay).click());
    await act(async () => { reducedMotion = true; motionListeners.forEach((listener) => listener()); });
    expect(conversation?.getAttribute("data-ask-phase")).toBe("complete");
    expect(vi.getTimerCount()).toBe(0);
    expect(button(en.CommonHome.askReplay).closest("[hidden]")).not.toBeNull();
    await act(async () => { reducedMotion = false; motionListeners.forEach((listener) => listener()); notifyIntersection(true); });
    expect(conversation?.getAttribute("data-ask-phase")).toBe("complete");
});

it("keeps reduced-motion playback static and cancels work on hiding or unmounting", async () => {
    vi.useFakeTimers();
    await act(async () => root.render(localized(<AskExample />, "en")));
    const conversation = container.querySelector("[data-ask-phase]");
    await act(async () => notifyIntersection(true));
    expect(conversation?.getAttribute("data-ask-phase")).toBe("complete");
    expect(vi.getTimerCount()).toBe(0);
    await act(async () => { reducedMotion = false; motionListeners.forEach((listener) => listener()); });
    await act(async () => button(en.CommonHome.askReplay).click());
    await act(async () => { pageHidden = true; document.dispatchEvent(new Event("visibilitychange")); });
    expect(conversation?.getAttribute("data-ask-phase")).toBe("complete");
    expect(vi.getTimerCount()).toBe(0);
    pageHidden = false;
    await act(async () => button(en.CommonHome.askReplay).click());
    await act(async () => root.render(null));
    expect(vi.getTimerCount()).toBe(0);
    expect(motionListeners.size).toBe(0);
});

it("keeps bilingual keys and dated risk evidence consistent", () => {
    expect(Object.keys(en.CommonHome).sort()).toEqual(Object.keys(ja.CommonHome).sort());
    const days = (earlier: string) => (Date.parse(SAMPLE_WORKSPACE.asOf) - Date.parse(earlier)) / 86_400_000;
    expect(days(SAMPLE_WORKSPACE.lastContact)).toBe(35);
    expect(days(SAMPLE_WORKSPACE.expectedClose)).toBe(12);
    expect(SAMPLE_WORKSPACE.sources.review.date).toBe(SAMPLE_WORKSPACE.lastContact);
    expect(SAMPLE_WORKSPACE.sources.close.date).toBe(SAMPLE_WORKSPACE.expectedClose);
});
