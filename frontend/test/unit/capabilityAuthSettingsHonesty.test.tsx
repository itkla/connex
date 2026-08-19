import {
    act,
    createElement,
    isValidElement,
    type AnchorHTMLAttributes,
    type ComponentProps,
    type PropsWithChildren,
    type ReactElement,
    type ReactNode,
} from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { afterEach, describe, expect, it, vi } from "vitest";

import SettingsLayout from "@/app/(app)/settings/layout";
import EmailSettingsPage from "@/app/(app)/settings/email/page";
import LoginPage from "@/app/auth/login/page";
import { AuthForm } from "@/app/components/AuthForm";
import CapabilityUnavailablePage from "@/app/components/CapabilityUnavailablePage";
import EmailPanel from "@/app/components/settings/EmailPanel";
import WorkspaceSettingsChrome from "@/app/components/settings/WorkspaceSettingsChrome";
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

function hasChildren(value: unknown): value is { children?: ReactNode } {
    return typeof value === "object" && value !== null && "children" in value;
}

function findByType(node: ReactNode, type: unknown): ReactElement | null {
    if (Array.isArray(node)) {
        for (const child of node) {
            const found = findByType(child, type);
            if (found !== null) return found;
        }
        return null;
    }
    if (!isValidElement(node)) return null;
    if (node.type === type) return node;
    return hasChildren(node.props) ? findByType(node.props.children, type) : null;
}

function containsText(node: ReactNode, text: string): boolean {
    if (node === text) return true;
    if (Array.isArray(node)) return node.some((child) => containsText(child, text));
    if (!isValidElement(node) || !hasChildren(node.props)) return false;
    return containsText(node.props.children, text);
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

function hasMailManagementAvailability(value: unknown): value is {
    mailManagementAvailability: "enabled" | "disabled" | "unavailable";
} {
    return typeof value === "object"
        && value !== null
        && "mailManagementAvailability" in value
        && (value.mailManagementAvailability === "enabled"
            || value.mailManagementAvailability === "disabled"
            || value.mailManagementAvailability === "unavailable");
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
        if (!isValidElement(rendered) || !hasSsoAvailability(rendered.props)) {
            throw new Error("Login did not render the expected capability contract");
        }
        const html = renderToStaticMarkup(rendered);

        expect(rendered.type).toBe(AuthForm);
        expect(rendered.props.ssoAvailability).toBe("unavailable");
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
        if (!isValidElement(rendered) || !hasSsoAvailability(rendered.props)) {
            throw new Error("Login did not render the expected capability contract");
        }
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

        expect(rendered.props.ssoAvailability).toBe("unavailable");
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
        if (!isValidElement(rendered) || !hasSsoAvailability(rendered.props)) {
            throw new Error("Login did not render the expected capability contract");
        }
        const html = renderToStaticMarkup(rendered);

        expect(rendered.props.ssoAvailability).toBe("disabled");
        expect(html).toContain("login-password");
        expect(html).not.toContain("CapabilityUnavailable.title");
        expect(html).not.toContain("AuthLogin.ssoButton");
    });
});

describe("settings navigation capability honesty", () => {
    it("keeps unrelated settings content and marks the email tab unavailable after lookup failure", async () => {
        stubCapabilities(null);

        const rendered = await SettingsLayout({ children: createElement("p", null, "settings content") });
        const tabs = findByType(rendered, WorkspaceSettingsChrome);
        if (tabs === null || !hasMailManagementAvailability(tabs.props)) {
            throw new Error("Settings did not render the expected capability contract");
        }
        const html = renderToStaticMarkup(tabs);

        expect(tabs.props.mailManagementAvailability).toBe("unavailable");
        expect(containsText(rendered, "settings content")).toBe(true);
        expect(html).toContain("/settings/email");
        expect(html).toContain("CapabilityUnavailable.title");
    });

    it("shows the ordinary email tab without an unavailable marker when managed mail resolves false", async () => {
        stubCapabilities(DISABLED_CAPABILITIES);

        const rendered = await SettingsLayout({ children: createElement("p", null, "settings content") });
        const tabs = findByType(rendered, WorkspaceSettingsChrome);
        if (tabs === null || !hasMailManagementAvailability(tabs.props)) {
            throw new Error("Settings did not render the expected capability contract");
        }
        const html = renderToStaticMarkup(tabs);

        expect(tabs.props.mailManagementAvailability).toBe("disabled");
        expect(html).toContain("/settings/email");
        expect(html).not.toContain("CapabilityUnavailable.title");
    });
});

describe("email settings route capability honesty", () => {
    it("renders the retryable capability page when managed-mail availability cannot be checked", async () => {
        stubCapabilities(null);

        const rendered = await EmailSettingsPage();

        expect(isValidElement(rendered) ? rendered.type : null).toBe(CapabilityUnavailablePage);
        expect(redirectMock).not.toHaveBeenCalled();
    });

    it("renders self-managed email settings when managed mail resolves false", async () => {
        stubCapabilities(DISABLED_CAPABILITIES);

        const rendered = await EmailSettingsPage();

        expect(isValidElement(rendered) ? rendered.type : null).toBe(EmailPanel);
        expect(redirectMock).not.toHaveBeenCalled();
    });
});
