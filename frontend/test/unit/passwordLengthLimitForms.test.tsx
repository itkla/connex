import { act, Fragment, type AnchorHTMLAttributes, type PropsWithChildren, type ReactNode } from "react";
import { NextIntlClientProvider } from "next-intl";
import { afterEach, describe, expect, it, vi } from "vitest";

import { ResetPasswordForm } from "@/app/auth/reset-password/ResetPasswordForm";
import { AuthForm } from "@/app/components/AuthForm";
import NewUserDialog from "@/app/components/records/users/NewUserDialog";
import { ApiError } from "@/app/lib/api";
import enAuth from "@/messages/en/auth.json";
import enUsers from "@/messages/en/users.json";
import jaAuth from "@/messages/ja/auth.json";
import jaUsers from "@/messages/ja/users.json";
import {
    installInteractiveDocument,
    type InteractiveElement,
} from "@/test/unit/helpers/interactiveDocument";

const { createUserMock, resetPasswordMock, validateResetTokenMock, registerMock } = vi.hoisted(() => ({
    registerMock: vi.fn<() => Promise<void>>(),
    createUserMock: vi.fn<() => Promise<void>>(),
    resetPasswordMock: vi.fn<() => Promise<void>>(),
    validateResetTokenMock: vi.fn(async () => ({ valid: true })),
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
    useRouter: () => ({ push: vi.fn(), refresh: vi.fn(), replace: vi.fn() }),
}));

vi.mock("@/app/lib/api", async (importOriginal) => ({
    ...await importOriginal<typeof import("@/app/lib/api")>(),
    createUser: createUserMock,
    register: registerMock,
    logout: vi.fn(async () => {}),
    resetPassword: resetPasswordMock,
    validateResetToken: validateResetTokenMock,
}));

vi.mock("@/app/lib/oneTimeLink", () => ({
    takeOneTimeLinkToken: () => null,
    syncStrippedUrlWithRouter: () => {},
}));

vi.mock("@/app/lib/toast", async (importOriginal) => ({
    ...await importOriginal<typeof import("@/app/lib/toast")>(),
    toastError: vi.fn(),
    toastSuccess: vi.fn(),
}));

vi.mock("@/app/hooks/useApiErrorToast", () => ({
    useApiErrorToast: () => vi.fn(),
}));

vi.mock("@/app/hooks/usePasskeyStepUpError", () => ({
    usePasskeyStepUpErrorHandler: () => () => false,
}));

vi.mock("@/app/components/auth/AuthBrandPanel", () => ({
    default: () => null,
}));

vi.mock("@/components/ui/responsive-dialog", async () => {
    const React = await import("react");
    const Passthrough = ({ children }: { children?: ReactNode }) => React.createElement(Fragment, null, children);
    return {
        ResponsiveDialog: Passthrough,
        ResponsiveDialogClose: Passthrough,
        ResponsiveDialogContent: Passthrough,
        ResponsiveDialogDescription: Passthrough,
        ResponsiveDialogFooter: Passthrough,
        ResponsiveDialogHeader: Passthrough,
        ResponsiveDialogTitle: Passthrough,
        ResponsiveDialogTrigger: Passthrough,
    };
});

vi.mock("@/components/ui/dialog-status-cover", async (importOriginal) => ({
    ...await importOriginal<typeof import("@/components/ui/dialog-status-cover")>(),
    DialogStatusCover: () => null,
}));

vi.mock("@/app/hooks/usePasskeySupport", () => ({ usePasskeySupport: () => false }));

afterEach(() => {
    vi.unstubAllGlobals();
    vi.clearAllMocks();
});


