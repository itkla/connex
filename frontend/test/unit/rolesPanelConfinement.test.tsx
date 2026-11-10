// @vitest-environment jsdom
import { act, createElement, type ReactNode } from "react";
import { createRoot, type Root } from "react-dom/client";
import { NextIntlClientProvider } from "next-intl";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import RolesPanel from "@/app/components/settings/RolesPanel";
import { ApiError, getWorkspaceRoles, PRIVILEGED_MFA_CONFINEMENT_ACKNOWLEDGEMENT_REQUIRED_CODE } from "@/app/lib/api";
import enSettings from "@/messages/en/settings.json";
import enWorkspace from "@/messages/en/workspace.json";
import jaWorkspace from "@/messages/ja/workspace.json";

const spies = vi.hoisted(() => ({
    updateWorkspaceRole: vi.fn<(...args: unknown[]) => Promise<unknown>>(),
    showApiError: vi.fn(),
    toastSuccess: vi.fn(),
    refresh: vi.fn(),
    canManageRoles: true,
    workspaceId: 5,
    updateBuiltInExport: vi.fn(),
}));

vi.mock("@/app/lib/api", async (importOriginal) => ({
    ...await importOriginal<typeof import("@/app/lib/api")>(),
    getWorkspaceRoles: vi.fn(async () => [{ id: 4, name: "Ops", permissions: ["MEMBER_MANAGE"] }]),
    getBuiltInRoles: vi.fn(async () => [
        { id: 0, name: "member", permissions: ["MEMBER_MANAGE", "EXPORT_DATA"], exportPermissionEditable: true },
    ]),
    getPermissionCatalog: vi.fn(async () => ["MEMBER_MANAGE", "ROLE_MANAGE", "EXPORT_DATA"]),
    updateBuiltInRoleExportPermission: spies.updateBuiltInExport,
    updateWorkspaceRole: spies.updateWorkspaceRole,
}));
vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh: spies.refresh }) }));
vi.mock("@/app/hooks/usePermissions", () => ({ usePermission: () => spies.canManageRoles }));
vi.mock("@/app/hooks/useWorkspace", () => ({ useWorkspace: () => ({ activeWorkspaceId: spies.workspaceId }) }));
vi.mock("@/app/hooks/usePasskeyStepUpError", () => ({ usePasskeyStepUpErrorHandler: () => () => false }));
vi.mock("@/app/hooks/useApiErrorToast", () => ({ useApiErrorToast: () => spies.showApiError }));
vi.mock("@/app/lib/toast", () => ({ toastSuccess: spies.toastSuccess, toastError: vi.fn() }));
vi.mock("@/app/components/motion/Rise", () => ({
    default: ({ children }: { children?: ReactNode }) => createElement("div", null, children),
}));
vi.mock("@/app/components/settings/SettingsSection", () => ({
    SettingsSection: ({ title, action }: { title: ReactNode; action?: ReactNode }) =>
        createElement("div", null, title, action),
}));
vi.mock("@/app/components/records/DeleteRecordDialog", () => ({ default: () => null }));
vi.mock("@/components/ui/dropdown-menu", async () => {
    const React = await import("react");
    const Passthrough = ({ children }: { children?: ReactNode }) => React.createElement(React.Fragment, null, children);
    return {
        DropdownMenu: Passthrough,
        DropdownMenuTrigger: () => null,
        DropdownMenuContent: Passthrough,
        DropdownMenuItem: ({ children, onSelect }: { children?: ReactNode; onSelect?: () => void }) =>
            React.createElement("button", { type: "button", onClick: onSelect }, children),
    };
});
vi.mock("@/components/ui/dialog", async () => {
    const React = await import("react");
    const Passthrough = ({ children }: { children?: ReactNode }) => React.createElement(React.Fragment, null, children);
    return {
        Dialog: ({ open, children }: { open: boolean; children?: ReactNode }) =>
            open ? React.createElement("div", { role: "dialog" }, children) : null,
        DialogClose: Passthrough,
        DialogContent: Passthrough,
        DialogDescription: Passthrough,
        DialogFooter: Passthrough,
        DialogHeader: Passthrough,
        DialogTitle: Passthrough,
    };
});
let container: HTMLDivElement;
let root: Root;

