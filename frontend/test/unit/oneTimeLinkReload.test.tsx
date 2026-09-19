/** @vitest-environment jsdom */
import { act, type AnchorHTMLAttributes, type ComponentType, type PropsWithChildren } from "react";
import { createRoot, type Root } from "react-dom/client";
import { NextIntlClientProvider } from "next-intl";
import { afterEach, describe, expect, it, vi } from "vitest";

import { ConfirmEmailForm } from "@/app/auth/confirm-email/ConfirmEmailForm";
import { ConfirmPasskeyForm } from "@/app/auth/confirm-passkey/ConfirmPasskeyForm";
import { ResetPasswordForm } from "@/app/auth/reset-password/ResetPasswordForm";
import { VerifyEmailForm } from "@/app/auth/verify-email/VerifyEmailForm";
import DocumentAcceptanceEntry from "@/app/components/marketing/campaigns/DocumentAcceptanceEntry";
import UnsubscribeEntry from "@/app/components/marketing/campaigns/UnsubscribeEntry";
import { useReloadOnFragmentNavigation } from "@/app/hooks/useReloadOnFragmentNavigation";
import InviteLinkPage from "@/app/invite-link/page";
import InvitePage from "@/app/invite/page";
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
    takeOneTimeLinkToken: () => null,
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

function stubReload(): ReturnType<typeof vi.fn> {
    const real = window.location;
    const reload = vi.fn();
    Object.defineProperty(window, "location", {
        configurable: true,
        value: {
            get href() { return real.href; },
            get origin() { return real.origin; },
            get pathname() { return real.pathname; },
            get search() { return real.search; },
            get hash() { return real.hash; },
            replace: vi.fn(),
            reload,
        },
    });
    restoreLocation = () => Object.defineProperty(window, "location", {
        configurable: true,
        value: real,
    });
    return reload;
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
    useReloadOnFragmentNavigation();
    return null;
}

describe("useReloadOnFragmentNavigation", () => {
    it("reloads once per fragment navigation and stops listening after unmount", async () => {
        const reload = stubReload();
        const root = await mount(FragmentProbe);
        expect(reload).not.toHaveBeenCalled();

        await navigateFragment("/auth/reset-password", "second-link");
        expect(reload).toHaveBeenCalledTimes(1);

        await act(async () => root.unmount());
        await navigateFragment("/auth/reset-password", "third-link");
        expect(reload).toHaveBeenCalledTimes(1);
    });

    it("does not reload when the fragment is stripped with replaceState", async () => {
        const reload = stubReload();
        const root = await mount(FragmentProbe);

        await act(async () => {
            window.history.replaceState({}, "", "/auth/reset-password");
            await Promise.resolve();
        });
        expect(reload).not.toHaveBeenCalled();

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
        const reload = stubReload();
        const root = await mount(Entry);
        expect(reload).not.toHaveBeenCalled();

        await navigateFragment(path, "second-link");
        expect(reload).toHaveBeenCalledTimes(1);

        await act(async () => root.unmount());
    });
});
