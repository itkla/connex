/** @vitest-environment jsdom */
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { useEffect, type ReactNode } from "react";
import { NextIntlClientProvider } from "next-intl";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import PrivilegedMfaRouteGate from "@/app/components/account/PrivilegedMfaRouteGate";
import { PinnedViewsProvider, usePinnedViews } from "@/app/hooks/usePinnedViews";
import { NotificationProvider } from "@/app/hooks/useNotifications";
import { PermissionsProvider } from "@/app/hooks/usePermissions";
import { useAskConnexSkills } from "@/app/hooks/useAskConnexSkills";
import { currentPrivilegedMfaReadiness, forgetPrivilegedMfaStanding, getContactsPage, privilegedMfaConfinementReason, settlePrivilegedMfaStanding } from "@/app/lib/api";
import account from "@/messages/en/account.json";
import notifications from "@/messages/en/notifications.json";
import jaAccount from "@/messages/ja/account.json";

const boundary = vi.hoisted(() => ({
    socket: vi.fn(() => ({ activate: vi.fn(), deactivate: vi.fn() })),
    recordMounted: vi.fn(),
    toast: vi.fn(),
    pathname: "/records/contacts",
}));
vi.mock("next/navigation", () => ({
    usePathname: () => boundary.pathname,
    useSearchParams: () => new URLSearchParams(),
    useRouter: () => ({ refresh: vi.fn() }),
}));
vi.mock("@/app/hooks/useWorkspace", () => ({ useWorkspace: () => ({ activeWorkspaceId: 7, switching: false }) }));
vi.mock("@/app/hooks/useActions", () => ({ useActions: () => ({ context: { user: { id: 3 } } }) }));
vi.mock("@/app/components/notifications/useNotificationWorkspaceActions", () => ({
    useNotificationWorkspaceActions: () => ({ openNotification: vi.fn() }),
}));
vi.mock("@/app/lib/realtime", () => ({ createNotificationSocket: boundary.socket }));
vi.mock("@/app/lib/toast", () => ({ toastError: boundary.toast, toastInfo: boundary.toast, toastWarn: boundary.toast }));

function RecordsPage() {
    useEffect(() => {
        boundary.recordMounted();
        void getContactsPage().catch(() => undefined);
    }, []);
    return <h1>Contacts</h1>;
}
function ShellReaders() {
    const pins = usePinnedViews();
    useAskConnexSkills();
    return <output aria-label="Pinned views">{pins.status}</output>;
}
function mount(locale: "en" | "ja" = "en", content: ReactNode = <RecordsPage />) {
    return render(<NextIntlClientProvider locale={locale} timeZone="UTC" messages={{ ...(locale === "en" ? account : jaAccount), ...notifications }}>
        <PermissionsProvider permissions={["AI_USE"]} status="resolved">
            <NotificationProvider recipientId={3}><PinnedViewsProvider>
                <ShellReaders />
                <PrivilegedMfaRouteGate>{content}</PrivilegedMfaRouteGate>
            </PinnedViewsProvider></NotificationProvider>
        </PermissionsProvider>
    </NextIntlClientProvider>);
}
function json(body: unknown, status = 200) {
    return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}
function requirements(standing: string | undefined) {
    return json({ currentPasswordRequired: true, emailConfirmationRequired: false,
        emailConfirmationSatisfied: false, ...(standing === undefined ? {} : { standing }) });
}
function transport(policy: () => Promise<Response>, standing: () => Promise<Response>) {
    const protectedReads: string[] = [];
    vi.stubGlobal("fetch", vi.fn(async (input: string | URL | Request) => {
        const path = input instanceof Request ? input.url : String(input);
        if (path.endsWith("/api/capabilities")) return (await policy()).clone();
        if (path.endsWith("/api/auth/webauthn/register/requirements")) return (await standing()).clone();
        protectedReads.push(path);
        if (path.endsWith("/api/notifications/counts")) return json({ unread: 0, snoozed: 0, quietHoursActive: false, stateVersion: 0 });
        if (path.includes("/api/persons/page")) return json({ items: [], total: 0 });
        return json([]);
    }));
    return protectedReads;
}
function expectHeld(reads: string[]) {
    expect(boundary.recordMounted).not.toHaveBeenCalled();
    expect(reads).toEqual([]);
    expect(boundary.socket).not.toHaveBeenCalled();
    expect(boundary.toast).not.toHaveBeenCalled();
}
beforeEach(() => {
    boundary.pathname = "/records/contacts";
    window.history.replaceState(null, "", boundary.pathname);
    forgetPrivilegedMfaStanding();
    vi.clearAllMocks();
});
afterEach(() => {
    cleanup();
    forgetPrivilegedMfaStanding();
    vi.unstubAllGlobals();
});

