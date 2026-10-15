// @vitest-environment jsdom
import { act, createElement, type ReactNode } from "react";
import { createRoot, type Root } from "react-dom/client";
import { NextIntlClientProvider } from "next-intl";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import PrivilegedMfaConfinementCard from "@/app/components/account/PrivilegedMfaConfinementCard";
import type { ConfinementExits } from "@/app/hooks/useConfinementExits";
import type { PasskeyRegistrationRequirements } from "@/app/lib/types";
import enAccount from "@/messages/en/account.json";
import jaAccount from "@/messages/ja/account.json";

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

const requirements: PasskeyRegistrationRequirements = {
    currentPasswordRequired: false,
    emailConfirmationRequired: false,
    emailConfirmationSatisfied: false,
    standing: "ATTESTATION_REQUIRED",
    approvalGaps: [{ credentialId: "cred-laptop", orgId: 3, orgName: "Acme" }],
    privilegedMemberships: [
        { orgId: 3, orgName: "Acme", orgRole: "owner", workspaces: [{ workspaceId: 7, workspaceName: "Sales" }] },
        { orgId: 4, orgName: "Beta", orgRole: null, workspaces: [{ workspaceId: 9, workspaceName: "Ops" }] },
    ],
};

let container: HTMLDivElement;
let root: Root;

function exitsWith(overrides: Partial<ConfinementExits> = {}): ConfinementExits {
    return { leavingKey: null, refusals: {}, leave: vi.fn(async () => true), ...overrides };
}

async function renderCard(
    exits: ConfinementExits,
    options: { canLeaveWorkspaces?: boolean; locale?: string; messages?: Record<string, unknown> } = {},
) {
    await act(async () => root.render(
        <NextIntlClientProvider locale={options.locale ?? "en"} messages={options.messages ?? enAccount} timeZone="UTC">
            <PrivilegedMfaConfinementCard
                reason="attestation"
                requirements={requirements}
                passkeys={[{
                    credentialId: "cred-laptop",
                    label: "Laptop",
                    transports: [],
                    createdAt: "2026-09-01T00:00:00Z",
                    lastUsedAt: null,
                }]}
                action={createElement("button", { type: "button" }, "Enter code")}
                exits={exits}
                canLeaveWorkspaces={options.canLeaveWorkspaces ?? true}
            />
        </NextIntlClientProvider>,
    ));
}

function buttonTexts(): string[] {
    return [...container.querySelectorAll("button")].map((button) => button.textContent ?? "");
}

function button(label: string): HTMLButtonElement {
    const match = [...container.querySelectorAll("button")].find((candidate) => candidate.textContent === label);
    if (!match) throw new Error(`no button labelled ${label}`);
    return match;
}

beforeEach(() => {
    container = document.createElement("div");
    document.body.appendChild(container);
    root = createRoot(container);
});

afterEach(async () => {
    await act(async () => root.unmount());
    container.remove();
});

describe("the confinement card", () => {
    it("names the passkey to approve and offers the surface's action", async () => {
        await renderCard(exitsWith());

        expect(container.textContent).toContain(enAccount.AccountSecurity.approvalNeededTitle);
        expect(container.textContent).toContain("Approve Laptop. It still needs approval in Acme.");
        expect(buttonTexts()).toContain("Enter code");
    });

    it("offers every membership that makes the account privileged as an exit", async () => {
        await renderCard(exitsWith());

        expect(buttonTexts()).toEqual(expect.arrayContaining(["Leave Acme", "Leave Sales", "Leave Ops"]));
        expect(buttonTexts().filter((text) => text === "Leave Beta")).toHaveLength(0);
    });

    it("offers only organizations where the surface cannot leave a workspace", async () => {
        await renderCard(exitsWith(), { canLeaveWorkspaces: false });

        expect(buttonTexts()).toContain("Leave Acme");
        expect(buttonTexts()).not.toContain("Leave Sales");
    });

    it("asks before leaving, and leaves only once confirmed", async () => {
        const exits = exitsWith();
        await renderCard(exits);

        await act(async () => button("Leave Acme").click());
        const dialog = container.querySelector("[role='dialog']");
        expect(dialog?.textContent).toContain("Leave Acme?");
        expect(dialog?.textContent).toContain(enAccount.AccountSecurity.exitConfirmOrganization);
        expect(exits.leave).not.toHaveBeenCalled();

        await act(async () => button(enAccount.AccountSecurity.exitConfirm).click());

        expect(exits.leave).toHaveBeenCalledWith({ kind: "organization", orgId: 3, name: "Acme" });
        expect(container.querySelector("[role='dialog']")).toBeNull();
    });

    it("warns about the assignment and ownership changes before leaving a workspace", async () => {
        await renderCard(exitsWith());

        await act(async () => button("Leave Sales").click());

        expect(container.querySelector("[role='dialog']")?.textContent)
            .toContain(enAccount.AccountInvites.leaveConfirmBody);
    });

    it("explains a refused exit beside it in the reader's language", async () => {
        await renderCard(exitsWith({ refusals: { "organization:3": "ORG_LAST_OWNER", "workspace:7": "OTHER" } }));

        const alerts = [...container.querySelectorAll("[role='alert']")].map((alert) => alert.textContent);
        expect(alerts).toEqual([
            enAccount.AccountSecurity.exitRefusedOrgLastOwner,
            enAccount.AccountSecurity.exitRefused,
        ]);
    });

    it("explains the exits in Japanese", async () => {
        await renderCard(exitsWith({ refusals: { "workspace:7": "WORKSPACE_LAST_OWNER" } }), {
            locale: "ja",
            messages: jaAccount,
        });

        expect(container.textContent).toContain(jaAccount.AccountSecurity.exitsTitle);
        expect(buttonTexts()).toContain("Acme から退出");
        expect(container.textContent).toContain(jaAccount.AccountSecurity.exitRefusedWorkspaceLastOwner);
    });

    it("keeps every exit disabled while one is being taken", async () => {
        await renderCard(exitsWith({ leavingKey: "organization:3" }));

        const exitButtons = [...container.querySelectorAll<HTMLButtonElement>("button")]
            .filter((candidate) => candidate.textContent?.startsWith("Leave"));
        expect(exitButtons.every((candidate) => candidate.disabled)).toBe(true);
    });
});
