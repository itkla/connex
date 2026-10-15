// @vitest-environment jsdom
import { act, createElement, type ReactNode } from "react";
import { createRoot, type Root } from "react-dom/client";
import { NextIntlClientProvider } from "next-intl";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import SecurityPanel from "@/app/components/account/SecurityPanel";
import { ApiError } from "@/app/lib/api";
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
    reason: null as "enrollment" | "attestation" | null,
    failed: false,
    leaveWorkspace: vi.fn<(id: number) => Promise<{ activeWorkspaceId: number | null; standing: "applied" | "failed" | null }>>(),
    readSelection: vi.fn<() => Promise<{ activeWorkspaceId: number | null; workspaces: [] }>>(),
    settle: vi.fn<(settlement: string) => Promise<"applied" | "failed">>(),
    publish: vi.fn(),
    replace: vi.fn(),
    routerRefresh: vi.fn(),
}));

vi.mock("@/app/lib/api", async (importOriginal) => ({
    ...await importOriginal<typeof import("@/app/lib/api")>(),
    getPasskeys: backend.getPasskeys,
    beginPasskeyRegistration: backend.beginPasskeyRegistration,
    finishPasskeyRegistration: backend.finishPasskeyRegistration,
    leaveWorkspace: backend.leaveWorkspace,
    readAuthoritativeWorkspaceSelection: backend.readSelection,
    settlePrivilegedMfaStanding: backend.settle,
}));
vi.mock("@simplewebauthn/browser", async (importOriginal) => ({
    ...await importOriginal<typeof import("@simplewebauthn/browser")>(),
    startRegistration: backend.startRegistration,
}));
vi.mock("@/app/hooks/usePrivilegedMfaStanding", () => ({
    usePrivilegedMfaStanding: () => ({
        requirements: backend.requirements,
        status: backend.requirements ? "ready" : backend.failed ? "failed" : "loading",
        fresh: !backend.failed,
        confined: backend.reason !== null
            || backend.requirements?.standing === "ATTESTATION_REQUIRED"
            || backend.requirements?.standing === "ENROLLMENT_REQUIRED",
        reason: backend.reason,
        refresh: backend.refresh,
    }),
}));
vi.mock("@/app/hooks/usePasskeySupport", () => ({ usePasskeySupport: () => true }));
vi.mock("@/app/hooks/usePasskeyStepUpError", () => ({ usePasskeyStepUpErrorHandler: () => () => false }));
vi.mock("@/app/hooks/useWorkspace", () => ({
    useWorkspace: () => ({
        workspaces: [],
        runSelectionChange: async (change: (publish: (id: number | null) => void) => Promise<unknown>) =>
            change(backend.publish),
    }),
}));
vi.mock("next/navigation", () => ({ useRouter: () => ({ replace: backend.replace, refresh: backend.routerRefresh }) }));
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

