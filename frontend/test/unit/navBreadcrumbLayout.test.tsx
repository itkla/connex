/** @vitest-environment jsdom */
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";
import { afterEach, describe, expect, it, vi } from "vitest";
import NavBreadcrumb from "@/app/components/NavBreadcrumb";
import type { BreadcrumbCrumb } from "@/app/lib/breadcrumbRoutes";
import en from "@/messages/en/common.json";
import ja from "@/messages/ja/common.json";

const state = vi.hoisted<{ trail: BreadcrumbCrumb[] }>(() => ({ trail: [] }));
vi.mock("@/app/hooks/useNavTrail", () => ({ useNavTrail: () => state.trail }));
vi.mock("next/navigation", () => ({
    useSearchParams: () => new URLSearchParams(),
    useRouter: () => ({ back: vi.fn() }),
}));

afterEach(cleanup);

describe.each(["en", "ja"] as const)("NAV-04 breadcrumb priority (%s)", (locale) => {
    const messages = locale === "en" ? en : ja;

    it("protects the current label, bounds ancestors and lets the workspace shrink first", () => {
        state.trail = [
            { pathname: "/dashboard", label: "Long workspace name", current: false },
            { pathname: "/records/contacts", label: messages.CommonBreadcrumb.contacts, current: false, returnCollection: "contacts" },
            { pathname: "/records/contacts/42", label: locale === "en" ? "Alexandra Robertson" : "山田株式会社営業責任者", current: true },
        ];
        const { container } = render(<NextIntlClientProvider locale={locale} messages={messages}>
            <NavBreadcrumb />
        </NextIntlClientProvider>);
        const current = screen.getByText(state.trail[2].label);
        expect(current.closest("li")?.classList.contains("min-w-[7rem]")).toBe(true);
        expect(current.classList.contains("shrink-0")).toBe(true);
        const ancestors = container.querySelectorAll('a');
        expect(ancestors).toHaveLength(2);
        for (const link of ancestors) {
            expect(link.classList.contains("truncate")).toBe(true);
            expect(link.closest("li")?.classList.contains("min-w-[3ch]")).toBe(true);
            expect(link.closest("li")?.classList.contains("max-w-[6rem]")).toBe(true);
        }
        expect(ancestors[0].closest("li")?.classList.contains("shrink-[1000]")).toBe(true);
        expect(ancestors[1].closest("li")?.classList.contains("shrink-[100]")).toBe(true);
    });

    it("INS-21 keeps Reports and the report reachable from the snapshot overflow menu", async () => {
        state.trail = [
            { pathname: "/dashboard", label: "Long workspace name", current: false },
            { pathname: "/insights/reports", label: messages.CommonBreadcrumb.reports, current: false },
            { pathname: "/insights/reports/42", label: "Monthly pipeline review", current: false },
            { pathname: "/insights/reports/42/snapshots/7", label: messages.CommonBreadcrumb.snapshot, current: true },
        ];
        render(<NextIntlClientProvider locale={locale} messages={messages}>
            <NavBreadcrumb />
        </NextIntlClientProvider>);
        const overflow = screen.getByRole("button", { name: messages.CommonBreadcrumb.showHidden });
        overflow.focus();
        fireEvent.keyDown(overflow, { key: "Enter" });
        const reports = await screen.findByRole("menuitem", { name: messages.CommonBreadcrumb.reports });
        expect(reports.getAttribute("href")).toBe("/insights/reports");
        expect(screen.getByRole("menuitem", { name: "Monthly pipeline review" }).getAttribute("href")).toBe("/insights/reports/42");
    });
});