function button(label: string, scope: ParentNode = container): HTMLButtonElement {
    const match = [...scope.querySelectorAll("button")].find((candidate) => candidate.textContent === label);
    if (!match) throw new Error(`no button labelled ${label}`);
    return match;
}

function confinementQuestion(): Element {
    const question = [...container.querySelectorAll("[role='dialog']")]
        .find((dialog) => dialog.textContent?.includes(enSettings.ConfinementAcknowledgement.title));
    if (!question) throw new Error("no confinement question");
    return question;
}

function roleSaveButton(): HTMLButtonElement | undefined {
    return [...container.querySelectorAll<HTMLButtonElement>("button[type='submit']")].at(0);
}

async function flush() {
    for (let i = 0; i < 10; i += 1) await act(async () => Promise.resolve());
}

async function openEditAndSubmit() {
    await act(async () => root.render(
        <NextIntlClientProvider locale="en" messages={{ ...enWorkspace, ...enSettings }}>
            <RolesPanel />
        </NextIntlClientProvider>,
    ));
    await flush();
    await act(async () => button(enWorkspace.WorkspaceRoles.edit).click());
    await act(async () => roleSaveButton()?.click());
    await flush();
}

beforeEach(() => {
    spies.canManageRoles = true;
    spies.workspaceId = 5;
    spies.updateBuiltInExport.mockResolvedValue([{ id: 0, name: "member", permissions: ["MEMBER_MANAGE"], exportPermissionEditable: true }]);
    vi.stubGlobal("matchMedia", (query: string) => ({
        matches: false,
        media: query,
        onchange: null,
        addEventListener: () => undefined,
        removeEventListener: () => undefined,
        addListener: () => undefined,
        removeListener: () => undefined,
        dispatchEvent: () => false,
    }));
    vi.stubGlobal("ResizeObserver", class {
        observe() {}
        unobserve() {}
        disconnect() {}
    });
    container = document.createElement("div");
    document.body.appendChild(container);
    root = createRoot(container);
    spies.updateWorkspaceRole.mockReset();
    spies.updateWorkspaceRole.mockImplementation(async (...args: unknown[]) => {
        if (args[4] !== true) {
            const refusal = new ApiError("confinement", 409, PRIVILEGED_MFA_CONFINEMENT_ACKNOWLEDGEMENT_REQUIRED_CODE);
            refusal.affectedCount = 2;
            throw refusal;
        }
        return { id: 4, name: "Ops", permissions: ["MEMBER_MANAGE", "ROLE_MANAGE"] };
    });
});

afterEach(async () => {
    await act(async () => root.unmount());
    container.remove();
    vi.clearAllMocks();
    vi.unstubAllGlobals();
});

describe("editing a custom role that would confine its assignees", () => {
    it("names the role in the question and keeps the role dialog open, without an error, when declined", async () => {
        await openEditAndSubmit();

        expect(roleSaveButton()?.disabled).toBe(true);
        expect(confinementQuestion().textContent).toContain("Save changes to the Ops role");
        await act(async () => button(enSettings.ConfinementAcknowledgement.cancel, confinementQuestion()).click());
        await flush();

        expect(container.querySelector("#role-name")).not.toBeNull();
        expect(roleSaveButton()?.disabled).toBe(false);
        expect(roleSaveButton()?.textContent).toBe(enWorkspace.WorkspaceRoles.save);
        expect(spies.showApiError).not.toHaveBeenCalled();
        expect(spies.toastSuccess).not.toHaveBeenCalled();
        expect(spies.updateWorkspaceRole).toHaveBeenCalledTimes(1);
    });

    it("saves the role acknowledged once the actor goes ahead", async () => {
        await openEditAndSubmit();

        await act(async () => button(enSettings.ConfinementAcknowledgement.confirm, confinementQuestion()).click());
        await flush();

        expect(spies.updateWorkspaceRole).toHaveBeenLastCalledWith(5, 4, "Ops", ["MEMBER_MANAGE"], true);
        expect(spies.toastSuccess).toHaveBeenCalledWith(enWorkspace.WorkspaceRoles.updated);
        expect(spies.refresh).toHaveBeenCalled();
    });
});

