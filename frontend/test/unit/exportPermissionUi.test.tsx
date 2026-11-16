/** @vitest-environment jsdom */
import type { PropsWithChildren, ReactNode } from "react";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import RecordsActions from "@/app/components/import/RecordsActions";
import ReportDocumentBoard from "@/app/components/reports/ReportDocumentBoard";
import type { AppAction } from "@/app/lib/actions/types";
import type { ReportDefinition, ReportSnapshot } from "@/app/lib/types";
import englishImports from "@/messages/en/importExport.json";
import japaneseImports from "@/messages/ja/importExport.json";
import englishReports from "@/messages/en/reports.json";
import japaneseReports from "@/messages/ja/reports.json";

const state = vi.hoisted(() => ({
    permission: "granted",
    register: vi.fn<(actions: readonly AppAction[]) => void>(),
    run: vi.fn(),
    snapshotCsv: vi.fn(),
    exportPng: vi.fn(),
    schedule: vi.fn<(props: { canExport: boolean }) => void>(),
}));
vi.mock("next/navigation", () => ({ usePathname: () => "/records/contacts", useRouter: () => ({ refresh: vi.fn() }) }));
vi.mock("@/app/hooks/usePermissions", () => ({ usePermissionCheck: () => state.permission }));
vi.mock("@/app/hooks/useWorkspace", () => ({ useWorkspace: () => ({ activeWorkspaceId: 5, activeWorkspace: { id: 5 }, switching: false }) }));
vi.mock("@/app/hooks/useActions", () => ({
    useActions: () => ({ pendingIds: new Set(), run: state.run }),
    useRegisterActions: state.register,
}));
vi.mock("@/app/hooks/useApiErrorToast", () => ({ useApiErrorToast: () => vi.fn() }));
vi.mock("@/app/lib/api", () => ({
    subscribeClientRequestIdentityInvalidation: () => () => undefined,
    exportReportSnapshotCsv: state.snapshotCsv,
    exportReportPng: state.exportPng,
    exportReportCsv: vi.fn(),
    generateReport: vi.fn(),
}));
vi.mock("@/app/components/reports/ScheduleManager", () => ({ default: (props: { canExport: boolean }) => {
    state.schedule(props);
    return null;
} }));
vi.mock("@/app/components/import/LazyImportDialog", () => ({ default: () => null }));
vi.mock("@/app/components/records/DeleteRecordDialog", () => ({ default: () => null }));
vi.mock("@/components/ui/split-button", () => ({ SplitButton: ({ children }: PropsWithChildren) => <div>{children}</div> }));
vi.mock("@/components/ui/dropdown-menu", () => ({
    DropdownMenu: ({ children }: PropsWithChildren) => children,
    DropdownMenuTrigger: ({ children }: PropsWithChildren) => children,
    DropdownMenuContent: ({ children }: PropsWithChildren) => children,
    DropdownMenuItem: ({ children, disabled, onSelect }: { children: ReactNode; disabled?: boolean; onSelect?: () => void }) =>
        <button type="button" disabled={disabled} onClick={onSelect}>{children}</button>,
}));

const definition: ReportDefinition = {
    id: 1, name: "Quarterly review", description: null, cadence: "monthly", templateKey: null,
    createdBy: 1, createdAt: "2026-11-01T00:00:00Z", updatedAt: "2026-11-01T00:00:00Z",
    config: { widgets: [], layout: [], filters: null, range: null, bucket: "month" },
};
const snapshot: ReportSnapshot = {
    id: 2, reportDefinitionId: 1, periodStart: "2026-10-01", periodEnd: "2026-10-31", origin: "manual",
    generatedBy: 1, generatedAt: "2026-11-01T00:00:00Z",
    computedResult: {
        definition, periodStart: "2026-10-01", periodEnd: "2026-10-31", priorPeriodStart: "2026-09-01", priorPeriodEnd: "2026-09-30",
        narrative: { available: false, sections: [], findings: [], reason: null, generatedAt: null, warnings: 0 },
        widgets: [], appendix: [], citations: [], generatedAt: "2026-11-01T00:00:00Z",
    },
};

