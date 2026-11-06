/** @vitest-environment jsdom */
import { isValidElement, type ReactElement, type ReactNode } from "react";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { createTranslator, NextIntlClientProvider } from "next-intl";
import { afterEach, describe, expect, it, vi } from "vitest";

import MePage from "@/app/(app)/me/page";
import { MyWorkDealSection, MyWorkHistorySection, MyWorkPulseSection, MyWorkQueueSection, MyWorkSectionFailure } from "@/app/components/me/MyWorkSections";
import SectionUnavailable from "@/app/components/SectionUnavailable";
import PulseStrip from "@/app/components/me/PulseStrip";
import Timeline from "@/app/components/me/Timeline";
import PersonalDealSignals from "@/app/components/me/PersonalDealSignals";
import type { User } from "@/app/lib/types";
import { getMyWorkReadResultFromCookie } from "@/app/lib/api";
import en from "@/messages/en/me.json";
import ja from "@/messages/ja/me.json";
import enErrors from "@/messages/en/errors.json";
import jaErrors from "@/messages/ja/errors.json";

const fixture = vi.hoisted(() => ({ locale: "en", refresh: vi.fn() }));
const user: User = {
    id: 51, displayName: "Member", username: "member", email: "member@example.test", timezone: "UTC", locale: "en",
    createdAt: "2026-01-01T00:00:00Z", updatedAt: "2026-01-01T00:00:00Z",
};
vi.mock("next/headers", () => ({ headers: async () => new Headers({ cookie: "connex_workspace=7" }) }));
vi.mock("next/navigation", () => ({
    useRouter: () => ({ refresh: fixture.refresh }),
    redirect: (href: string) => { throw new Error(`redirect:${href}`); },
}));
vi.mock("next-intl/server", () => ({
    getTranslations: async () => createTranslator({ locale: fixture.locale, messages: fixture.locale === "ja" ? ja : en, namespace: "MePage" }),
    getTimeZone: async () => "UTC", getLocale: async () => "en",
}));
vi.mock("@/app/lib/api", () => ({
    getCurrentUserResultFromCookie: async () => ({ ok: true, data: {
        id: 51, displayName: "Member", username: "member", timezone: "UTC", locale: "en",
        createdAt: "2026-01-01T00:00:00Z", updatedAt: "2026-01-01T00:00:00Z",
    } }),
    getMyWorkReadResultFromCookie: vi.fn(() => new Promise(() => {})),
    getContactsFromCookie: () => { throw new Error("Workspace-wide contact collection requested"); },
    getDealsFromCookie: () => { throw new Error("Workspace-wide deal collection requested"); },
    getMyWorkResultFromCookie: async () => ({ ok: false }),
}));

function childrenOf(value: unknown): value is { children: ReactNode } {
    return typeof value === "object" && value !== null && "children" in value;
}
function find(node: ReactNode, type: unknown): ReactElement[] {
    if (Array.isArray(node)) return node.flatMap((child: ReactNode) => find(child, type));
    if (!isValidElement(node)) return [];
    if (node.type === type) return [node];
    return childrenOf(node.props) ? find(node.props.children, type) : [];
}

afterEach(() => { cleanup(); vi.useRealTimers(); vi.clearAllMocks(); fixture.locale = "en"; });

describe("bounded My Work rendering", () => {
    it("returns the page and independent queue while every supporting read remains pending", async () => {
        const page = await MePage({ searchParams: Promise.resolve({ currency: "JPY" }) });
        expect(getMyWorkReadResultFromCookie).toHaveBeenCalledTimes(5);
        expect(getMyWorkReadResultFromCookie).toHaveBeenCalledWith("deal-kpis", "connex_workspace=7", "JPY");
        expect(find(page, MyWorkQueueSection)).toHaveLength(1);
        expect(find(page, MyWorkHistorySection)).toHaveLength(1);
    });

    it("keeps a failed history section visible instead of removing it as empty", async () => {
        const section = await MyWorkHistorySection({ user, read: Promise.resolve({ ok: false, status: 503 }) });
        expect(find(section, MyWorkSectionFailure)[0]?.props).toEqual({ status: 503 });
        expect(find(section, Timeline)).toHaveLength(0);
    });

    it("retains genuine empty history and the authored-note continuation target", async () => {
        const section = await MyWorkHistorySection({ user, read: Promise.resolve({ ok: true, data: { tasks: [], activities: [], notes: [] } }) });
        expect(find(section, MyWorkSectionFailure)).toHaveLength(0);
        expect(find(section, Timeline)[0]?.props).toMatchObject({ tasks: [], activities: [], notes: [], noteTarget: { type: "user", id: 51 } });
    });

    it("counts all daily touches independently of capped history previews", async () => {
        vi.useFakeTimers();
        vi.setSystemTime(new Date("2026-09-15T12:00:00Z"));
        const section = await MyWorkPulseSection({ read: Promise.resolve({ ok: true, data: [
            { date: "2026-09-14", count: 101 }, { date: "2026-09-15", count: 4 },
        ] }) });
        expect(find(section, PulseStrip)[0]?.props).toMatchObject({ totalTouches: 105, streak: 2 });
        const failed = await MyWorkPulseSection({ read: Promise.resolve({ ok: false, status: 503 }) });
        expect(find(failed, PulseStrip)).toHaveLength(0);
        expect(find(failed, MyWorkSectionFailure)).toHaveLength(1);
    });

    it("never fabricates zero money when a KPI read fails", async () => {
        const failed = await MyWorkDealSection({ read: Promise.resolve({ ok: false, status: 503 }) });
        expect(find(failed, PersonalDealSignals)).toHaveLength(0);
        expect(find(failed, MyWorkSectionFailure)).toHaveLength(1);
    });

    it("redirects an expired section session through sign-in", async () => {
        await expect(MyWorkSectionFailure({ status: 401 })).rejects.toThrow("redirect:/auth/login");
    });

    it.each(["en", "ja"])("offers localized Retry and permission guidance in %s", async (locale) => {
        fixture.locale = locale;
        const messages = locale === "ja" ? { ...ja, ...jaErrors } : { ...en, ...enErrors };
        const failed = await MyWorkSectionFailure({ status: 403 });
        expect(find(failed, SectionUnavailable)).toHaveLength(1);
        render(<NextIntlClientProvider locale={locale} messages={messages}>{failed}</NextIntlClientProvider>);
        expect(screen.getByText(messages.MePage.accessDeniedTitle)).toBeTruthy();
        fireEvent.click(screen.getByRole("button", { name: messages.SectionUnavailable.retry }));
        expect(fixture.refresh).toHaveBeenCalledOnce();
    });
});
