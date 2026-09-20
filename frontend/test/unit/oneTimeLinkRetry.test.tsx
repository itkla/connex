/** @vitest-environment jsdom */
import { act, type AnchorHTMLAttributes, type ComponentType, type PropsWithChildren } from "react";
import { createRoot, type Root } from "react-dom/client";
import { NextIntlClientProvider } from "next-intl";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import InviteLinkPage from "@/app/invite-link/page";
import InvitePage from "@/app/invite/page";
import { ApiError } from "@/app/lib/api";
import enErrors from "@/messages/en/errors.json";
import enWorkspace from "@/messages/en/workspace.json";

const api = vi.hoisted(() => ({
    exchangeInviteToken: vi.fn(),
    exchangeInviteLinkToken: vi.fn(),
    getInvitePreview: vi.fn(),
    getInviteLinkPreview: vi.fn(),
    me: vi.fn(),
}));

const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn() }));

vi.mock("next/link", async () => {
    const React = await import("react");
    type LinkProps = PropsWithChildren<AnchorHTMLAttributes<HTMLAnchorElement> & { href: string }>;
    return {
        default: ({ children, href, ...props }: LinkProps) =>
            React.createElement("a", { ...props, href }, children),
    };
});

vi.mock("next/navigation", () => ({
    useRouter: () => router,
}));

vi.mock("@/app/lib/api", async (importOriginal) => ({
    ...await importOriginal<Record<string, unknown>>(),
    ...api,
}));

const MESSAGES = { ...enErrors, ...enWorkspace };
const BEARER = "browser_only_retry_bearer_123456789";

let restoreLocation: (() => void) | null = null;
let root: Root | null = null;

beforeEach(() => {
    api.me.mockReturnValue(new Promise<never>(() => {}));
    api.getInvitePreview.mockReturnValue(new Promise<never>(() => {}));
    api.getInviteLinkPreview.mockReturnValue(new Promise<never>(() => {}));
});

afterEach(async () => {
    const mounted = root;
    root = null;
    if (mounted) {
        await act(async () => mounted.unmount());
    }
    restoreLocation?.();
    restoreLocation = null;
    document.body.replaceChildren();
    vi.resetAllMocks();
});

function stubNavigation(): ReturnType<typeof vi.fn> {
    const real = window.location;
    const replace = vi.fn();
    Object.defineProperty(window, "location", {
        configurable: true,
        value: {
            get href() { return real.href; },
            get origin() { return real.origin; },
            get pathname() { return real.pathname; },
            get search() { return real.search; },
            get hash() { return real.hash; },
            replace,
            reload: vi.fn(),
        },
    });
    restoreLocation = () => Object.defineProperty(window, "location", {
        configurable: true,
        value: real,
    });
    return replace;
}

async function mount(Entry: ComponentType) {
    const container = document.createElement("div");
    document.body.appendChild(container);
    const created = createRoot(container);
    root = created;
    await act(async () => {
        created.render(
            <NextIntlClientProvider locale="en" messages={MESSAGES} timeZone="UTC" onError={() => {}}>
                <Entry />
            </NextIntlClientProvider>,
        );
    });
}

function retryButton(): HTMLButtonElement {
    const button = Array.from(document.querySelectorAll("button"))
        .find((candidate) => candidate.textContent?.includes(enErrors.WorkspaceUnavailable.retry));
    if (!button) {
        throw new Error("The unavailable state rendered no retry control");
    }
    return button;
}

describe.each([
    {
        path: "/invite",
        Entry: InvitePage,
        exchange: api.exchangeInviteToken,
        failure: new ApiError("Too many requests", 429),
    },
    {
        path: "/invite-link",
        Entry: InviteLinkPage,
        exchange: api.exchangeInviteLinkToken,
        failure: new TypeError("Failed to fetch"),
    },
])("invite entry at $path", ({ path, Entry, exchange, failure }) => {
    it("retries a failed exchange with the in-memory bearer and keeps the fragment out of the URL", async () => {
        window.history.replaceState({}, "", `${path}#token=${BEARER}`);
        const replace = stubNavigation();
        exchange.mockRejectedValueOnce(failure).mockResolvedValueOnce(undefined);

        await mount(Entry);

        expect(exchange).toHaveBeenCalledTimes(1);
        expect(exchange).toHaveBeenLastCalledWith(BEARER);
        expect(window.location.href).toBe(`${window.location.origin}${path}`);

        await act(async () => retryButton().click());

        expect(router.refresh).not.toHaveBeenCalled();
        expect(exchange).toHaveBeenCalledTimes(2);
        expect(exchange).toHaveBeenLastCalledWith(BEARER);
        expect(replace).toHaveBeenCalledWith(path);
        expect(window.location.href).toBe(`${window.location.origin}${path}`);
    });
});