function viewRecords(locale: "en" | "ja", entity: "persons" | "companies" | "deals" | "products") {
    const props = { onNew: vi.fn(), newLabel: "New", newAriaLabel: "New", onExport: vi.fn() };
    return render(
        <NextIntlClientProvider locale={locale} messages={locale === "en" ? englishImports : japaneseImports}>
            {entity === "products" ? <RecordsActions {...props} entity={entity} />
                : <RecordsActions {...props} entity={entity} onImported={vi.fn()} />}
        </NextIntlClientProvider>,
    );
}

function viewReport(locale: "en" | "ja") {
    return render(
        <NextIntlClientProvider locale={locale} messages={locale === "en" ? englishReports : japaneseReports} timeZone="UTC">
            <ReportDocumentBoard definition={definition} initialSnapshots={[]} initialSnapshot={snapshot}
                canUpdateReports={false} canDeleteReports={false} currentUserId={1} defaultTimezone="UTC" />
        </NextIntlClientProvider>,
    );
}

beforeEach(() => { state.permission = "granted"; });
afterEach(() => { cleanup(); vi.clearAllMocks(); });

describe.each(["en", "ja"] as const)("export permission controls in %s", (locale) => {
    it.each(["persons", "companies", "deals", "products"] as const)("disables %s export and omits its command-palette action when denied", (entity) => {
        state.permission = "denied";
        viewRecords(locale, entity);
        const labels = locale === "en" ? englishImports.importExport : japaneseImports.importExport;
        const button = screen.getByRole("button", { name: new RegExp(labels.exportCurrentView) });
        expect(button.hasAttribute("disabled")).toBe(true);
        expect(screen.getByText(labels.exportPermissionDenied)).toBeTruthy();
        fireEvent.click(button);
        expect(state.run).not.toHaveBeenCalled();
        expect(state.register).toHaveBeenLastCalledWith([]);
    });

    it("registers the current-view export when the permission is granted", () => {
        viewRecords(locale, "persons");
        expect(state.register.mock.lastCall?.[0].map(({ id }) => id)).toEqual(["utility.export-current-contacts"]);
    });

    it("disables report CSV, PNG, and print with an explanation when denied", () => {
        state.permission = "denied";
        viewReport(locale);
        const labels = locale === "en" ? englishReports.Reports : japaneseReports.Reports;
        expect(screen.getByText(labels.exportPermissionDenied)).toBeTruthy();
        for (const label of [labels.document.export, labels.document.csv, labels.document.png, labels.document.pdf]) {
            const button = screen.getByRole("button", { name: label });
            expect(button.hasAttribute("disabled")).toBe(true);
            fireEvent.click(button);
        }
        expect(state.snapshotCsv).not.toHaveBeenCalled();
        expect(state.exportPng).not.toHaveBeenCalled();
        expect(state.schedule.mock.lastCall?.[0].canExport).toBe(false);
    });

    it("allows a frozen report CSV export when granted", () => {
        viewReport(locale);
        const labels = locale === "en" ? englishReports.Reports : japaneseReports.Reports;
        const button = screen.getByRole("button", { name: labels.document.csv });
        expect(button.hasAttribute("disabled")).toBe(false);
        fireEvent.click(button);
        expect(state.snapshotCsv).toHaveBeenCalledWith(1, 2);
        expect(state.schedule.mock.lastCall?.[0].canExport).toBe(true);
    });

    it("fails closed and distinguishes unavailable permissions", () => {
        state.permission = "unavailable";
        viewRecords(locale, "persons");
        const labels = locale === "en" ? englishImports.importExport : japaneseImports.importExport;
        expect(screen.getByText(labels.exportPermissionUnavailable)).toBeTruthy();
        expect(state.register).toHaveBeenLastCalledWith([]);
    });
});
