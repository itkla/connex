import { act, type ReactNode } from "react";
import { NextIntlClientProvider } from "next-intl";
import { afterEach, describe, expect, it, vi } from "vitest";

import ChangeEmailDialog from "@/app/components/account/ChangeEmailDialog";
import {
    ApiError,
    PASSKEY_ENROLLMENT_REQUIRED_CODE,
    PASSKEY_STEP_UP_CANCELED_CODE,
    PASSKEY_STEP_UP_FAILED_CODE,
} from "@/app/lib/api";
import enAccount from "@/messages/en/account.json";
import enErrors from "@/messages/en/errors.json";
import jaAccount from "@/messages/ja/account.json";
import jaErrors from "@/messages/ja/errors.json";
import { installInteractiveDocument } from "@/test/unit/helpers/interactiveDocument";

const { requestEmailChangeMock, toastErrorMock, toastInfoMock } = vi.hoisted(() => ({
    requestEmailChangeMock: vi.fn<() => Promise<unknown>>(),
    toastErrorMock: vi.fn(),
    toastInfoMock: vi.fn(),
}));

vi.mock("@/app/lib/api", async (importOriginal) => ({
    ...await importOriginal<typeof import("@/app/lib/api")>(),
    requestEmailChange: requestEmailChangeMock,
}));

vi.mock("@/app/lib/toast", () => ({
    toastError: toastErrorMock,
    toastInfo: toastInfoMock,
    toastSuccess: vi.fn(),
    toastWarn: vi.fn(),
    toastLoading: vi.fn(),
    toastDismiss: vi.fn(),
}));

vi.mock("@/components/ui/dialog", async () => {
    const React = await import("react");
    const Passthrough = ({ children }: { children?: ReactNode }) =>
        React.createElement(React.Fragment, null, children);
    return {
        Dialog: Passthrough,
        DialogClose: Passthrough,
        DialogContent: Passthrough,
        DialogDescription: Passthrough,
        DialogFooter: Passthrough,
        DialogHeader: Passthrough,
        DialogTitle: Passthrough,
    };
});

afterEach(() => {
    vi.unstubAllGlobals();
    vi.clearAllMocks();
});

describe.each([
    { locale: "en", messages: { ...enAccount, ...enErrors } },
    { locale: "ja", messages: { ...jaAccount, ...jaErrors } },
])("email-change step-up refusals in $locale", ({ locale, messages }) => {
    async function submitRejectedWith(error: unknown) {
        requestEmailChangeMock.mockRejectedValueOnce(error);
        const interactive = installInteractiveDocument();
        const { createRoot } = await import("react-dom/client");
        const root = createRoot(interactive.container);
        await act(async () => {
            root.render(
                <NextIntlClientProvider locale={locale} messages={messages} timeZone="UTC">
                    <ChangeEmailDialog open onOpenChange={() => undefined} />
                </NextIntlClientProvider>,
            );
        });
        const form = interactive.elements.find((element) => element.tagName === "FORM");
        if (!form) throw new Error("Email change form did not render");

        await act(async () => interactive.dispatch("submit", form));

        expect(requestEmailChangeMock).toHaveBeenCalledOnce();
        await act(async () => root.unmount());
    }

    it("tells a privileged account without a passkey to add one", async () => {
        await submitRejectedWith(new ApiError(
            "A passkey must be enrolled before this action can be completed",
            400,
            PASSKEY_ENROLLMENT_REQUIRED_CODE,
        ));

        expect(toastErrorMock).toHaveBeenCalledOnce();
        expect(toastErrorMock).toHaveBeenCalledWith(messages.PasskeyStepUp.enrollmentRequired);
        expect(toastInfoMock).not.toHaveBeenCalled();
    });

    it("reports a canceled passkey step-up as canceled", async () => {
        await submitRejectedWith(new ApiError("canceled", 403, PASSKEY_STEP_UP_CANCELED_CODE));

        expect(toastInfoMock).toHaveBeenCalledWith(messages.PasskeyStepUp.canceled);
        expect(toastErrorMock).not.toHaveBeenCalled();
    });

    it("reports a failed passkey step-up as failed", async () => {
        await submitRejectedWith(new ApiError("failed", 403, PASSKEY_STEP_UP_FAILED_CODE));

        expect(toastErrorMock).toHaveBeenCalledOnce();
        expect(toastErrorMock).toHaveBeenCalledWith(messages.PasskeyStepUp.failed);
    });

    it("keeps the generic failure for an unrelated error", async () => {
        await submitRejectedWith(new ApiError("unavailable", 503));

        expect(toastErrorMock).toHaveBeenCalledOnce();
        expect(toastErrorMock).not.toHaveBeenCalledWith(messages.PasskeyStepUp.enrollmentRequired);
        expect(toastErrorMock).not.toHaveBeenCalledWith(messages.PasskeyStepUp.failed);
        expect(toastInfoMock).not.toHaveBeenCalled();
    });
});