async function fillInput(elements: readonly InteractiveElement[], id: string, value: string) {
    const input = elements.find((element) => element.attributes.get("id") === id && element.parentNode !== null);
    if (!input) throw new Error(`Missing input ${id}`);
    await act(async () => {
        const key = Reflect.ownKeys(input).find((key) => typeof key === "string" && key.startsWith("__reactProps$"));
        const props: unknown = key === undefined ? null : Reflect.get(input, key);
        if (typeof props !== "object" || props === null || !("onChange" in props) || typeof props.onChange !== "function") {
            throw new Error("Missing change handler");
        }
        Reflect.apply(props.onChange, undefined, [{ target: { value } }]);
    });
}

function byId(elements: readonly InteractiveElement[], id: string): InteractiveElement | undefined {
    return elements.find((element) => element.attributes.get("id") === id && element.parentNode !== null);
}

async function render(ui: ReactNode) {
    const interactive = installInteractiveDocument();
    const { createRoot } = await import("react-dom/client");
    const root = createRoot(interactive.container);
    await act(async () => {
        root.render(ui);
    });
    return {
        ...interactive,
        fill: (id: string, value: string) => fillInput(interactive.elements, id, value),
        submit: async () => {
            const form = interactive.elements.find(
                (element) => element.tagName === "FORM" && element.parentNode !== null,
            );
            if (!form) throw new Error("Form did not render");
            await act(async () => {
                interactive.dispatch("submit", form);
            });
        },
        unmount: async () => {
            await act(async () => root.unmount());
        },
    };
}

describe.each([
    { locale: "en", messages: enAuth },
    { locale: "ja", messages: jaAuth },
])("password reset length limit in $locale", ({ locale, messages }) => {
    function renderReset() {
        return render(
            <NextIntlClientProvider locale={locale} messages={messages} timeZone="UTC">
                <ResetPasswordForm />
            </NextIntlClientProvider>,
        );
    }

    it("caps both password fields at 72 characters and states the range", async () => {
        const rendered = await renderReset();
        try {
            expect(byId(rendered.elements, "reset-password")?.attributes.get("maxLength")).toBe("72");
            expect(byId(rendered.elements, "reset-confirm")?.attributes.get("maxLength")).toBe("72");
            expect(byId(rendered.elements, "reset-password")?.attributes.get("aria-describedby"))
                .toBe("reset-password-hint");
            expect(byId(rendered.elements, "reset-password-hint")?.textContent)
                .toContain(messages.PasswordPolicy.specialCharacter);
        } finally {
            await rendered.unmount();
        }
    });

    it("shows an over-long password refusal on the new-password field in place of the hint", async () => {
        resetPasswordMock.mockRejectedValueOnce(new ApiError("Request failed", 400, "PASSWORD_TOO_LONG"));
        const rendered = await renderReset();
        try {
            await rendered.fill("reset-password", "Aa12345#");
            await rendered.fill("reset-confirm", "Aa12345#");
            await rendered.submit();

            expect(resetPasswordMock).toHaveBeenCalledOnce();
            expect(byId(rendered.elements, "reset-password-error")?.textContent)
                .toBe(messages.AuthResetPassword.passwordTooLong);
            expect(byId(rendered.elements, "reset-password")?.attributes.get("aria-describedby"))
                .toBe("reset-password-error reset-password-hint");
            expect(byId(rendered.elements, "reset-password-hint")).toBeDefined();
            expect(rendered.container.textContent).toContain(messages.AuthResetPassword.title);
        } finally {
            await rendered.unmount();
        }
    });
});

