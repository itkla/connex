/** @vitest-environment jsdom */
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import OrganizationLifecyclePanel from "@/app/components/organization/OrganizationLifecyclePanel";
import english from "@/messages/en/organization.json";
import japanese from "@/messages/ja/organization.json";

const api = vi.hoisted(() => ({ get: vi.fn(), update: vi.fn(), grant: vi.fn(), showError: vi.fn() }));
vi.mock("@/app/lib/api", () => ({
    getOrgExportPermissions: api.get,
    updateOrgExportPermission: api.update,
    requestWorkspaceTenantExport: api.grant,
    teardownOrganization: vi.fn(),
    teardownOrganizationWorkspace: vi.fn(),
    ApiError: class extends Error {},
}));
vi.mock("@/app/hooks/useApiErrorToast", () => ({ useApiErrorToast: () => api.showError }));
vi.mock("@/app/hooks/usePasskeyStepUpError", () => ({ usePasskeyStepUpErrorHandler: () => () => false }));
vi.mock("@/app/lib/toast", () => ({ toastSuccess: vi.fn() }));

function view(locale: "en" | "ja", orgId = 3) {
    return (
        <NextIntlClientProvider locale={locale} timeZone="UTC" messages={locale === "en" ? english : japanese}>
            <OrganizationLifecyclePanel organization={{ id: orgId, name: "Organization", slug: "organization", identityVersion: 1, updatedAt: "2026-11-01" }}
                workspaces={[{ id: 5, name: "Workspace", slug: "workspace", timezone: "UTC", rosterVisible: false, memberships: [], membershipsTruncated: false }]}
                orgRole="admin" hasMore={false} loadingMore={false} onLoadMore={vi.fn()} />
        </NextIntlClientProvider>
    );
}

beforeEach(() => {
    vi.stubGlobal("matchMedia", (query: string) => ({
        matches: false, media: query, onchange: null,
        addEventListener: vi.fn(), removeEventListener: vi.fn(),
        addListener: vi.fn(), removeListener: vi.fn(), dispatchEvent: () => true,
    }));
    api.get.mockResolvedValue({ owner: true, admin: true, member: false });
    api.update.mockResolvedValue({ owner: true, admin: false, member: false });
    api.grant.mockResolvedValue({ expiresAt: "2026-11-01T00:02:00Z", downloadPath: "/api/orgs/3/workspaces/5/export" });
});
afterEach(() => { cleanup(); vi.resetAllMocks(); vi.unstubAllGlobals(); });

describe.each(["en", "ja"] as const)("organization export grants in %s", (locale) => {
    it("disables archive creation when the organization admin role lacks its grant", async () => {
        api.get.mockResolvedValueOnce({ owner: true, admin: false, member: false });
        render(view(locale));
        const copy = locale === "en" ? english.OrgDataLifecycle : japanese.OrgDataLifecycle;
        expect(await screen.findByText(copy.exportPermissionDenied)).toBeTruthy();
        const button = screen.getByRole("button", { name: copy.exportWorkspace });
        expect(button.hasAttribute("disabled")).toBe(true);
        fireEvent.click(button);
        expect(api.grant).not.toHaveBeenCalled();
        expect(screen.getAllByRole("switch").every((control) => !control.hasAttribute("disabled"))).toBe(true);
    });

    it("prepares a browser download when the organization role has export access", async () => {
        render(view(locale));
        const copy = locale === "en" ? english.OrgDataLifecycle : japanese.OrgDataLifecycle;
        const button = screen.getByRole("button", { name: copy.exportWorkspace });
        await waitFor(() => expect(button.hasAttribute("disabled")).toBe(false));
        fireEvent.click(button);
        const link = await screen.findByRole("link", { name: copy.downloadExport });
        expect(link.getAttribute("href")).toBe("/api/orgs/3/workspaces/5/export");
        expect(api.grant).toHaveBeenCalledWith(3, 5);
        const label = copy.exportPermissionLabel.replace("{role}", copy.roleAdmin);
        fireEvent.click(screen.getByRole("switch", { name: label }));
        await waitFor(() => expect(api.update).toHaveBeenCalledWith(3, "admin", false));
        await waitFor(() => expect(screen.queryByRole("link", { name: copy.downloadExport })).toBeNull());
        expect(screen.getByRole("button", { name: copy.exportWorkspace }).hasAttribute("disabled")).toBe(true);
    });

    it.each(["resolve", "reject"] as const)("keeps a saved withdrawal when an older policy read later %ss", async (outcome) => {
        const pending = Promise.withResolvers<{ owner: boolean; admin: boolean; member: boolean }>();
        render(view(locale));
        const copy = locale === "en" ? english.OrgDataLifecycle : japanese.OrgDataLifecycle;
        const label = copy.exportPermissionLabel.replace("{role}", copy.roleAdmin);
        const toggle = screen.getByRole("switch", { name: label });
        await waitFor(() => expect(toggle.hasAttribute("disabled")).toBe(false));
        api.get.mockReturnValueOnce(pending.promise);
        fireEvent.focus(window);
        await waitFor(() => expect(api.get).toHaveBeenCalledTimes(2));
        fireEvent.click(toggle);
        await waitFor(() => expect(toggle.getAttribute("aria-checked")).toBe("false"));
        await waitFor(() => expect(toggle.hasAttribute("disabled")).toBe(false));

        await act(async () => {
            if (outcome === "resolve") pending.resolve({ owner: true, admin: true, member: false });
            else pending.reject(new Error("Earlier refresh failed"));
        });

        expect(toggle.getAttribute("aria-checked")).toBe("false");
        expect(toggle.hasAttribute("disabled")).toBe(false);
        expect(screen.getByRole("button", { name: copy.exportWorkspace }).hasAttribute("disabled")).toBe(true);
        expect(screen.getByText(copy.exportPermissionDenied)).toBeTruthy();
    });
});

it("does not apply a pending policy update to another organization", async () => {
    const pending = Promise.withResolvers<{ owner: boolean; admin: boolean; member: boolean }>();
    api.update.mockReturnValueOnce(pending.promise);
    const rendered = render(view("en"));
    const label = english.OrgDataLifecycle.exportPermissionLabel.replace("{role}", english.OrgDataLifecycle.roleAdmin);
    const toggle = screen.getByRole("switch", { name: label });
    await waitFor(() => expect(toggle.hasAttribute("disabled")).toBe(false));
    fireEvent.click(toggle);
    rendered.rerender(view("en", 8));
    await waitFor(() => expect(screen.getByRole("button", { name: english.OrgDataLifecycle.exportWorkspace }).hasAttribute("disabled")).toBe(false));
    await act(async () => pending.resolve({ owner: true, admin: false, member: false }));
    expect(screen.getByRole("button", { name: english.OrgDataLifecycle.exportWorkspace }).hasAttribute("disabled")).toBe(false);
    expect(screen.getByRole("switch", { name: label }).getAttribute("aria-checked")).toBe("true");
});
