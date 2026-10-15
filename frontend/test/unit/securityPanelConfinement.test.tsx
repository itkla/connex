// @vitest-environment jsdom
import { act, createElement, type ReactNode } from "react";
import { createRoot, type Root } from "react-dom/client";
import { NextIntlClientProvider } from "next-intl";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import SecurityPanel from "@/app/components/account/SecurityPanel";
import type { Passkey, PasskeyRegistrationRequirements } from "@/app/lib/types";
import enAccount from "@/messages/en/account.json";

const backend = vi.hoisted(() => ({
    passkeys: [] as Passkey[],
    requirements: null as PasskeyRegistrationRequirements | null,
    refresh: vi.fn(async () => "applied" as const),
    getPasskeys: vi.fn(),
    beginPasskeyRegistration: vi.fn(async () => ({ challenge: "challenge" })),
    finishPasskeyRegistration: vi.fn(async () => undefined),
    startRegistration: vi.fn(async () => ({ id: "credential" })),
    approve: null as ((orgId: number) => void) | null,
}));

vi.mock("@/app/lib/api", async (importOriginal) => ({
    ...await importOriginal<typeof import("@/app/lib/api")>(),
    getPasskeys: backend.getPasskeys,
    beginPasskeyRegistration: backend.beginPasskeyRegistration,
    finishPasskeyRegistration: backend.finishPasskeyRegistration,
}));
vi.mock("@simplewebauthn/browser", async (importOriginal) => ({
    ...await importOriginal<typeof import("@simplewebauthn/browser")>(),
    startRegistration: backend.startRegistration,
}));
vi.mock("@/app/hooks/usePrivilegedMfaStanding", () => ({
    usePrivilegedMfaStanding: () => ({
        requirements: backend.requirements,
        status: backend.requirements ? "ready" : "loading",
        confined: backend.requirements?.standing === "ATTESTATION_REQUIRED"
            || backend.requirements?.standing === "ENROLLMENT_REQUIRED",
        refresh: backend.refresh,
    }),
}));
vi.mock("@/app/hooks/usePasskeySupport", () => ({ usePasskeySupport: () => true }));
vi.mock("@/app/hooks/usePasskeyStepUpError", () => ({ usePasskeyStepUpErrorHandler: () => () => false }));
vi.mock("@/app/hooks/useWorkspace", () => ({ useWorkspace: () => ({ workspaces: [] }) }));
vi.mock("@/app/hooks/useNow", () => ({ useLiveNow: () => Date.parse("2026-10-14T00:00:00Z") }));
vi.mock("@/app/components/motion/Rise", () => ({
    default: ({ children, className }: { children?: ReactNode; className?: string }) =>
        createElement("div", { className }, children),
}));
vi.mock("@/app/components/dashboard/SectionHeader", () => ({
    default: ({ title, action }: { title: ReactNode; action?: ReactNode }) =>
        createElement("div", null, createElement("h2", null, title), action),
}));
vi.mock("@/app/components/account/PasskeyApprovalDialog", () => ({
    default: ({ onApproved }: { onApproved: (orgId: number) => void }) => {
        backend.approve = onApproved;
        return null;
    },
}));
vi.mock("@/app/components/records/DeleteRecordDialog", () => ({ default: () => null }));
vi.mock("@/components/ui/dialog", async () => {
    const React = await import("react");
    const Passthrough = ({ children }: { children?: ReactNode }) => React.createElement(React.Fragment, null, children);
    return {
        Dialog: ({ open, children }: { open: boolean; children?: ReactNode }) =>
            open ? React.createElement(React.Fragment, null, children) : null,
        DialogClose: Passthrough,
        DialogContent: Passthrough,
        DialogDescription: Passthrough,
        DialogFooter: Passthrough,
        DialogHeader: Passthrough,
        DialogTitle: Passthrough,
    };
});
vi.mock("@/components/ui/dropdown-menu", async () => {
    const React = await import("react");
    const Passthrough = ({ children }: { children?: ReactNode }) => React.createElement(React.Fragment, null, children);
    return {
        DropdownMenu: Passthrough,
        DropdownMenuContent: () => null,
        DropdownMenuItem: Passthrough,
        DropdownMenuTrigger: Passthrough,
    };
});

function passkey(credentialId: string, label: string, lastUsedAt: string | null): Passkey {
    return { credentialId, label, transports: ["internal"], createdAt: "2026-09-01T00:00:00Z", lastUsedAt };
}

function requirementsFor(
    standing: PasskeyRegistrationRequirements["standing"],
    approvalGaps: PasskeyRegistrationRequirements["approvalGaps"] = [],
): PasskeyRegistrationRequirements {
    return {
        currentPasswordRequired: false,
        emailConfirmationRequired: false,
        emailConfirmationSatisfied: false,
        standing,
        approvalGaps,
    };
}

let container: HTMLDivElement;
let root: Root;

async function renderPanel() {
    await act(async () => root.render(
        <NextIntlClientProvider locale="en" messages={enAccount} timeZone="UTC">
            <SecurityPanel />
        </NextIntlClientProvider>,
    ));
}

