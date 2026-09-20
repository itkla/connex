import { act, type AnchorHTMLAttributes, type PropsWithChildren } from "react";
import { NextIntlClientProvider } from "next-intl";
import { afterEach, describe, expect, it, vi } from "vitest";

import { VerifyEmailForm } from "@/app/auth/verify-email/VerifyEmailForm";
import type { EmailChangeConfirmation, RevokedInvitation } from "@/app/lib/types";
import enAuth from "@/messages/en/auth.json";
import jaAuth from "@/messages/ja/auth.json";
import { installInteractiveDocument } from "@/test/unit/helpers/interactiveDocument";

const { confirmEmailChangeMock, validateEmailChangeTokenMock } = vi.hoisted(() => ({
    confirmEmailChangeMock: vi.fn<() => Promise<EmailChangeConfirmation>>(),
    validateEmailChangeTokenMock: vi.fn(async () => ({ valid: true })),
}));

vi.mock("next/link", async () => {
    const React = await import("react");
    type LinkProps = PropsWithChildren<AnchorHTMLAttributes<HTMLAnchorElement> & { href: string }>;
    return {
        default: ({ children, href, ...props }: LinkProps) =>
            React.createElement("a", { ...props, href }, children),
    };
});

vi.mock("@/app/lib/api", async (importOriginal) => ({
    ...await importOriginal<typeof import("@/app/lib/api")>(),
    confirmEmailChange: confirmEmailChangeMock,
    validateEmailChangeToken: validateEmailChangeTokenMock,
}));

vi.mock("@/app/lib/oneTimeLink", () => ({
    takeOneTimeLinkToken: () => null,
    syncStrippedUrlWithRouter: () => {},
}));

vi.mock("@/app/components/auth/AuthBrandPanel", () => ({
    default: () => null,
}));

afterEach(() => {
    vi.unstubAllGlobals();
    vi.clearAllMocks();
});

describe.each([
    { locale: "en", messages: enAuth },
    { locale: "ja", messages: jaAuth },
])("email-change confirmation in $locale", ({ locale, messages }) => {
    async function confirm(revokedInvitations: RevokedInvitation[]) {
        confirmEmailChangeMock.mockResolvedValueOnce({ message: "Updated", revokedInvitations });
        const interactive = installInteractiveDocument();
        const { createRoot } = await import("react-dom/client");
        const root = createRoot(interactive.container);
        await act(async () => {
            root.render(
                <NextIntlClientProvider locale={locale} messages={messages} timeZone="UTC">
                    <VerifyEmailForm />
                </NextIntlClientProvider>,
            );
        });
        const button = interactive.elements.find((element) =>
            element.tagName === "BUTTON" && element.textContent === messages.AuthVerifyEmail.submitLabel,
        );
        if (!button) throw new Error("Email confirmation button did not render");
        expect(validateEmailChangeTokenMock).toHaveBeenCalledOnce();
        expect(interactive.container.textContent).not.toContain(messages.AuthVerifyEmail.revokedInvitationsTitle);

        await act(async () => interactive.dispatch("click", button));
        expect(confirmEmailChangeMock).toHaveBeenCalledOnce();

        return {
            ...interactive,
            unmount: async () => { await act(async () => root.unmount()); },
        };
    }

    it("names every revoked workspace and explains how to get invited again", async () => {
        const rendered = await confirm([
            { workspaceId: 11, orgId: 1, workspaceName: "North sales" },
            { workspaceId: 22, orgId: 2, workspaceName: "営業チーム" },
        ]);
        try {
            expect(rendered.container.textContent).toContain(messages.AuthVerifyEmail.successTitle);
            expect(rendered.container.textContent).toContain(messages.AuthVerifyEmail.successBody);
            expect(rendered.container.textContent).toContain(messages.AuthVerifyEmail.revokedInvitationsTitle);
            expect(rendered.container.textContent).toContain(messages.AuthVerifyEmail.revokedInvitationsBody);
            expect(rendered.elements.filter((element) => element.tagName === "LI")
                .map((element) => element.textContent)).toEqual(["North sales", "営業チーム"]);
            expect(rendered.elements.some((element) =>
                element.tagName === "A" && element.attributes.get("href") === "/dashboard",
            )).toBe(true);
        } finally {
            await rendered.unmount();
        }
    });

    it("keeps the ordinary success state when no invitations were revoked", async () => {
        const rendered = await confirm([]);
        try {
            expect(rendered.container.textContent).toContain(messages.AuthVerifyEmail.successTitle);
            expect(rendered.container.textContent).toContain(messages.AuthVerifyEmail.successBody);
            expect(rendered.container.textContent).not.toContain(messages.AuthVerifyEmail.revokedInvitationsTitle);
            expect(rendered.container.textContent).not.toContain(messages.AuthVerifyEmail.revokedInvitationsBody);
            expect(rendered.elements.filter((element) => element.tagName === "LI")).toHaveLength(0);
        } finally {
            await rendered.unmount();
        }
    });
});
