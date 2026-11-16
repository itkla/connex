/** @vitest-environment jsdom */
import { type ReactNode } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { act, cleanup, getByRole, render, screen } from "@testing-library/react";
import { createTranslator, NextIntlClientProvider } from "next-intl";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import WorkspaceSwitcher from "@/app/components/WorkspaceSwitcher";
import SummaryTile from "@/app/components/SummaryTile";
import CalendarShell from "@/app/components/calendar/CalendarShell";
import TodayPriorities from "@/app/components/dashboard/TodayPriorities";
import { NowProvider } from "@/app/hooks/useNow";
import { TooltipProvider } from "@/components/ui/tooltip";
import enCommon from "@/messages/en/common.json";
import jaCommon from "@/messages/ja/common.json";
import enCalendar from "@/messages/en/calendar.json";
import jaCalendar from "@/messages/ja/calendar.json";
import enWorkspace from "@/messages/en/workspace.json";
import jaWorkspace from "@/messages/ja/workspace.json";
import enDashboard from "@/messages/en/dashboard.json";
import jaDashboard from "@/messages/ja/dashboard.json";

const state = vi.hoisted(() => ({ locale: "en" as "en" | "ja", workspace: "North team", workspaceCount: 1 }));
const catalogues = {
    en: { ...enCommon, ...enCalendar, ...enWorkspace, ...enDashboard },
    ja: { ...jaCommon, ...jaCalendar, ...jaWorkspace, ...jaDashboard },
};
vi.mock("next-intl/server", () => ({
    getTranslations: async (namespace: "DashboardToday") => createTranslator({
        locale: state.locale, messages: catalogues[state.locale], namespace,
    }),
}));
vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh: vi.fn() }) }));
vi.mock("@/app/hooks/useWorkspace", () => ({ useWorkspace: () => ({
    workspaces: Array.from({ length: state.workspaceCount }, (_, index) => ({ id: index + 1, name: state.workspace })), activeWorkspaceId: 1,
    activeWorkspace: { id: 1, name: state.workspace }, switching: false, switchTo: vi.fn(),
}) }));
vi.mock("@/app/components/NewWorkspaceDialog", () => ({ default: () => null }));
vi.mock("@/app/components/calendar/QuickCreateHost", () => ({ default: () => null }));
vi.mock("@/app/components/activity/activities/ActivityDialog", () => ({ default: () => null }));

beforeEach(() => {
    vi.stubGlobal("ResizeObserver", class {
        observe = vi.fn();
        unobserve = vi.fn();
        disconnect = vi.fn();
    });
    vi.stubGlobal("matchMedia", (query: string): MediaQueryList => ({
        matches: false, media: query, onchange: null,
        addListener: vi.fn(), removeListener: vi.fn(),
        addEventListener: vi.fn(), removeEventListener: vi.fn(), dispatchEvent: () => true,
    }));
});

afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
});

function localized(children: ReactNode, locale: "en" | "ja") {
    return (
        <NextIntlClientProvider locale={locale} messages={catalogues[locale]} timeZone="UTC">
            <TooltipProvider>{children}</TooltipProvider>
        </NextIntlClientProvider>
    );
}

describe.each(["en", "ja"] as const)("label in name (%s)", (locale) => {
    it.each([
        { compact: false, count: 1 }, { compact: false, count: 2 },
        { compact: true, count: 1 }, { compact: true, count: 2 },
    ])("names the workspace and count, compact=$compact count=$count", ({ compact, count }) => {
        state.workspace = locale === "ja" ? "営業チーム" : "North team";
        state.workspaceCount = count;
        const t = createTranslator({ locale, messages: catalogues[locale], namespace: "WorkspaceSwitcher" });
        render(localized(<WorkspaceSwitcher compact={compact} />, locale));
        const visibleCount = count === 1 ? t("oneWorkspace") : t("manyWorkspaces", { count });
        const name = t("switchAria", { workspace: state.workspace, count: visibleCount });
        const button = screen.getByRole("button", { name });
        expect(name.startsWith(state.workspace)).toBe(true);
        expect(name).toContain(visibleCount);
        if (!compact) {
            expect(button.textContent).toContain(state.workspace);
            expect(button.textContent).toContain(visibleCount);
        }
    });

    it("names the date-jump control with the period it displays", () => {
        const now = Date.UTC(2026, 10, 16, 12);
        const html = renderToStaticMarkup(localized(
            <NowProvider value={now}><CalendarShell currentUserId={1} /></NowProvider>, locale,
        ));
        const container = document.createElement("div");
        container.innerHTML = html;
        const period = new Intl.DateTimeFormat(locale, { month: "long", year: "numeric", timeZone: "UTC" }).format(now);
        const button = getByRole(container, "button", { name: period });
        expect(button.textContent).toBe(period);
    });

    it.each([true, false])("names the whole visible My Work link, available=%s", async (available) => {
        state.locale = locale;
        const t = createTranslator({ locale, messages: catalogues[locale], namespace: "DashboardToday" });
        const content = await TodayPriorities({ summary: available ? { ok: true, data: {
            knownTotal: 0, knownCritical: 0, totalsComplete: true, availability: "available",
            sourceStatuses: [], asOf: "2026-11-16T12:00:00Z",
        } } : { ok: false } });
        render(content);
        const visible = t(available ? "allClear" : "unavailable");
        const expected = `${visible}${t("viewMyWork")}`.replace(/\s+/g, "");
        const link = screen.getByRole("link", { name: (name) => name.replace(/\s+/g, "") === expected });
        expect(link.textContent?.startsWith(visible)).toBe(true);
        expect(link.getAttribute("href")).toBe("/me");
    });
});

describe("metric definitions", () => {
    it("exposes a named, focusable info button that opens the definition on focus", async () => {
        const label = "Open pipeline";
        const definition = "Open deals in the selected pipeline.";
        render(localized(<SummaryTile label={label} value="12" tooltip={definition} />, "en"));
        const trigger = screen.getByRole("button", { name: label });
        expect(trigger.tabIndex).toBe(0);
        expect(screen.queryByRole("tooltip")).toBeNull();
        act(() => trigger.focus());
        expect(document.activeElement).toBe(trigger);
        const tooltip = await screen.findByRole("tooltip");
        expect(tooltip.textContent).toContain(definition);
        expect(trigger.getAttribute("aria-describedby")).toBe(tooltip.id);
    });
});
