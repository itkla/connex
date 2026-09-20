/** @vitest-environment jsdom */
import { act, useEffect, type AnchorHTMLAttributes, type ComponentType, type PropsWithChildren } from "react";
import { createRoot, type Root } from "react-dom/client";
import { NextIntlClientProvider } from "next-intl";
import { afterEach, describe, expect, it, vi } from "vitest";

import { ConfirmEmailForm } from "@/app/auth/confirm-email/ConfirmEmailForm";
import { ConfirmPasskeyForm } from "@/app/auth/confirm-passkey/ConfirmPasskeyForm";
import { ResetPasswordForm } from "@/app/auth/reset-password/ResetPasswordForm";
import { VerifyEmailForm } from "@/app/auth/verify-email/VerifyEmailForm";
import DocumentAcceptanceEntry from "@/app/components/marketing/campaigns/DocumentAcceptanceEntry";
import UnsubscribeEntry from "@/app/components/marketing/campaigns/UnsubscribeEntry";
import { useOneTimeLinkEntry } from "@/app/hooks/useOneTimeLinkEntry";
import InviteLinkPage from "@/app/invite-link/page";
import InvitePage from "@/app/invite/page";
import {
    exchangeEmailChangeToken,
    exchangeEmailVerificationToken,
    exchangePasswordResetToken,
    validateEmailChangeToken,
    validateEmailVerificationToken,
    validateResetToken,
} from "@/app/lib/api";
import { syncStrippedUrlWithRouter, takeOneTimeLinkToken } from "@/app/lib/oneTimeLink";
import enAuth from "@/messages/en/auth.json";
import enErrors from "@/messages/en/errors.json";
import enUnsubscribe from "@/messages/en/unsubscribe.json";
import enWorkspace from "@/messages/en/workspace.json";

vi.mock("next/link", async () => {
    const React = await import("react");
    type LinkProps = PropsWithChildren<AnchorHTMLAttributes<HTMLAnchorElement> & { href: string }>;
    return {
        default: ({ children, href, ...props }: LinkProps) =>
            React.createElement("a", { ...props, href }, children),
    };
});

vi.mock("next/navigation", () => ({
    useRouter: () => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn() }),
}));

vi.mock("@/app/lib/api", async (importOriginal) => {
    const actual = await importOriginal<Record<string, unknown>>();
    const isClass = (value: unknown) =>
        typeof value === "function" && /^class\b/.test(Function.prototype.toString.call(value));
    return Object.fromEntries(Object.entries(actual).map(([name, value]) => [
        name,
        typeof value === "function" && !isClass(value) ? vi.fn(() => new Promise<never>(() => {})) : value,
    ]));
});

vi.mock("@/app/lib/oneTimeLink", () => ({
    takeOneTimeLinkToken: vi.fn(() => null),
    syncStrippedUrlWithRouter: vi.fn(),
}));

vi.mock("@/app/components/auth/AuthBrandPanel", () => ({
    default: () => null,
}));

const MESSAGES = { ...enAuth, ...enErrors, ...enUnsubscribe, ...enWorkspace };

let restoreLocation: (() => void) | null = null;

afterEach(() => {
    restoreLocation?.();
    restoreLocation = null;
    document.body.replaceChildren();
    vi.clearAllMocks();
});

function stubLocation(): { reload: ReturnType<typeof vi.fn>; replace: ReturnType<typeof vi.fn> } {
    const real = window.location;
    const reload = vi.fn();
    const replace = vi.fn();
    Object.defineProperty(window, "location", {
        configurable: true,
        value: {
            get href() { return real.href; },
            get origin() { return real.origin; },
            get pathname() { return real.pathname; },
            get search() { return real.search; },
            get hash() { return real.hash; },
            set hash(value: string) { real.hash = value; },
            replace,
            reload,
        },
    });
    restoreLocation = () => Object.defineProperty(window, "location", {
        configurable: true,
        value: real,
    });
    return { reload, replace };
}

async function mount(Entry: ComponentType): Promise<Root> {
    const container = document.createElement("div");
    document.body.appendChild(container);
    const root = createRoot(container, { onCaughtError: vi.fn() });
    await act(async () => {
        root.render(
            <NextIntlClientProvider locale="en" messages={MESSAGES} timeZone="UTC" onError={() => {}}>
                <Entry />
            </NextIntlClientProvider>,
        );
        await Promise.resolve();
    });
    return root;
}

async function navigateFragment(path: string, token: string) {
    await act(async () => {
        window.history.replaceState({}, "", `${path}#token=${token}`);
        window.dispatchEvent(new HashChangeEvent("hashchange"));
        await Promise.resolve();
    });
}

function FragmentProbe() {
    useOneTimeLinkEntry();
    return null;
}

/** Mirrors an entry page: listens for fragment navigation, then reads and strips the bearer on mount. */
function StrippingEntryProbe({ take, onToken }: {
    take: () => string | null;
    onToken: (token: string | null) => void;
}) {
    useOneTimeLinkEntry();
    useEffect(() => {
        onToken(take());
    }, [take, onToken]);
    return null;
}

/**
 * Mirrors `AppRouter`, which installs the `history` patch the router sync depends on in its own
 * mount effect — the effect React runs after every descendant's.
 */
function PatchInstaller({ children, onInstall }: PropsWithChildren<{ onInstall: () => void }>) {
    useEffect(() => {
        onInstall();
    }, [onInstall]);
    return children;
}