describe("fresh account policy readiness", () => {
    it.each([true, false])("holds all readers until a delayed policy resolves to %s", async (enforced) => {
        const policy = Promise.withResolvers<Response>();
        const standing = Promise.withResolvers<Response>();
        const reads = transport(() => policy.promise, () => standing.promise);
        mount();
        expect(screen.getByRole("heading", { name: account.AccountSecurity.accessCheckingTitle })).toBeTruthy();
        expectHeld(reads);
        await act(async () => standing.resolve(requirements("ENROLLMENT_REQUIRED")));
        await act(async () => {
            window.dispatchEvent(new Event("focus"));
            document.dispatchEvent(new Event("visibilitychange"));
        });
        expectHeld(reads);
        await act(async () => policy.resolve(json({ privilegedMfaEnforced: enforced })));
        if (enforced) {
            expect(screen.getByRole("heading", { name: account.AccountSecurity.enrollmentNeededTitle })).toBeTruthy();
            expectHeld(reads);
        } else {
            await waitFor(() => expect(boundary.recordMounted).toHaveBeenCalledOnce());
            await waitFor(() => expect(reads.some((path) => path.endsWith("/api/notifications/counts"))).toBe(true));
            expect(reads.some((path) => path.endsWith("/api/saved-views/pins"))).toBe(true);
            expect(reads.some((path) => path.includes("/api/ai/assistant/skills"))).toBe(true);
            expect(boundary.toast).not.toHaveBeenCalled();
        }
    });

    it.each([true, false])("handles policy %s while requirements remain delayed", async (enforced) => {
        const policy = Promise.withResolvers<Response>();
        const standing = Promise.withResolvers<Response>();
        const reads = transport(() => policy.promise, () => standing.promise);
        mount();
        await act(async () => policy.resolve(json({ privilegedMfaEnforced: enforced })));
        if (enforced) {
            expectHeld(reads);
            expect(currentPrivilegedMfaReadiness()).toBe("loading");
        } else {
            await waitFor(() => expect(boundary.recordMounted).toHaveBeenCalledOnce());
            expect(currentPrivilegedMfaReadiness()).toBe("ready");
        }
        await act(async () => standing.resolve(requirements("ENROLLMENT_REQUIRED")));
        if (enforced) expectHeld(reads);
        else expect(privilegedMfaConfinementReason()).toBeNull();
    });

    it.each(["absent", "failed", "missing standing"] as const)("keeps readers held for %s policy and retries", async (failure) => {
        let retry = false;
        const reads = transport(async () => {
            if (retry) return json({ privilegedMfaEnforced: false });
            if (failure === "failed") throw new Error("Network unavailable");
            return json(failure === "absent" ? {} : { privilegedMfaEnforced: true });
        }, async () => failure === "missing standing" ? requirements(undefined) : requirements("ENROLLMENT_REQUIRED"));
        mount();
        await waitFor(() => expect(screen.getByRole("heading", { name: account.AccountSecurity.accessCheckFailedTitle })).toBeTruthy());
        expectHeld(reads);
        expect(privilegedMfaConfinementReason()).toBeNull();
        expect(screen.getByRole("link", { name: account.AccountSecurity.accessSecurity }).getAttribute("href")).toBe("/settings/personal/security");
        retry = true;
        fireEvent.click(screen.getByRole("button", { name: account.AccountSecurity.retry }));
        await waitFor(() => expect(boundary.recordMounted).toHaveBeenCalledOnce());
    });

    it.each([undefined, "ENROLLMENT_REQUIRED", "ATTESTATION_REQUIRED"] as const)("keeps master-off pages normal with advisory standing %s", async (standing) => {
        transport(async () => json({ privilegedMfaEnforced: false }), async () => requirements(standing));
        mount();
        await waitFor(() => expect(boundary.recordMounted).toHaveBeenCalledOnce());
        expect(screen.getByRole("heading", { name: "Contacts" })).toBeTruthy();
        expect(privilegedMfaConfinementReason()).toBeNull();
    });

    it("releases master-off pages even when requirements fail", async () => {
        transport(async () => json({ privilegedMfaEnforced: false }), async () => { throw new Error("Unavailable"); });
        mount();
        await waitFor(() => expect(boundary.recordMounted).toHaveBeenCalledOnce());
        expect(currentPrivilegedMfaReadiness()).toBe("ready");
    });

    it.each(["SATISFIED", "UNPRIVILEGED"])("opens enforced-on pages after standing resolves to %s", async (value) => {
        const standing = Promise.withResolvers<Response>();
        const reads = transport(async () => json({ privilegedMfaEnforced: true }), () => standing.promise);
        mount();
        expectHeld(reads);
        await act(async () => standing.resolve(requirements(value)));
        await waitFor(() => expect(boundary.recordMounted).toHaveBeenCalledOnce());
        expect(privilegedMfaConfinementReason()).toBeNull();
    });

    it("preserves a mounted page during a pending background recheck", async () => {
        let checking = false;
        const policy = Promise.withResolvers<Response>();
        const standing = Promise.withResolvers<Response>();
        transport(() => checking ? policy.promise : Promise.resolve(json({ privilegedMfaEnforced: true })),
            () => checking ? standing.promise : Promise.resolve(requirements("SATISFIED")));
        mount();
        await waitFor(() => expect(boundary.recordMounted).toHaveBeenCalledOnce());
        const heading = screen.getByRole("heading", { name: "Contacts" });
        checking = true;
        let check: Promise<unknown> = Promise.resolve();
        await act(async () => { check = settlePrivilegedMfaStanding("check"); });
        await act(async () => policy.resolve(json({ privilegedMfaEnforced: true })));
        expect(screen.getByRole("heading", { name: "Contacts" })).toBe(heading);
        await act(async () => { standing.resolve(requirements("SATISFIED")); await check; });
        expect(screen.getByRole("heading", { name: "Contacts" })).toBe(heading);
        expect(boundary.recordMounted).toHaveBeenCalledOnce();
    });

    it("keeps Security reachable while policy is unresolved", async () => {
        boundary.pathname = "/settings/personal/security";
        const reads = transport(() => new Promise(() => undefined), () => new Promise(() => undefined));
        mount("en", <h1>Security</h1>);
        expect(screen.getByRole("heading", { name: "Security" })).toBeTruthy();
        expectHeld(reads);
    });

    it("localizes the neutral pending state in Japanese", () => {
        const reads = transport(() => new Promise(() => undefined), () => new Promise(() => undefined));
        mount("ja");
        expect(screen.getByRole("heading", { name: jaAccount.AccountSecurity.accessCheckingTitle })).toBeTruthy();
        expectHeld(reads);
    });
});