describe.each(["en", "ja"] as const)("localized role explanations in %s", (locale) => {
    it("names built-in roles, shows denied actions, and labels editable permissions", async () => {
        const messages = locale === "en" ? enWorkspace : jaWorkspace;
        const copy = messages.WorkspaceRoles;
        await act(async () => root.render(
            <NextIntlClientProvider locale={locale} messages={{ ...messages, ...enSettings }}>
                <RolesPanel />
            </NextIntlClientProvider>,
        ));
        await flush();
        expect(container.textContent).toContain(copy.builtInRoles.member.name);
        expect(container.textContent).toContain(copy.builtInRoles.member.description);
        expect(container.querySelector("details")?.textContent)
            .toContain(`${copy.permissionLabels.ROLE_MANAGE.action} · ${copy.notAllowed}`);
        expect(container.textContent).not.toContain("Member Manage");
        await act(async () => button(copy.newRole).click());
        const checkbox = container.querySelector<HTMLInputElement>('input[aria-labelledby="role-action-ROLE_MANAGE"]');
        expect(checkbox).not.toBeNull();
        expect(document.getElementById(checkbox?.getAttribute("aria-describedby") ?? "")?.textContent)
            .toBe(copy.permissionLabels.ROLE_MANAGE.description);
        expect(container.querySelector<HTMLInputElement>('input[aria-labelledby="role-action-EXPORT_DATA"]')?.checked).toBe(false);
        await act(async () => checkbox?.click());
        expect(checkbox?.checked).toBe(true);
        expect(spies.updateWorkspaceRole).not.toHaveBeenCalled();
    });
});


describe("built-in export permission controls", () => {
    it("allows an administrator to change export access without custom-role management", async () => {
        spies.canManageRoles = false;
        await act(async () => root.render(
            <NextIntlClientProvider locale="en" messages={{ ...enWorkspace, ...enSettings }}>
                <RolesPanel />
            </NextIntlClientProvider>,
        ));
        await flush();
        expect(getWorkspaceRoles).not.toHaveBeenCalled();
        expect(container.textContent).not.toContain(enWorkspace.WorkspaceRoles.newRole);
        const toggle = container.querySelector<HTMLButtonElement>("#built-in-export-member");
        expect(toggle?.getAttribute("aria-checked")).toBe("true");
        await act(async () => toggle?.click());
        await flush();
        expect(spies.updateBuiltInExport).toHaveBeenCalledWith(5, "member", false);
        expect(container.querySelector("#built-in-export-member")?.getAttribute("aria-checked")).toBe("false");
    });
});


describe("pending built-in export policy updates", () => {
    it("does not replace another workspace's policy after switching", async () => {
        const pending = Promise.withResolvers<Array<{ id: number; name: string; permissions: string[]; exportPermissionEditable: boolean }>>();
        spies.updateBuiltInExport.mockReturnValueOnce(pending.promise);
        const view = () => (
            <NextIntlClientProvider locale="en" messages={{ ...enWorkspace, ...enSettings }}>
                <RolesPanel />
            </NextIntlClientProvider>
        );
        await act(async () => root.render(view()));
        await flush();
        await act(async () => container.querySelector<HTMLButtonElement>("#built-in-export-member")?.click());
        expect(spies.updateBuiltInExport).toHaveBeenCalledWith(5, "member", false);
        spies.workspaceId = 9;
        await act(async () => root.render(view()));
        await flush();
        expect(container.querySelector("#built-in-export-member")?.getAttribute("aria-checked")).toBe("true");
        await act(async () => pending.resolve([{ id: 0, name: "member", permissions: [], exportPermissionEditable: true }]));
        await flush();
        expect(container.querySelector("#built-in-export-member")?.getAttribute("aria-checked")).toBe("true");
        expect(spies.refresh).not.toHaveBeenCalled();
        expect(spies.toastSuccess).not.toHaveBeenCalled();
    });
});
