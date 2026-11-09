/** @vitest-environment jsdom */
import type { PropsWithChildren } from "react";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import AuditLogBrowser from "@/app/components/admin/AuditLogBrowser";
import OrgAuditPanel from "@/app/components/organization/OrgAuditPanel";
import type { AuditLogEntry } from "@/app/lib/types";
import { TooltipProvider } from "@/components/ui/tooltip";
import englishAdmin from "@/messages/en/admin.json";
import japaneseAdmin from "@/messages/ja/admin.json";
import englishOrg from "@/messages/en/organization.json";
import japaneseOrg from "@/messages/ja/organization.json";
import englishCommon from "@/messages/en/common.json";
import japaneseCommon from "@/messages/ja/common.json";

const api = vi.hoisted(() => ({ exportRows: vi.fn(), getOrgAudit: vi.fn(), getOrgExportPermissions: vi.fn(), reportError: vi.fn(), workspaceExport: "granted", orgExport: true }));
vi.mock("@/app/lib/api", () => ({
    exportAuditRowsCsv: api.exportRows,
    getAuditLogs: vi.fn(),
    getOrgAudit: api.getOrgAudit,
    getOrgExportPermissions: api.getOrgExportPermissions,
    ApiError: class extends Error { status = 403; },
}));
vi.mock("@/app/hooks/useWorkspace", () => ({ useWorkspace: () => ({ activeWorkspace: { id: 5, orgId: 3, orgRole: "owner" } }) }));
vi.mock("@/app/hooks/usePermissions", () => ({ usePermissionCheck: () => api.workspaceExport }));
vi.mock("@/app/hooks/useApiErrorToast", () => ({ useApiErrorToast: () => api.reportError }));
vi.mock("@/app/components/motion/Rise", () => ({ default: ({ children }: PropsWithChildren) => children }));
vi.mock("@/app/components/PageShell", () => ({ PageShell: ({ children }: PropsWithChildren) => <main>{children}</main> }));

const entries: AuditLogEntry[] = [
    { id: 42, action: "company.export", entityType: "company", actorLabel: "Auditor", currentActorLabel: "Current auditor", outcome: "success", createdAt: "2026-11-05T10:00:00Z" },
    { id: 7, action: "person.update", entityType: "person", actorLabel: "Operator", outcome: "failed", createdAt: "2026-11-05T09:00:00Z" },
];

function view(locale: "en" | "ja", organization: boolean, rows = entries) {
    const admin = locale === "en" ? englishAdmin : japaneseAdmin;
    const org = locale === "en" ? englishOrg : japaneseOrg;
    const common = locale === "en" ? englishCommon : japaneseCommon;
    api.getOrgAudit.mockResolvedValue(rows);
    return render(
        <NextIntlClientProvider locale={locale} messages={{ ...admin, ...org, ...common }} timeZone="UTC">
            <TooltipProvider>
                {organization ? <OrgAuditPanel presentation="section" />
                    : <AuditLogBrowser initialEntries={rows} pageSize={200} presentation="section" />}
            </TooltipProvider>
        </NextIntlClientProvider>,
    );
}

beforeEach(() => {
    api.workspaceExport = "granted";
    api.orgExport = true;
    api.getOrgExportPermissions.mockImplementation(async () => ({ owner: api.orgExport, admin: api.orgExport, member: false }));
});

afterEach(() => { cleanup(); vi.clearAllMocks(); });

