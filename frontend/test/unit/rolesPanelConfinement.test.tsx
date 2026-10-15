// @vitest-environment jsdom
import { act, createElement, type ReactNode } from "react";
import { createRoot, type Root } from "react-dom/client";
import { NextIntlClientProvider } from "next-intl";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import RolesPanel from "@/app/components/settings/RolesPanel";
import { ApiError, PRIVILEGED_MFA_CONFINEMENT_ACKNOWLEDGEMENT_REQUIRED_CODE } from "@/app/lib/api";
import enSettings from "@/messages/en/settings.json";
import enWorkspace from "@/messages/en/workspace.json";

const spies = vi.hoisted(() => ({
    updateWorkspaceRole: vi.fn<(...args: unknown[]) => Promise<unknown>>(),
    showApiError: vi.fn(),
    toastSuccess: vi.fn(),
    refresh: vi.fn(),
    submissions: [] as string[],
}));

vi.mock("@/app/lib/api", async (importOriginal) => ({
    ...await importOriginal<typeof import("@/app/lib/api")>(),
    getWorkspaceRoles: vi.fn(async () => [{ id: 4, name: "Ops", permissions: ["MEMBER_MANAGE"] }]),
    getBuiltInRoles: vi.fn(async () => []),
    getPermissionCatalog: vi.fn(async () => ["MEMBER_MANAGE"]),
    updateWorkspaceRole: spies.updateWorkspaceRole,
}));
vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh: spies.refresh }) }));
vi.mock("@/app/hooks/useWorkspace", () => ({ useWorkspace: () => ({ activeWorkspaceId: 5 }) }));
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
vi.mock("@/app/components/settings/RoleDialog", async () => {
    const React = await import("react");
    return {
        default: ({ open, onSubmit }: { open: boolean; onSubmit: (name: string, permissions: string[]) => Promise<void> }) =>
            open
                ? React.createElement("button", {
                    type: "button",
                    "data-testid": "submit-role",
                    onClick: () => {
                        onSubmit("Ops", ["MEMBER_MANAGE", "ROLE_MANAGE"]).then(
                            () => spies.submissions.push("saved"),
                            (err: unknown) => spies.submissions.push(err instanceof Error ? err.name : "rejected"),
                        );
                    },
                }, "Submit role")
                : null,
    };
});

let container: HTMLDivElement;
let root: Root;

function button(label: string): HTMLButtonElement {
    const match = [...container.querySelectorAll("button")].find((candidate) => candidate.textContent === label);
    if (!match) throw new Error(`no button labelled ${label}`);
    return match;
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
    await act(async () => container.querySelector<HTMLButtonElement>("[data-testid='submit-role']")?.click());
    await flush();
}

beforeEach(() => {
    container = document.createElement("div");
    document.body.appendChild(container);
    root = createRoot(container);
    spies.submissions.length = 0;
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
});

describe("editing a custom role that would confine its assignees", () => {
    it("names the role in the question and keeps the role dialog open, without an error, when declined", async () => {
        await openEditAndSubmit();

        expect(container.textContent).toContain("Save changes to the Ops role");
        await act(async () => button(enSettings.ConfinementAcknowledgement.cancel).click());
        await flush();

        expect(spies.submissions).toEqual(["ConfinementAcknowledgementDeclined"]);
        expect(container.querySelector("[data-testid='submit-role']")).not.toBeNull();
        expect(spies.showApiError).not.toHaveBeenCalled();
        expect(spies.toastSuccess).not.toHaveBeenCalled();
        expect(spies.updateWorkspaceRole).toHaveBeenCalledTimes(1);
    });

    it("saves the role acknowledged once the actor goes ahead", async () => {
        await openEditAndSubmit();

        await act(async () => button(enSettings.ConfinementAcknowledgement.confirm).click());
        await flush();

        expect(spies.updateWorkspaceRole).toHaveBeenLastCalledWith(5, 4, "Ops", ["MEMBER_MANAGE", "ROLE_MANAGE"], true);
        expect(spies.submissions).toEqual(["saved"]);
        expect(spies.toastSuccess).toHaveBeenCalledWith(enWorkspace.WorkspaceRoles.updated);
        expect(spies.refresh).toHaveBeenCalled();
    });
});
