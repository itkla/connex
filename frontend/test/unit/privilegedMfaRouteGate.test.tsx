/** @vitest-environment jsdom */
import { act, cleanup, render, screen } from "@testing-library/react";
import { useEffect } from "react";
import { NextIntlClientProvider } from "next-intl";
import { afterEach, describe, expect, it, vi } from "vitest";

import PrivilegedMfaRouteGate from "@/app/components/account/PrivilegedMfaRouteGate";
import en from "@/messages/en/account.json";
import ja from "@/messages/ja/account.json";

const state = vi.hoisted(() => ({ readiness: "ready", confined: true, reason: "enrollment", pathname: "/records/contacts", mounted: vi.fn() }));
vi.mock("next/navigation", () => ({ usePathname: () => state.pathname }));
vi.mock("@/app/hooks/usePrivilegedMfaStanding", () => ({ usePrivilegedMfaStanding: () => state }));
vi.mock("@/app/hooks/usePrivilegedMfaConfinement", () => ({ usePrivilegedMfaReadiness: () => state.readiness }));
vi.mock("@/app/lib/api", () => ({ privilegedMfaConfinementDestination: () => `/settings/personal/security?mfa=${state.reason === "attestation" ? "approve" : "enroll"}` }));

function RecordBrowser() {
    useEffect(() => { state.mounted(); }, []);
    return <p>Records loaded</p>;
}

afterEach(() => {
    cleanup();
    state.readiness = "ready";
    state.confined = true;
    state.reason = "enrollment";
    state.pathname = "/records/contacts";
    vi.clearAllMocks();
});

describe("passkey-confined client navigation", () => {
    it.each(["en", "ja"] as const)("explains the next step without mounting record fetches in %s", async (locale) => {
        const messages = locale === "en" ? en : ja;
        const tree = () => <NextIntlClientProvider locale={locale} messages={messages}>
            <PrivilegedMfaRouteGate><RecordBrowser /></PrivilegedMfaRouteGate>
        </NextIntlClientProvider>;
        const mounted = render(tree());
        expect(screen.getByRole("heading", { name: messages.AccountSecurity.enrollmentNeededTitle })).toBeTruthy();
        expect(screen.getByRole("link", { name: messages.AccountSecurity.returnToSecurity }).getAttribute("href"))
            .toBe("/settings/personal/security?mfa=enroll");
        expect(state.mounted).not.toHaveBeenCalled();
        state.reason = "attestation";
        mounted.rerender(tree());
        expect(screen.getByRole("heading", { name: messages.AccountSecurity.approvalNeededTitle })).toBeTruthy();
        expect(screen.getByRole("link").getAttribute("href")).toBe("/settings/personal/security?mfa=approve");
        state.confined = false;
        await act(async () => mounted.rerender(tree()));
        expect(state.mounted).toHaveBeenCalledOnce();
    });

    it("keeps the security step reachable during confinement", () => {
        state.pathname = "/settings/personal/security";
        render(<NextIntlClientProvider locale="en" messages={en}>
            <PrivilegedMfaRouteGate><RecordBrowser /></PrivilegedMfaRouteGate>
        </NextIntlClientProvider>);
        expect(state.mounted).toHaveBeenCalledOnce();
    });
});