describe("audit export controls", () => {
    it("translates organization action/entity options and chips without changing the filter values", async () => {
        const labels = japaneseAdmin.AdminAuditLog;
        view("ja", true, [
            { id: 101, action: "org.rename", entityType: "organization", actorLabel: "Auditor", createdAt: "2026-11-05T10:00:00Z" },
            { id: 102, action: "org.member.set", entityType: "organization", actorLabel: "Auditor", createdAt: "2026-11-05T09:00:00Z" },
        ]);
        const actions = await screen.findByRole("button", { name: labels.filterAction });
        fireEvent.keyDown(actions, { key: "Enter" });
        const renamed = await screen.findByRole("menuitemcheckbox", { name: new RegExp(labels.verbRenamed) });
        expect(screen.getByRole("menuitemcheckbox", { name: new RegExp(`^${labels.verbChanged}`) })).toBeTruthy();
        expect(screen.queryByRole("menuitemcheckbox", { name: /^rename\b/ })).toBeNull();
        expect(screen.queryByRole("menuitemcheckbox", { name: /^set\b/ })).toBeNull();
        fireEvent.click(renamed);
        expect(screen.getAllByText(labels.verbRenamed).length).toBeGreaterThan(1);
        fireEvent.keyDown(renamed, { key: "Escape" });
        await waitFor(() => expect(screen.queryByRole("menu")).toBeNull());
        fireEvent.keyDown(screen.getByRole("button", { name: labels.filterEntity }), { key: "Enter" });
        const organization = await screen.findByRole("menuitemcheckbox", { name: new RegExp(labels.exportScopeOrganization) });
        expect(screen.queryByRole("menuitemcheckbox", { name: /^organization\b/ })).toBeNull();
        fireEvent.click(organization);
        expect(screen.getAllByText(labels.exportScopeOrganization).length).toBeGreaterThan(1);
        fireEvent.keyDown(organization, { key: "Escape" });
        await waitFor(() => expect(screen.queryByRole("menu")).toBeNull());
        await waitFor(() => expect(screen.getByRole("button", { name: labels.exportCsv }).hasAttribute("disabled")).toBe(false));
        fireEvent.click(screen.getByRole("button", { name: labels.exportCsv }));
        await waitFor(() => expect(api.exportRows).toHaveBeenCalledWith([101], 3, expect.anything()));
    });

    it.each([
        { locale: "en", organization: false }, { locale: "en", organization: true },
        { locale: "ja", organization: false }, { locale: "ja", organization: true },
    ] as const)("exports the filtered loaded rows in $locale, organization=$organization", async ({ locale, organization }) => {
        const admin = locale === "en" ? englishAdmin.AdminAuditLog : japaneseAdmin.AdminAuditLog;
        view(locale, organization);
        const search = await screen.findByRole("textbox", { name: admin.searchAria });
        fireEvent.change(search, { target: { value: organization ? "Current auditor" : "Auditor" } });
        await waitFor(() => expect(screen.getByRole("button", { name: admin.exportCsv }).hasAttribute("disabled")).toBe(false));
        fireEvent.click(screen.getByRole("button", { name: admin.exportCsv }));
        await waitFor(() => expect(api.exportRows).toHaveBeenCalledOnce());
        const [ids, orgId, init] = api.exportRows.mock.calls[0];
        expect(ids).toEqual([42]);
        expect(orgId).toBe(organization ? 3 : undefined);
        expect(new Headers(init.headers).get("X-Workspace-Id")).toBe("5");
        expect(screen.getByRole("button", { name: admin.exportCsv }).getAttribute("aria-describedby")).toBe(
            organization ? "organization-audit-export-scope" : "workspace-audit-export-scope",
        );
        fireEvent.change(search, { target: { value: "no matching entry" } });
        expect(screen.getByRole("button", { name: admin.exportCsv }).hasAttribute("disabled")).toBe(true);
    });

    it.each(["en", "ja"] as const)("refuses workspace export without EXPORT_DATA in %s", (locale) => {
        api.workspaceExport = "denied";
        const labels = locale === "en" ? englishAdmin.AdminAuditLog : japaneseAdmin.AdminAuditLog;
        view(locale, false);
        expect(screen.getByText(labels.exportPermissionDenied)).toBeTruthy();
        const button = screen.getByRole("button", { name: labels.exportCsv });
        expect(button.hasAttribute("disabled")).toBe(true);
        fireEvent.click(button);
        expect(api.exportRows).not.toHaveBeenCalled();
    });

    it("uses organization grants even when workspace export is denied", async () => {
        api.workspaceExport = "denied";
        view("en", true);
        const button = await screen.findByRole("button", { name: englishAdmin.AdminAuditLog.exportCsv });
        await waitFor(() => expect(button.hasAttribute("disabled")).toBe(false));
        fireEvent.click(button);
        await waitFor(() => expect(api.exportRows).toHaveBeenCalledOnce());
    });

    it("refuses organization exports even when workspace export is granted", async () => {
        api.orgExport = false;
        view("en", true);
        expect(await screen.findByText(englishAdmin.AdminAuditLog.exportPermissionDenied)).toBeTruthy();
        const button = screen.getByRole("button", { name: englishAdmin.AdminAuditLog.exportCsv });
        expect(button.hasAttribute("disabled")).toBe(true);
        fireEvent.click(button);
        expect(api.exportRows).not.toHaveBeenCalled();
    });

    it("fails closed when organization policy cannot be checked", async () => {
        api.getOrgExportPermissions.mockRejectedValueOnce(new Error("Unavailable"));
        view("en", true);
        expect(await screen.findByText(englishAdmin.AdminAuditLog.exportPermissionUnavailable)).toBeTruthy();
        expect(screen.getByRole("button", { name: englishAdmin.AdminAuditLog.exportCsv }).hasAttribute("disabled")).toBe(true);
    });

    it("caps the selection at 200 rows in the displayed order and states that scope", async () => {
        const rows = Array.from({ length: 205 }, (_, i) => ({ ...entries[0], id: i + 1 }));
        view("en", false, rows);
        expect(screen.getByText(/CSV includes 200 loaded rows matching your filters/)).toBeTruthy();
        fireEvent.click(screen.getByRole("button", { name: englishAdmin.AdminAuditLog.exportCsv }));
        await waitFor(() => expect(api.exportRows).toHaveBeenCalledOnce());
        expect(api.exportRows.mock.calls[0][0]).toEqual(rows.slice(0, 200).map(({ id }) => id));
    });

    it("keeps the action busy until completion and reports a rejected export", async () => {
        let rejectExport: ((error: Error) => void) | undefined;
        api.exportRows.mockImplementationOnce(() => new Promise<void>((_resolve, reject) => { rejectExport = reject; }));
        view("en", false);
        fireEvent.click(screen.getByRole("button", { name: englishAdmin.AdminAuditLog.exportCsv }));
        expect(screen.getByRole("button", { name: englishAdmin.AdminAuditLog.exporting }).hasAttribute("disabled")).toBe(true);
        const error = new Error("Export refused");
        rejectExport?.(error);
        await waitFor(() => expect(api.reportError).toHaveBeenCalledWith(error, "exportFailed"));
        expect(screen.getByRole("button", { name: englishAdmin.AdminAuditLog.exportCsv }).hasAttribute("disabled")).toBe(false);
    });

    it("offers no unfiltered fallback when the log is empty", () => {
        view("en", false, []);
        expect(screen.getByRole("button", { name: englishAdmin.AdminAuditLog.exportCsv }).hasAttribute("disabled")).toBe(true);
        expect(api.exportRows).not.toHaveBeenCalled();
    });
});