beforeEach(() => {
    container = document.createElement("div");
    document.body.appendChild(container);
    root = createRoot(container);
});

beforeEach(() => {
    backend.getPasskeys.mockImplementation(async () => backend.passkeys);
});

afterEach(async () => {
    await act(async () => root.unmount());
    container.remove();
    backend.passkeys = [];
    backend.requirements = null;
    backend.approve = null;
    vi.clearAllMocks();
});

describe("the Security page while a privileged account is confined", () => {
    it("puts the approval step first and names the passkey every code should approve", async () => {
        backend.passkeys = [passkey("cred-laptop", "Laptop", "2026-10-10T00:00:00Z"), passkey("cred-phone", "Phone", null)];
        backend.requirements = requirementsFor("ATTESTATION_REQUIRED", [
            { credentialId: "cred-laptop", orgId: 2, orgName: "Beta" },
            { credentialId: "cred-phone", orgId: 1, orgName: "Acme" },
            { credentialId: "cred-phone", orgId: 2, orgName: "Beta" },
        ]);
        await renderPanel();

        const card = container.querySelector("section[aria-labelledby='passkey-approval-needed-title']");
        expect(card).not.toBeNull();
        expect(card?.textContent).toContain("Approve Laptop. It still needs approval in Beta.");
        expect(card?.textContent).toContain("Phone: needs approval in Acme and Beta");
        expect(card?.textContent).toContain("when you enter a code, confirm with Laptop.");
        expect(container.textContent).not.toContain(enAccount.AccountSecurity.approvalTitle);
        expect(container.textContent?.indexOf(enAccount.AccountSecurity.approvalNeededTitle))
            .toBeLessThan(container.textContent?.indexOf("Laptop: needs approval") ?? -1);
    });

    it("keeps a single passkey's guidance to one line", async () => {
        backend.passkeys = [passkey("cred-laptop", "Laptop", null)];
        backend.requirements = requirementsFor("ATTESTATION_REQUIRED", [
            { credentialId: "cred-laptop", orgId: 1, orgName: "Acme" },
        ]);
        await renderPanel();

        const card = container.querySelector("section[aria-labelledby='passkey-approval-needed-title']");
        expect(card?.textContent).toContain("Approve Laptop. It still needs approval in Acme.");
        expect(card?.querySelector("ul")).toBeNull();
    });

    it("explains an enrollment confinement without a second add button", async () => {
        backend.requirements = requirementsFor("ENROLLMENT_REQUIRED");
        await renderPanel();

        expect(container.textContent).toContain(enAccount.AccountSecurity.enrollmentNeededTitle);
        expect(container.querySelector("section[aria-labelledby='passkey-approval-needed-title']")).toBeNull();
    });

    it("withdraws passkey management while approval is pending, since only redemption can succeed", async () => {
        backend.passkeys = [passkey("cred-laptop", "Laptop", null)];
        backend.requirements = requirementsFor("ATTESTATION_REQUIRED", [
            { credentialId: "cred-laptop", orgId: 1, orgName: "Acme" },
        ]);
        await renderPanel();

        expect(container.textContent).not.toContain(enAccount.AccountSecurity.add);
        expect(container.querySelector(`[aria-label="${enAccount.AccountSecurity.passkeyActions.replace("{label}", "Laptop")}"]`))
            .toBeNull();
    });

    it("re-reads the standing as a ceremony once a code is redeemed", async () => {
        backend.passkeys = [passkey("cred-laptop", "Laptop", null)];
        backend.requirements = requirementsFor("ATTESTATION_REQUIRED", [
            { credentialId: "cred-laptop", orgId: 1, orgName: "Acme" },
        ]);
        await renderPanel();
        backend.getPasskeys.mockClear();

        await act(async () => backend.approve?.(1));

        expect(backend.refresh).toHaveBeenCalledWith("ceremony");
        expect(backend.getPasskeys).toHaveBeenCalledOnce();
    });

    it("re-reads the standing as a ceremony once a first passkey is enrolled", async () => {
        backend.requirements = requirementsFor("ENROLLMENT_REQUIRED");
        await renderPanel();
        backend.passkeys = [passkey("cred-new", "Laptop", null)];
        const add = [...container.querySelectorAll("button")]
            .find((candidate) => candidate.textContent === enAccount.AccountSecurity.add);
        if (!add) throw new Error("no add button");

        await act(async () => add.click());

        expect(backend.finishPasskeyRegistration).toHaveBeenCalledOnce();
        expect(backend.refresh).toHaveBeenCalledWith("ceremony");
        expect(container.textContent).toContain("Laptop");
    });

    it("shows the ordinary approval-code card to an account in good standing", async () => {
        backend.passkeys = [passkey("cred-laptop", "Laptop", null)];
        backend.requirements = requirementsFor("SATISFIED");
        await renderPanel();

        expect(container.textContent).toContain(enAccount.AccountSecurity.approvalTitle);
        expect(container.textContent).not.toContain(enAccount.AccountSecurity.approvalNeededTitle);
        expect(container.textContent).not.toContain(enAccount.AccountSecurity.enrollmentNeededTitle);
    });
});
