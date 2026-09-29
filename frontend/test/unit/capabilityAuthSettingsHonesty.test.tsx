import {
    act,
    Fragment,
    isValidElement,
    type AnchorHTMLAttributes,
    type ComponentProps,
    type PropsWithChildren,
} from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { afterEach, describe, expect, it, vi } from "vitest";

import LoginPage from "@/app/auth/login/page";
import { AuthForm } from "@/app/components/AuthForm";
import type { InstanceCapabilities } from "@/app/lib/types";
import {
    installInteractiveDocument,
    type InteractiveElement,
} from "@/test/unit/helpers/interactiveDocument";

const {
    beginPasskeyAuthenticationMock,
    finishPasskeyAuthenticationMock,
    loginMock,
    passkeySupportState,
    redirectMock,
    routerPushMock,
    routerRefreshMock,
    routerReplaceMock,
    startAuthenticationMock,
} = vi.hoisted(() => ({
    beginPasskeyAuthenticationMock: vi.fn(async () => ({ challenge: "passkey-challenge" })),
    finishPasskeyAuthenticationMock: vi.fn(async () => {}),
    loginMock: vi.fn(async () => {}),
    passkeySupportState: { supported: false },
    redirectMock: vi.fn((destination: string): never => {
        throw new Error(`redirect:${destination}`);
    }),
    routerPushMock: vi.fn(),
    routerRefreshMock: vi.fn(),
    routerReplaceMock: vi.fn(),
    startAuthenticationMock: vi.fn(async () => ({ id: "credential" })),
}));

vi.mock("next/headers", () => ({
    headers: () => Promise.resolve(new Headers({
        cookie: "JSESSIONID=session; connex_workspace=7",
    })),
}));

vi.mock("next/link", async () => {
    const React = await import("react");
    type LinkProps = PropsWithChildren<AnchorHTMLAttributes<HTMLAnchorElement> & { href: string }>;
    return {
        default: ({ children, href, ...props }: LinkProps) =>
            React.createElement("a", { ...props, href }, children),
    };
});

vi.mock("next/navigation", () => ({
    redirect: redirectMock,
    usePathname: () => "/settings/members",
    useRouter: () => ({
        push: routerPushMock,
        refresh: routerRefreshMock,
        replace: routerReplaceMock,
    }),
}));

vi.mock("@simplewebauthn/browser", async (importOriginal) => {
    const actual = await importOriginal<typeof import("@simplewebauthn/browser")>();
    return {
        ...actual,
        startAuthentication: startAuthenticationMock,
    };
});

vi.mock("@/app/lib/api", async (importOriginal) => {
    const actual = await importOriginal<typeof import("@/app/lib/api")>();
    return {
        ...actual,
        beginPasskeyAuthentication: beginPasskeyAuthenticationMock,
        finishPasskeyAuthentication: finishPasskeyAuthenticationMock,
        login: loginMock,
    };
});

vi.mock("next-intl", () => ({
    useTranslations: (namespace: string) => (key: string) => `${namespace}.${key}`,
}));

vi.mock("next-intl/server", () => ({
    getTranslations: (namespace: string) =>
        Promise.resolve((key: string) => `${namespace}.${key}`),
}));

vi.mock("motion/react", async () => {
    const React = await import("react");
    type MotionSpanProps = ComponentProps<"span"> & {
        layoutId?: string;
        transition?: unknown;
    };
    return {
        motion: {
            span: ({ children, className }: MotionSpanProps) =>
                React.createElement("span", { className }, children),
        },
        useReducedMotion: () => true,
    };
});

vi.mock("@/app/hooks/usePasskeySupport", () => ({
    usePasskeySupport: () => passkeySupportState.supported,
}));

vi.mock("@/app/hooks/usePermissions", () => ({
    usePermission: () => true,
}));

vi.mock("@/app/components/auth/AuthBrandPanel", () => ({
    default: () => null,
}));

const DISABLED_CAPABILITIES = {
    sso: false,
    socialLogin: { google: false, microsoft: false },
    connectedAccounts: { google: false, microsoft: false },
    connectedCapture: { google: false, microsoft: false },
    mailManaged: false,
    businessCardScanning: false,
    businessCardImport: false,
    campaignDelivery: false,
    privilegedMfaEnforced: true,
} satisfies InstanceCapabilities;

function json(body: unknown): Response {
    return new Response(JSON.stringify(body), {
        status: 200,
        headers: { "Content-Type": "application/json" },
    });
}

function stubCapabilities(capabilities: InstanceCapabilities | null) {
    vi.stubGlobal("fetch", vi.fn((input: string | URL | Request) => {
        const url = String(input);
        if (!url.endsWith("/api/capabilities")) {
            return Promise.resolve(new Response("", { status: 404 }));
        }
        return Promise.resolve(capabilities === null
            ? new Response("", { status: 503 })
            : json(capabilities));
    }));
}

function requiredElement(
    elements: readonly InteractiveElement[],
    predicate: (element: InteractiveElement) => boolean,
    label: string,
): InteractiveElement {
    const element = elements.find(predicate);
    if (!element) throw new Error(`${label} did not render`);
    return element;
}

