import { readFileSync } from "node:fs";
import { act, type AnchorHTMLAttributes, type PropsWithChildren } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { AuthForm } from "@/app/components/AuthForm";
import {
    installInteractiveDocument,
    type InteractiveElement,
} from "@/test/unit/helpers/interactiveDocument";

const {
    loginMock,
    registerMock,
    toastErrorMock,
    toastSuccessMock,
} = vi.hoisted(() => ({
    loginMock: vi.fn(async () => {}),
    registerMock: vi.fn(async () => {}),
    toastErrorMock: vi.fn(),
    toastSuccessMock: vi.fn(),
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
    useRouter: () => ({
        push: vi.fn(),
        refresh: vi.fn(),
        replace: vi.fn(),
    }),
}));

vi.mock("next-intl", () => ({
    useTranslations: (namespace: string) => (key: string, values?: Record<string, unknown>) =>
        values === undefined
            ? `${namespace}.${key}`
            : `${namespace}.${key}(${JSON.stringify(values)})`,
}));

vi.mock("@/app/lib/api", async (importOriginal) => {
    const actual = await importOriginal<typeof import("@/app/lib/api")>();
    return {
        ...actual,
        login: loginMock,
        register: registerMock,
    };
});

vi.mock("@/app/lib/toast", async (importOriginal) => {
    const actual = await importOriginal<typeof import("@/app/lib/toast")>();
    return {
        ...actual,
        toastError: toastErrorMock,
        toastSuccess: toastSuccessMock,
    };
});

vi.mock("@/app/hooks/usePasskeySupport", () => ({
    usePasskeySupport: () => false,
}));

vi.mock("@/app/components/auth/AuthBrandPanel", () => ({
    default: () => null,
}));

const RAW_BACKEND_TEXT = "Request failed (502) — upstream proxy returned text/html";

type Rendered = {
    elements: readonly InteractiveElement[];
    unmount: () => Promise<void>;
};

async function renderAuthForm(mode: "login" | "register"): Promise<Rendered> {
    const interactive = installInteractiveDocument("connex_workspace=7");
    const { createRoot } = await import("react-dom/client");
    const root = createRoot(interactive.container);
    await act(async () => {
        root.render(<AuthForm mode={mode} redirectUrl={null} />);
    });

    const form = interactive.elements.find((element) => element.tagName === "FORM");
    if (!form) throw new Error("Auth form did not render");

    await act(async () => {
        interactive.dispatch("submit", form);
    });

    return {
        elements: interactive.elements,
        unmount: async () => {
            await act(async () => root.unmount());
        },
    };
}

function alertText(elements: readonly InteractiveElement[]): string {
    return elements
        .filter((element) => element.attributes.get("role") === "alert")
        .map((element) => element.textContent)
        .join(" ");
}

function toastedText(): string {
    return toastErrorMock.mock.calls.map((call) => String(call[0])).join(" ");
}

function catalog(locale: "en" | "ja"): Record<string, Record<string, string>> {
    return JSON.parse(readFileSync(`messages/${locale}/auth.json`, "utf8")) as Record<
        string,
        Record<string, string>
    >;
}

afterEach(() => {
    vi.unstubAllGlobals();
    vi.clearAllMocks();
});

