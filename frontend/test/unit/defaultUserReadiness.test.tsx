/** @vitest-environment jsdom */
import { act, cleanup, render, screen, waitFor } from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import PrivilegedMfaRouteGate from "@/app/components/account/PrivilegedMfaRouteGate";
import { PinnedViewsProvider, usePinnedViews } from "@/app/hooks/usePinnedViews";
import { currentPrivilegedMfaRequirements, forgetPrivilegedMfaStanding, getSavedViewPins, privilegedMfaConfinementReason, settlePrivilegedMfaStanding } from "@/app/lib/api";
import messages from "@/messages/en/account.json";

vi.mock("next/navigation", () => ({
    usePathname: () => "/records/contacts",
    useSearchParams: () => new URLSearchParams(),
}));
vi.mock("@/app/hooks/useWorkspace", () => ({ useWorkspace: () => ({ activeWorkspaceId: 7, switching: false }) }));

function RecordsPage() {
    const pins = usePinnedViews();
    return <div><h1>Contacts</h1><output aria-label="Pinned views">{pins.status}</output></div>;
}

beforeEach(() => {
    window.history.replaceState(null, "", "/records/contacts");
    forgetPrivilegedMfaStanding();
});

afterEach(() => {
    cleanup();
    forgetPrivilegedMfaStanding();
    vi.unstubAllGlobals();
});

describe("an ordinary signed-in account with MFA enforcement disabled", () => {
    it("does not invent confinement when the policy hint itself is absent", async () => {
        vi.stubGlobal("fetch", vi.fn(async (input: string | URL | Request) => {
            const path = input instanceof Request ? input.url : String(input);
            const body = path.endsWith("/api/capabilities") ? {}
                : path.endsWith("/api/auth/webauthn/register/requirements") ? {
                    currentPasswordRequired: true, emailConfirmationRequired: false,
                    emailConfirmationSatisfied: false, standing: "ENROLLMENT_REQUIRED",
                } : [];
            return new Response(JSON.stringify(body), { status: 200, headers: { "Content-Type": "application/json" } });
        }));
        render(<NextIntlClientProvider locale="en" timeZone="UTC" messages={messages}>
            <PinnedViewsProvider><PrivilegedMfaRouteGate><RecordsPage /></PrivilegedMfaRouteGate></PinnedViewsProvider>
        </NextIntlClientProvider>);
        await act(async () => { expect(await settlePrivilegedMfaStanding("check")).toBe("failed"); });
        await waitFor(() => expect(screen.getByLabelText("Pinned views").textContent).toBe("ready"));
        expect(screen.getByRole("heading", { name: "Contacts" })).toBeTruthy();
        expect(privilegedMfaConfinementReason()).toBeNull();
        await expect(getSavedViewPins()).resolves.toEqual([]);
    });

    it.each([undefined, "ENROLLMENT_REQUIRED", "ATTESTATION_REQUIRED"] as const)("keeps normal pages and reads available with advisory standing %s", async (standing) => {
        vi.stubGlobal("fetch", vi.fn(async (input: string | URL | Request) => {
            const path = input instanceof Request ? input.url : String(input);
            const body = path.endsWith("/api/capabilities") ? { privilegedMfaEnforced: false }
                : path.endsWith("/api/auth/webauthn/register/requirements") ? {
                    currentPasswordRequired: true,
                    emailConfirmationRequired: false,
                    emailConfirmationSatisfied: false,
                    ...(standing === undefined ? {} : { standing }),
                } : [];
            return new Response(JSON.stringify(body), { status: 200, headers: { "Content-Type": "application/json" } });
        }));
        render(<NextIntlClientProvider locale="en" timeZone="UTC" messages={messages}>
            <PinnedViewsProvider><PrivilegedMfaRouteGate><RecordsPage /></PrivilegedMfaRouteGate></PinnedViewsProvider>
        </NextIntlClientProvider>);
        await waitFor(() => expect(currentPrivilegedMfaRequirements()).not.toBeNull());
        await waitFor(() => expect(screen.getByLabelText("Pinned views").textContent).toBe("ready"));
        expect(screen.getByRole("heading", { name: "Contacts" })).toBeTruthy();
        expect(screen.queryByRole("link", { name: messages.AccountSecurity.returnToSecurity })).toBeNull();
        expect(privilegedMfaConfinementReason()).toBeNull();
        await expect(getSavedViewPins()).resolves.toEqual([]);
    });
});
