import { act, Fragment, type AnchorHTMLAttributes, type PropsWithChildren, type ReactNode } from "react";
import { NextIntlClientProvider } from "next-intl";
import { afterEach, describe, expect, it, vi } from "vitest";

import { ResetPasswordForm } from "@/app/auth/reset-password/ResetPasswordForm";
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

const { createUserMock, resetPasswordMock, validateResetTokenMock } = vi.hoisted(() => ({
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
    resetPassword: resetPasswordMock,
    validateResetToken: validateResetTokenMock,
}));

vi.mock("@/app/lib/oneTimeLink", () => ({
    takeOneTimeLinkToken: () => null,
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

afterEach(() => {
    vi.unstubAllGlobals();
    vi.clearAllMocks();
});

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
                .toBe(messages.AuthResetPassword.passwordLengthHint);
        } finally {
            await rendered.unmount();
        }
    });

    it("shows an over-long password refusal on the new-password field in place of the hint", async () => {
        resetPasswordMock.mockRejectedValueOnce(new ApiError("Request failed", 400, "PASSWORD_TOO_LONG"));
        const rendered = await renderReset();
        try {
            await rendered.submit();

            expect(resetPasswordMock).toHaveBeenCalledOnce();
            expect(byId(rendered.elements, "reset-password-error")?.textContent)
                .toBe(messages.AuthResetPassword.passwordTooLong);
            expect(byId(rendered.elements, "reset-password")?.attributes.get("aria-describedby"))
                .toBe("reset-password-error");
            expect(byId(rendered.elements, "reset-password-hint")).toBeUndefined();
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
            <NextIntlClientProvider locale={locale} messages={messages} timeZone="UTC">
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