function hasSsoAvailability(value: unknown): value is {
    ssoAvailability: "enabled" | "disabled" | "unavailable";
} {
    return typeof value === "object"
        && value !== null
        && "ssoAvailability" in value
        && (value.ssoAvailability === "enabled"
            || value.ssoAvailability === "disabled"
            || value.ssoAvailability === "unavailable");
}

/**
 * Pins the sign-in route's root shape: a fragment holding the signed-out storage sweep, the
 * account bridge, and the credential form, in that order. Reading the form's props through the
 * pinned positions keeps the capability assertions from silently passing on a page that stopped
 * rendering the form the visitor actually authenticates with.
 * @param rendered the element the route's server component returned
 * @returns the capability props the credential form received
 */
function loginCapabilityProps(rendered: unknown): {
    ssoAvailability: "enabled" | "disabled" | "unavailable";
} {
    if (!isValidElement<{ children?: unknown }>(rendered) || rendered.type !== Fragment) {
        throw new Error("Login did not render a fragment root");
    }
    const children = rendered.props.children;
    if (!Array.isArray(children) || children.length !== 3) {
        throw new Error("Login did not render the expected root children");
    }
    const [sweep, bridge, form] = children;
    if (sweep !== null || bridge !== null) {
        throw new Error("Login rendered browser-storage children for an unresolved session");
    }
    if (!isValidElement(form) || form.type !== AuthForm || !hasSsoAvailability(form.props)) {
        throw new Error("Login did not render the expected capability contract");
    }
    return form.props;
}

afterEach(() => {
    vi.unstubAllGlobals();
    vi.clearAllMocks();
    passkeySupportState.supported = false;
});

describe("login capability honesty", () => {
    it("keeps password submission enabled and operational while SSO availability is unavailable", async () => {
        stubCapabilities(null);

        const rendered = await LoginPage({ searchParams: Promise.resolve({}) });
        const loginProps = loginCapabilityProps(rendered);
        const html = renderToStaticMarkup(rendered);

        expect(loginProps.ssoAvailability).toBe("unavailable");
        expect(html).toContain("login-username");
        expect(html).toContain("login-password");
        expect(html).toContain("CapabilityUnavailable.title");
        expect(html).toContain("CapabilityUnavailable.retry");

        const interactive = installInteractiveDocument("connex_workspace=7");
        const { createRoot } = await import("react-dom/client");
        const root = createRoot(interactive.container);
        await act(async () => {
            root.render(rendered);
        });

        const form = requiredElement(
            interactive.elements,
            (element) => element.tagName === "FORM",
            "Login form",
        );
        const submit = requiredElement(
            interactive.elements,
            (element) => element.tagName === "BUTTON" && element.type === "submit",
            "Password submit button",
        );

        expect(submit.disabled).not.toBe(true);
        await act(async () => {
            interactive.dispatch("submit", form);
        });

        expect(loginMock).toHaveBeenCalledWith({
            username: "",
            password: "",
        });
        expect(routerReplaceMock).toHaveBeenCalledWith("/dashboard");
        await act(async () => root.unmount());
    });

    it("keeps passkey login visible and operational when the browser supports it during an SSO lookup failure", async () => {
        passkeySupportState.supported = true;
        stubCapabilities(null);

        const rendered = await LoginPage({ searchParams: Promise.resolve({}) });
        const loginProps = loginCapabilityProps(rendered);
        const interactive = installInteractiveDocument("connex_workspace=7");
        const { createRoot } = await import("react-dom/client");
        const root = createRoot(interactive.container);
        await act(async () => {
            root.render(rendered);
        });

        const passkey = requiredElement(
            interactive.elements,
            (element) => element.tagName === "BUTTON"
                && element.textContent.includes("AuthLogin.passkeyButton"),
            "Passkey login button",
        );

        expect(loginProps.ssoAvailability).toBe("unavailable");
        expect(passkey.disabled).not.toBe(true);
        await act(async () => {
            interactive.dispatch("click", passkey);
        });

        expect(beginPasskeyAuthenticationMock).toHaveBeenCalledOnce();
        expect(startAuthenticationMock).toHaveBeenCalledOnce();
        expect(finishPasskeyAuthenticationMock).toHaveBeenCalledOnce();
        expect(routerReplaceMock).toHaveBeenCalledWith("/dashboard");
        await act(async () => root.unmount());
    });

    it("keeps a resolved disabled SSO capability distinct from lookup failure", async () => {
        stubCapabilities(DISABLED_CAPABILITIES);

        const rendered = await LoginPage({ searchParams: Promise.resolve({}) });
        const loginProps = loginCapabilityProps(rendered);
        const html = renderToStaticMarkup(rendered);

        expect(loginProps.ssoAvailability).toBe("disabled");
        expect(html).toContain("login-password");
        expect(html).not.toContain("CapabilityUnavailable.title");
        expect(html).not.toContain("AuthLogin.ssoButton");
    });
});