describe("auth form failure copy", () => {
    it("reports an unmapped status as product copy instead of the request's own text", async () => {
        const { ApiError } = await import("@/app/lib/api");
        loginMock.mockRejectedValueOnce(new ApiError(RAW_BACKEND_TEXT, 502));

        const rendered = await renderAuthForm("login");

        expect(alertText(rendered.elements)).toContain("AuthForm.genericError");
        expect(alertText(rendered.elements)).not.toContain(RAW_BACKEND_TEXT);
        expect(toastedText()).toBe("AuthForm.genericError");
        await rendered.unmount();
    });

    it("names a rate-limited attempt rather than falling back to the generic failure", async () => {
        const { ApiError } = await import("@/app/lib/api");
        loginMock.mockRejectedValueOnce(new ApiError("Too many requests", 429));

        const rendered = await renderAuthForm("login");

        expect(alertText(rendered.elements)).toContain("AuthForm.tooManyAttempts");
        expect(toastedText()).toBe("AuthForm.tooManyAttempts");
        await rendered.unmount();
    });

    it("carries no unmapped registration status into the copy the user reads", async () => {
        const { ApiError } = await import("@/app/lib/api");
        registerMock.mockRejectedValueOnce(new ApiError(RAW_BACKEND_TEXT, 503));

        const rendered = await renderAuthForm("register");

        expect(alertText(rendered.elements)).toContain("AuthForm.genericError");
        expect(alertText(rendered.elements)).not.toContain("502");
        expect(alertText(rendered.elements)).not.toContain("503");
        await rendered.unmount();
    });

    it("keeps every AuthForm failure key translated in both locales", () => {
        const en = catalog("en");
        const ja = catalog("ja");

        for (const key of ["genericError", "tooManyAttempts", "formHasErrors"]) {
            expect(en.AuthForm[key]).toBeTruthy();
            expect(ja.AuthForm[key]).toBeTruthy();
            expect(ja.AuthForm[key]).not.toBe(en.AuthForm[key]);
        }
    });
});

describe("auth form field errors", () => {
    it("announces the field's own text rather than a generic summary", async () => {
        const { ApiError } = await import("@/app/lib/api");
        loginMock.mockRejectedValueOnce(
            new ApiError(RAW_BACKEND_TEXT, 400, undefined, { password: "Password is too short" }),
        );

        const rendered = await renderAuthForm("login");

        expect(alertText(rendered.elements)).toContain("Password is too short");
        expect(alertText(rendered.elements)).not.toContain("AuthForm.formHasErrors");

        const fieldError = rendered.elements.find(
            (element) => element.attributes.get("id") === "login-password-error",
        );
        expect(fieldError?.textContent).toBe("Password is too short");
        expect(fieldError?.attributes.get("role")).toBe("alert");

        const passwordInput = rendered.elements.find(
            (element) => element.tagName === "INPUT" && element.attributes.get("id") === "login-password",
        );
        expect(passwordInput?.attributes.get("aria-invalid")).toBe("true");
        expect(passwordInput?.attributes.get("aria-describedby")).toBe("login-password-error");
        expect(toastErrorMock).not.toHaveBeenCalled();
        await rendered.unmount();
    });

    it("announces the breached-password guidance itself", async () => {
        const { ApiError } = await import("@/app/lib/api");
        registerMock.mockRejectedValueOnce(new ApiError(RAW_BACKEND_TEXT, 400, "BREACHED_PASSWORD"));

        const rendered = await renderAuthForm("register");

        expect(alertText(rendered.elements)).toContain("AuthForm.breachedPassword");
        expect(alertText(rendered.elements)).not.toContain("AuthForm.formHasErrors");
        expect(toastErrorMock).not.toHaveBeenCalled();
        await rendered.unmount();
    });
});

describe("registration next step", () => {
    it("points the new account at the inbox any confirmation link reaches", async () => {
        const rendered = await renderAuthForm("register");

        expect(toastSuccessMock).toHaveBeenCalledOnce();
        expect(toastSuccessMock).toHaveBeenCalledWith("AuthRegister.successMessage", {
            description: expect.stringContaining('AuthRegister.verificationNotice({"email":'),
        });
        await rendered.unmount();
    });

    it("leaves sign-in success without a registration-only next step", async () => {
        const rendered = await renderAuthForm("login");

        expect(toastSuccessMock).toHaveBeenCalledWith("AuthLogin.successMessage", undefined);
        await rendered.unmount();
    });

    it("names the recipient inbox in both locales", () => {
        const en = catalog("en");
        const ja = catalog("ja");

        expect(en.AuthRegister.verificationNotice).toContain("{email}");
        expect(ja.AuthRegister.verificationNotice).toContain("{email}");
        expect(ja.AuthRegister.verificationNotice).not.toBe(en.AuthRegister.verificationNotice);
    });
});
