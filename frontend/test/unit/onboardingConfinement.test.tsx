// @vitest-environment jsdom
import { act, createElement, type ReactNode } from "react";
import { createRoot, type Root } from "react-dom/client";
import { NextIntlClientProvider } from "next-intl";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import OnboardingConfinement from "@/app/onboarding/OnboardingConfinement";
import type { PrivilegedMfaStandingState } from "@/app/hooks/usePrivilegedMfaStanding";
import type { PasskeyRegistrationRequirements } from "@/app/lib/types";
import enAccount from "@/messages/en/account.json";
import enWorkspace from "@/messages/en/workspace.json";

const mocks = vi.hoisted(() => ({
    getPasskeys: vi.fn(async () => [] as unknown[]),
    leaveOrganization: vi.fn(async () => undefined),
    requestConfirmation: vi.fn(async () => ({ message: "sent" })),
    beginRegistration: vi.fn(async () => ({ challenge: "challenge" })),
    finishRegistration: vi.fn(async () => undefined),
    startRegistration: vi.fn(async () => ({ id: "credential" })),
    approval: { open: false, onApproved: null as ((orgId: number) => void) | null },
}));

vi.mock("@/app/lib/api", async (importOriginal) => ({
    ...await importOriginal<typeof import("@/app/lib/api")>(),
    getPasskeys: mocks.getPasskeys,
    leaveOrganization: mocks.leaveOrganization,
    requestPasskeyBootstrapConfirmation: mocks.requestConfirmation,
    beginPasskeyRegistration: mocks.beginRegistration,
    finishPasskeyRegistration: mocks.finishRegistration,
}));
vi.mock("@simplewebauthn/browser", async (importOriginal) => ({
    ...await importOriginal<typeof import("@simplewebauthn/browser")>(),
    startRegistration: mocks.startRegistration,
}));
vi.mock("@/app/hooks/usePasskeySupport", () => ({ usePasskeySupport: () => true }));
vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh: vi.fn(), replace: vi.fn() }) }));
vi.mock("@/app/hooks/usePasskeyStepUpError", () => ({ usePasskeyStepUpErrorHandler: () => () => false }));
vi.mock("@/app/lib/toast", () => ({ toastError: vi.fn(), toastInfo: vi.fn(), toastSuccess: vi.fn() }));
vi.mock("@/app/components/account/PasskeyApprovalDialog", () => ({
    default: ({ open, onApproved }: { open: boolean; onApproved: (orgId: number) => void }) => {
        mocks.approval.open = open;
        mocks.approval.onApproved = onApproved;
        return null;
    },
}));
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

function standingFor(
    standing: PasskeyRegistrationRequirements["standing"],
    overrides: Partial<PasskeyRegistrationRequirements> = {},
): PrivilegedMfaStandingState {
    return {
        requirements: {
            currentPasswordRequired: false,
            emailConfirmationRequired: false,
            emailConfirmationSatisfied: false,
            standing,
            approvalGaps: [],
            privilegedMemberships: [{ orgId: 3, orgName: "Acme", orgRole: "admin", workspaces: [] }],
            ...overrides,
        },
        status: "ready",
        fresh: true,
        confined: true,
        reason: standing === "ATTESTATION_REQUIRED" ? "attestation" : "enrollment",
        refresh: vi.fn(async () => "applied" as const),
    };
}

async function renderConfinement(standing: PrivilegedMfaStandingState) {
    await act(async () => root.render(
        <NextIntlClientProvider locale="en" messages={{ ...enAccount, ...enWorkspace }} timeZone="UTC">
            {createElement(OnboardingConfinement, { standing })}
        </NextIntlClientProvider>,
    ));
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
    mocks.approval.open = false;
    mocks.approval.onApproved = null;
});

afterEach(async () => {
    await act(async () => root.unmount());
    container.remove();
    vi.clearAllMocks();
});

describe("confinement on onboarding, for an account with no workspace", () => {
    it("asks for the emailed confirmation before a privileged account's first passkey", async () => {
        await renderConfinement(standingFor("ENROLLMENT_REQUIRED", { emailConfirmationRequired: true }));

        expect(container.textContent).toContain(enAccount.AccountSecurity.confirmationDescription);
        await act(async () => button(enAccount.AccountSecurity.confirmationSend).click());

        expect(mocks.requestConfirmation).toHaveBeenCalledOnce();
    });

    it("enrolls a passkey and re-reads the standing as a ceremony", async () => {
        const standing = standingFor("ENROLLMENT_REQUIRED");
        await renderConfinement(standing);

        await act(async () => button(enAccount.AccountSecurity.add).click());

        expect(mocks.finishRegistration).toHaveBeenCalledOnce();
        expect(standing.refresh).toHaveBeenCalledWith("ceremony");
    });

    it("asks a password-backed account for its current password before the first passkey", async () => {
        await renderConfinement(standingFor("ENROLLMENT_REQUIRED", { currentPasswordRequired: true }));

        await act(async () => button(enAccount.AccountSecurity.add).click());

        const dialog = container.querySelector("[role='dialog']");
        expect(dialog?.textContent).toContain(enAccount.AccountSecurity.passwordTitle);
        expect(mocks.beginRegistration).not.toHaveBeenCalled();
    });

    it("withholds code entry until the passkeys are read, and offers a retry when they cannot be", async () => {
        mocks.getPasskeys.mockRejectedValueOnce(new Error("offline"));
        await renderConfinement(standingFor("ATTESTATION_REQUIRED"));

        expect(container.textContent).toContain(enAccount.AccountSecurity.loadFailed);
        expect([...container.querySelectorAll("button")].some(
            (candidate) => candidate.textContent === enAccount.AccountSecurity.approvalEnter,
        )).toBe(false);

        await act(async () => button(enAccount.AccountSecurity.retry).click());

        expect(button(enAccount.AccountSecurity.approvalEnter).disabled).toBe(false);
    });

    it("redeems an approval code and re-reads the standing as a ceremony", async () => {
        const standing = standingFor("ATTESTATION_REQUIRED");
        await renderConfinement(standing);

        await act(async () => button(enAccount.AccountSecurity.approvalEnter).click());
        expect(mocks.approval.open).toBe(true);
        await act(async () => mocks.approval.onApproved?.(3));

        expect(standing.refresh).toHaveBeenCalledWith("ceremony");
    });

    it("lets the account leave the organization that confines it", async () => {
        await renderConfinement(standingFor("ENROLLMENT_REQUIRED"));

        await act(async () => button("Leave Acme").click());
        await act(async () => button(enAccount.AccountSecurity.exitConfirm).click());

        expect(mocks.leaveOrganization).toHaveBeenCalledWith(3);
    });

    it("checks the standing again on request", async () => {
        const standing = standingFor("ATTESTATION_REQUIRED");
        await renderConfinement(standing);

        await act(async () => button(enWorkspace.Onboarding.confinedRecheck).click());

        expect(standing.refresh).toHaveBeenCalledWith("check");
    });
});