function confinementCard(): HTMLElement | null {
    return [...container.querySelectorAll<HTMLElement>("section")].find((section) =>
        section.textContent?.includes(enAccount.AccountSecurity.approvalNeededTitle)
        || section.textContent?.includes(enAccount.AccountSecurity.enrollmentNeededTitle)) ?? null;
}
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
    backend.reason = null;
    backend.failed = false;
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

        const card = confinementCard();
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

        const card = confinementCard();
        expect(card?.textContent).toContain("Approve Laptop. It still needs approval in Acme.");
        expect(card?.querySelector("ul")).toBeNull();
    });

    it("explains an enrollment confinement without a second add button", async () => {
        backend.requirements = requirementsFor("ENROLLMENT_REQUIRED");
        await renderPanel();

        const card = confinementCard();
        expect(card?.textContent).toContain(enAccount.AccountSecurity.enrollmentNeededTitle);
        const buttons = [...(card?.querySelectorAll("button") ?? [])].map((button) => button.textContent);
        expect(buttons).not.toContain(enAccount.AccountSecurity.approvalEnter);
        expect(buttons).not.toContain(enAccount.AccountSecurity.add);
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

    it("keeps passkey management withdrawn on the approval step even when the standing cannot be read", async () => {
        backend.passkeys = [passkey("cred-laptop", "Laptop", null)];
        backend.reason = "attestation";
        backend.failed = true;
        await renderPanel();

        expect(container.textContent).toContain(enAccount.AccountSecurity.loadFailed);
        expect(container.textContent).not.toContain(enAccount.AccountSecurity.add);
        expect(confinementCard()).not.toBeNull();
    });

    it("offers no Add passkey while the standing cannot be read, whatever it turns out to be", async () => {
        backend.passkeys = [passkey("cred-laptop", "Laptop", null)];
        backend.failed = true;
        await renderPanel();

        expect(container.textContent).toContain(enAccount.AccountSecurity.loadFailed);
        expect(container.textContent).not.toContain(enAccount.AccountSecurity.add);
    });

    it("withholds code entry until the passkeys have been read", async () => {
        backend.getPasskeys.mockImplementation(() => new Promise(() => undefined));
        backend.requirements = requirementsFor("ATTESTATION_REQUIRED", [
            { credentialId: "cred-laptop", orgId: 1, orgName: "Acme" },
        ]);
        await renderPanel();

        expect(confinementCard()).not.toBeNull();
        expect([...container.querySelectorAll("button")].some(
            (candidate) => candidate.textContent === enAccount.AccountSecurity.approvalEnter,
        )).toBe(false);
    });

    it("lets a confined account check its standing again from the card", async () => {
        backend.passkeys = [passkey("cred-laptop", "Laptop", null)];
        backend.requirements = requirementsFor("ATTESTATION_REQUIRED", [
            { credentialId: "cred-laptop", orgId: 1, orgName: "Acme" },
        ]);
        await renderPanel();
        const recheck = [...(confinementCard()?.querySelectorAll("button") ?? [])]
            .find((candidate) => candidate.textContent === enAccount.AccountSecurity.confinementRecheck);

        await act(async () => recheck?.click());

        expect(backend.refresh).toHaveBeenCalledWith("check");
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

    describe("leaving a workspace that makes the account privileged", () => {
        function withWorkspaceExit() {
            backend.passkeys = [passkey("cred-laptop", "Laptop", null)];
            backend.requirements = {
                ...requirementsFor("ATTESTATION_REQUIRED", [{ credentialId: "cred-laptop", orgId: 3, orgName: "Acme" }]),
                privilegedMemberships: [
                    { orgId: 3, orgName: "Acme", orgRole: null, workspaces: [{ workspaceId: 7, workspaceName: "Sales" }] },
                ],
            };
        }

        function button(label: string): HTMLButtonElement {
            const match = [...container.querySelectorAll("button")].find((candidate) => candidate.textContent === label);
            if (!match) throw new Error(`no button labelled ${label}`);
            return match;
        }

        async function leaveSales() {
            await act(async () => button("Leave Sales").click());
            await act(async () => button(enAccount.AccountSecurity.exitConfirm).click());
        }

        it("leaves through the workspace selection and publishes the server's selection", async () => {
            withWorkspaceExit();
            backend.leaveWorkspace.mockResolvedValueOnce({ activeWorkspaceId: 9, standing: "applied" });
            await renderPanel();

            await leaveSales();

            expect(backend.leaveWorkspace).toHaveBeenCalledWith(7);
            expect(backend.publish).toHaveBeenCalledWith(9);
            expect(backend.routerRefresh).toHaveBeenCalled();
            expect(backend.replace).not.toHaveBeenCalled();
        });

        it("goes to onboarding when no workspace remains, where any remaining confinement is handled", async () => {
            withWorkspaceExit();
            backend.leaveWorkspace.mockResolvedValueOnce({ activeWorkspaceId: null, standing: null });
            await renderPanel();

            await leaveSales();

            expect(backend.publish).toHaveBeenCalledWith(null);
            expect(backend.replace).toHaveBeenCalledWith("/onboarding");
        });

        it("catches up with a workspace already left elsewhere instead of refusing the exit", async () => {
            withWorkspaceExit();
            backend.leaveWorkspace.mockRejectedValueOnce(
                new ApiError("You are not a member of this workspace", 404, "RESOURCE_NOT_FOUND"),
            );
            backend.readSelection.mockResolvedValueOnce({ activeWorkspaceId: 9, workspaces: [] });
            backend.settle.mockResolvedValueOnce("applied");
            await renderPanel();

            await leaveSales();

            expect(backend.readSelection).toHaveBeenCalledOnce();
            expect(backend.settle).toHaveBeenCalledWith("exit");
            expect(backend.publish).toHaveBeenCalledWith(9);
            expect(backend.routerRefresh).toHaveBeenCalled();
            expect(container.textContent).not.toContain(enAccount.AccountSecurity.exitRefused);
        });

        it("explains a refused exit and keeps the panel usable", async () => {
            withWorkspaceExit();
            backend.leaveWorkspace.mockRejectedValueOnce(new ApiError("Last owner", 400, "WORKSPACE_LAST_OWNER"));
            await renderPanel();

            await leaveSales();

            expect(container.textContent).toContain(enAccount.AccountSecurity.exitRefusedWorkspaceLastOwner);
            expect(button("Leave Sales").disabled).toBe(false);
            expect(backend.publish).not.toHaveBeenCalled();
        });
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
