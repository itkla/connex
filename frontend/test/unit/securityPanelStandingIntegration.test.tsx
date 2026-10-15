// @vitest-environment jsdom
import { act, createElement, type ReactNode } from "react";
import { createRoot, type Root } from "react-dom/client";
import { NextIntlClientProvider } from "next-intl";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import SecurityPanel from "@/app/components/account/SecurityPanel";
import { privilegedMfaConfinementReason } from "@/app/lib/api";
import enAccount from "@/messages/en/account.json";

const harness = vi.hoisted(() => ({
    approve: null as ((orgId: number) => void) | null,
    standings: [] as string[],
}));

vi.mock("@/app/hooks/usePasskeySupport", () => ({ usePasskeySupport: () => true }));
vi.mock("@/app/hooks/usePasskeyStepUpError", () => ({ usePasskeyStepUpErrorHandler: () => () => false }));
vi.mock("@/app/hooks/useWorkspace", () => ({
    useWorkspace: () => ({
        workspaces: [],
        runSelectionChange: async (change: (publish: (id: number | null) => void) => Promise<unknown>) =>
            change(() => undefined),
    }),
}));
vi.mock("next/navigation", () => ({ useRouter: () => ({ replace: vi.fn(), refresh: vi.fn() }) }));
vi.mock("@/app/hooks/useNow", () => ({ useLiveNow: () => Date.parse("2026-10-14T00:00:00Z") }));
vi.mock("@/app/lib/toast", () => ({ toastError: vi.fn(), toastInfo: vi.fn(), toastSuccess: vi.fn() }));
vi.mock("@/app/components/motion/Rise", () => ({
    default: ({ children }: { children?: ReactNode }) => createElement("div", null, children),
}));
vi.mock("@/app/components/dashboard/SectionHeader", () => ({
    default: ({ title, action }: { title: ReactNode; action?: ReactNode }) =>
        createElement("div", null, createElement("h2", null, title), action),
}));
vi.mock("@/app/components/account/PasskeyApprovalDialog", () => ({
    default: ({ onApproved }: { onApproved: (orgId: number) => void }) => {
        harness.approve = onApproved;
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

function json(body: unknown): Response {
    return new Response(JSON.stringify(body), { status: 200, headers: { "Content-Type": "application/json" } });
}

let container: HTMLDivElement;
let root: Root;

async function flush() {
    for (let i = 0; i < 20; i += 1) await act(async () => Promise.resolve());
}

beforeEach(() => {
    container = document.createElement("div");
    document.body.appendChild(container);
    root = createRoot(container);
    vi.stubGlobal("fetch", vi.fn(async (input: string | URL | Request) => {
        const url = input instanceof Request ? input.url : String(input);
        if (url.endsWith("/api/auth/webauthn/credentials")) {
            return json([{
                credentialId: "cred-laptop",
                label: "Laptop",
                transports: ["internal"],
                createdAt: "2026-09-01T00:00:00Z",
                lastUsedAt: null,
            }]);
        }
        if (url.endsWith("/api/auth/webauthn/register/requirements")) {
            const standing = harness.standings.shift() ?? "SATISFIED";
            return json({
                currentPasswordRequired: false,
                emailConfirmationRequired: false,
                emailConfirmationSatisfied: false,
                standing,
                approvalGaps: standing === "ATTESTATION_REQUIRED"
                    ? [{ credentialId: "cred-laptop", orgId: 1, orgName: "Acme" }]
                    : [],
            });
        }
        return json({});
    }));
});

afterEach(async () => {
    await act(async () => root.unmount());
    container.remove();
    vi.unstubAllGlobals();
});

describe("the Security page on the shared standing", () => {
    it("confines while approval is pending and releases once the redeemed code satisfies the standing", async () => {
        harness.standings = ["ATTESTATION_REQUIRED", "SATISFIED"];
        await act(async () => root.render(
            <NextIntlClientProvider locale="en" messages={enAccount} timeZone="UTC">
                <SecurityPanel />
            </NextIntlClientProvider>,
        ));
        await flush();

        expect(container.textContent).toContain(enAccount.AccountSecurity.approvalNeededTitle);
        expect(container.textContent).toContain("Approve Laptop. It still needs approval in Acme.");
        expect(privilegedMfaConfinementReason()).toBe("attestation");

        await act(async () => harness.approve?.(1));
        await flush();

        expect(privilegedMfaConfinementReason()).toBeNull();
        expect(container.textContent).not.toContain(enAccount.AccountSecurity.approvalNeededTitle);
        expect(container.textContent).toContain(enAccount.AccountSecurity.approvalTitle);
        expect(container.textContent).toContain(enAccount.AccountSecurity.add);
    });
});
