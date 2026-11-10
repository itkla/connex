/** @vitest-environment jsdom */
import type { PropsWithChildren } from "react";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import ScheduleDialog from "@/app/components/reports/ScheduleDialog";
import type { ReportSchedule } from "@/app/lib/types";
import english from "@/messages/en/reports.json";
import japanese from "@/messages/ja/reports.json";

vi.mock("@/components/ui/responsive-dialog", () => ({
    ResponsiveDialog: ({ children, open }: PropsWithChildren<{ open: boolean }>) => open ? children : null,
    ResponsiveDialogContent: ({ children }: PropsWithChildren) => <div>{children}</div>,
    ResponsiveDialogHeader: ({ children }: PropsWithChildren) => <header>{children}</header>,
    ResponsiveDialogTitle: ({ children }: PropsWithChildren) => <h2>{children}</h2>,
    ResponsiveDialogDescription: ({ children }: PropsWithChildren) => <p>{children}</p>,
    ResponsiveDialogFooter: ({ children }: PropsWithChildren) => <footer>{children}</footer>,
    ResponsiveDialogClose: ({ children }: PropsWithChildren) => children,
}));

const schedule: ReportSchedule = {
    id: 1, reportDefinitionId: 2, cadence: "monthly", recipientUserIds: [3],
    recipients: [{ userId: 3, displayName: "Recipient", email: "recipient@example.com" }],
    timezone: "UTC", hourOfDay: 9, enabled: true, runAsUserId: 3, runAsLabel: "Owner",
    nextRunAt: "2026-12-01T09:00:00Z", lastRunAt: null, createdBy: 3,
    createdAt: "2026-11-01T00:00:00Z", updatedAt: "2026-11-01T00:00:00Z",
};

function view(locale: "en" | "ja", canManage: boolean, canExport: boolean, onDelete = vi.fn()) {
    return render(
        <NextIntlClientProvider locale={locale} timeZone="UTC" messages={locale === "en" ? english : japanese}>
            <ScheduleDialog open schedule={schedule}
                members={[{ id: 3, username: "recipient", displayName: "Recipient", email: "recipient@example.com", role: "member", builtInRole: "member", status: "active" }]}
                canManage={canManage} canExport={canExport} loading={false} loadFailed={false} membersFailed={false}
                defaultTimezone="UTC" onOpenChange={vi.fn()} onRetry={vi.fn()}
                onSubmit={vi.fn(async () => undefined)} onRequestDelete={onDelete} />
        </NextIntlClientProvider>,
    );
}

beforeEach(() => {
    vi.stubGlobal("ResizeObserver", class { observe() {} unobserve() {} disconnect() {} });
});
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

describe.each(["en", "ja"] as const)("scheduled export permission in %s", (locale) => {
    it("explains the export refusal while retaining review and deletion", () => {
        const onDelete = vi.fn();
        view(locale, true, false, onDelete);
        const copy = (locale === "en" ? english : japanese).Reports.schedule;
        expect(screen.getByText(copy.exportRequired)).toBeTruthy();
        expect(screen.queryByRole("button", { name: copy.saveChanges })).toBeNull();
        expect(screen.getByText("Recipient")).toBeTruthy();
        fireEvent.click(screen.getByRole("button", { name: copy.delete }));
        expect(onDelete).toHaveBeenCalledOnce();
    });

    it("does not offer deletion to a report reader", () => {
        view(locale, false, false);
        const copy = (locale === "en" ? english : japanese).Reports.schedule;
        expect(screen.getByText(copy.readOnly)).toBeTruthy();
        expect(screen.queryByRole("button", { name: copy.delete })).toBeNull();
        expect(screen.queryByRole("button", { name: copy.saveChanges })).toBeNull();
    });

    it("offers editing when report update and export are granted", () => {
        view(locale, true, true);
        const copy = (locale === "en" ? english : japanese).Reports.schedule;
        expect(screen.queryByText(copy.exportRequired)).toBeNull();
        expect(screen.getByRole("button", { name: copy.saveChanges }).hasAttribute("disabled")).toBe(false);
    });
});