describe.each([
    { locale: "en", messages: enUsers },
    { locale: "ja", messages: jaUsers },
])("new user password length limit in $locale", ({ locale, messages }) => {
    function renderDialog() {
        return render(
            <NextIntlClientProvider locale={locale} messages={{ ...messages, ...(locale === "en" ? enAuth : jaAuth) }} timeZone="UTC">
                <NewUserDialog />
            </NextIntlClientProvider>,
        );
    }

    it("caps the password at 72 characters and states the range", async () => {
        const rendered = await renderDialog();
        try {
            const password = byId(rendered.elements, "password");
            expect(password?.attributes.get("maxLength")).toBe("72");
            expect(password?.attributes.get("placeholder")).toBe(messages.UsersNewUserDialog.passwordPlaceholder);
            expect(messages.UsersNewUserDialog.passwordPlaceholder).toContain("72");
        } finally {
            await rendered.unmount();
        }
    });

    it("shows an over-long password refusal on the password field", async () => {
        createUserMock.mockRejectedValueOnce(new ApiError("Request failed", 400, "PASSWORD_TOO_LONG"));
        const rendered = await renderDialog();
        try {
            await rendered.fill("password", "Aa12345#");
            await rendered.submit();

            expect(createUserMock).toHaveBeenCalledOnce();
            expect(byId(rendered.elements, "password-error")?.textContent)
                .toBe(messages.UsersNewUserDialog.passwordTooLong);
            expect(byId(rendered.elements, "password")?.attributes.get("aria-invalid")).toBe("true");
        } finally {
            await rendered.unmount();
        }
    });
});

const symbols = [...Array.from({ length: 95 }, (_, index) => String.fromCharCode(index + 32))
    .filter((symbol) => !/[a-zA-Z0-9]/.test(symbol)), "£", "€", "。", "★", "　"];

for (const locale of ["en", "ja"] as const) {
    const authMessages = locale === "en" ? enAuth : jaAuth;
    const messages = { ...authMessages, ...(locale === "en" ? enUsers : jaUsers) };
    describe.each(["register", "reset", "create"] as const)(`%s password policy in ${locale}`, (mode) => {
        async function form() {
            return render(
                <NextIntlClientProvider locale={locale} messages={messages} timeZone="UTC">
                    {mode === "register" ? <AuthForm mode="register" redirectUrl={null} />
                        : mode === "reset" ? <ResetPasswordForm /> : <NewUserDialog />}
                </NextIntlClientProvider>,
            );
        }
        const inputId = mode === "register" ? "register-password" : mode === "reset" ? "reset-password" : "password";
        const request = mode === "register" ? registerMock : mode === "reset" ? resetPasswordMock : createUserMock;

        it.each(symbols)("submits %j unchanged as the sole special character", async (symbol) => {
            request.mockRejectedValueOnce(new ApiError("Request failed", 503, "BREACHED_PASSWORD_CHECK_UNAVAILABLE"));
            const rendered = await form();
            try {
                const password = `Aa12345${symbol}`;
                expect(rendered.container.textContent).toContain(authMessages.PasswordPolicy.specialCharacter);
                await rendered.fill(inputId, password);
                if (mode === "reset") await rendered.fill("reset-confirm", password);
                await rendered.submit();
                expect(request).toHaveBeenCalledOnce();
                expect(request).toHaveBeenCalledWith(expect.objectContaining(
                    mode === "reset" ? { newPassword: password } : { password },
                ));
            } finally {
                await rendered.unmount();
            }
        });

        it.each([
            ["abcdef1#", "uppercase"], ["ABCDEF1#", "lowercase"], ["Abcdefg#", "number"],
            ["Abcdefg1", "specialCharacter"], ["Aa1#abc", "length"], ["Aa12345\t", "printable"],
            [`Aa1#${"€".repeat(23)}`, "byteLength"],
        ])("refuses %j locally and names the missing requirement", async (password, key) => {
            const rendered = await form();
            try {
                await rendered.fill(inputId, password);
                if (mode === "reset") await rendered.fill("reset-confirm", password);
                await rendered.submit();
                expect(request).not.toHaveBeenCalled();
                expect(byId(rendered.elements, `${inputId}-error`)?.textContent)
                    .toBe(Reflect.get(authMessages.PasswordPolicy, key));
                const hintId = mode === "register" ? "register-password-hint"
                    : mode === "reset" ? "reset-password-hint" : "new-user-password-hint";
                expect(byId(rendered.elements, hintId)).toBeDefined();
            } finally {
                await rendered.unmount();
            }
        });
    });
}