async function settleQueuedEvents() {
    await act(async () => {
        await new Promise((resolve) => setTimeout(resolve, 0));
        await new Promise((resolve) => setTimeout(resolve, 0));
    });
}

describe("useOneTimeLinkEntry", () => {
    it("reloads once per fragment navigation and stops listening after unmount", async () => {
        const { reload } = stubLocation();
        const root = await mount(FragmentProbe);
        expect(reload).not.toHaveBeenCalled();

        await navigateFragment("/auth/reset-password", "second-link");
        expect(reload).toHaveBeenCalledTimes(1);

        await act(async () => root.unmount());
        await navigateFragment("/auth/reset-password", "third-link");
        expect(reload).toHaveBeenCalledTimes(1);
    });

    it("hands the router the stripped URL only once the history patch is installed", async () => {
        const order: string[] = [];
        vi.mocked(syncStrippedUrlWithRouter).mockImplementationOnce(() => {
            order.push("router sync");
        });
        function PatchedEntry() {
            return (
                <PatchInstaller onInstall={() => order.push("history patch")}>
                    <FragmentProbe />
                </PatchInstaller>
            );
        }

        const root = await mount(PatchedEntry);
        await settleQueuedEvents();

        expect(order).toEqual(["history patch", "router sync"]);

        await act(async () => root.unmount());
    });

    it("hands the router the stripped URL even when the entry unmounts first", async () => {
        const root = await mount(FragmentProbe);

        await act(async () => root.unmount());
        await settleQueuedEvents();

        expect(syncStrippedUrlWithRouter).toHaveBeenCalledTimes(1);
    });

    it("does not reload when the real one-time-link reader strips the fragment", async () => {
        const { takeOneTimeLinkToken } = await vi.importActual<typeof import("@/app/lib/oneTimeLink")>(
            "@/app/lib/oneTimeLink",
        );
        window.history.replaceState({}, "", "/auth/reset-password#token=first-link");
        const { reload } = stubLocation();
        const hashChanges = vi.fn();
        window.addEventListener("hashchange", hashChanges);
        const onToken = vi.fn();
        function ResetPasswordEntry() {
            return <StrippingEntryProbe take={takeOneTimeLinkToken} onToken={onToken} />;
        }

        const root = await mount(ResetPasswordEntry);
        await settleQueuedEvents();

        expect(onToken).toHaveBeenCalledWith("first-link");
        expect(hashChanges).not.toHaveBeenCalled();
        expect(reload).not.toHaveBeenCalled();
        expect(window.location.href).toBe(`${window.location.origin}/auth/reset-password`);

        window.removeEventListener("hashchange", hashChanges);
        await act(async () => root.unmount());
    });
});

describe.each([
    { path: "/invite", Entry: InvitePage },
    { path: "/invite-link", Entry: InviteLinkPage },
    { path: "/auth/reset-password", Entry: ResetPasswordForm },
    { path: "/auth/verify-email", Entry: VerifyEmailForm },
    { path: "/auth/confirm-email", Entry: ConfirmEmailForm },
    { path: "/auth/confirm-passkey", Entry: ConfirmPasskeyForm },
    { path: "/unsubscribe", Entry: UnsubscribeEntry },
    { path: "/document-acceptance", Entry: DocumentAcceptanceEntry },
])("one-time-link entry at $path", ({ path, Entry }) => {
    it("re-opens a second emailed link that lands in the same tab", async () => {
        window.history.replaceState({}, "", path);
        const { reload } = stubLocation();
        const root = await mount(Entry);
        expect(reload).not.toHaveBeenCalled();

        await navigateFragment(path, "second-link");
        expect(reload).toHaveBeenCalledTimes(1);

        await act(async () => root.unmount());
    });
});

describe.each([
    {
        path: "/auth/reset-password",
        Entry: ResetPasswordForm,
        exchange: exchangePasswordResetToken,
        validate: validateResetToken,
        validating: enAuth.AuthResetPassword.validatingLabel,
    },
    {
        path: "/auth/verify-email",
        Entry: VerifyEmailForm,
        exchange: exchangeEmailChangeToken,
        validate: validateEmailChangeToken,
        validating: enAuth.AuthVerifyEmail.validatingLabel,
    },
    {
        path: "/auth/confirm-email",
        Entry: ConfirmEmailForm,
        exchange: exchangeEmailVerificationToken,
        validate: validateEmailVerificationToken,
        validating: enAuth.AuthConfirmEmail.validatingLabel,
    },
])("auth entry at $path after a successful exchange", ({ path, Entry, exchange, validate, validating }) => {
    it("stays on its validating state while the replacing navigation loads", async () => {
        window.history.replaceState({}, "", path);
        const { replace } = stubLocation();
        vi.mocked(takeOneTimeLinkToken).mockReturnValueOnce("first-link");
        vi.mocked(exchange).mockResolvedValueOnce(undefined);

        const root = await mount(Entry);
        await settleQueuedEvents();

        expect(exchange).toHaveBeenCalledWith("first-link");
        expect(replace).toHaveBeenCalledWith(path);
        expect(validate).not.toHaveBeenCalled();
        expect(document.body.querySelector("h1")).toBeNull();
        expect(document.body.textContent).toContain(validating);

        await act(async () => root.unmount());
    });
});
