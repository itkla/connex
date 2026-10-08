import { act, type ReactNode } from "react";
import { NextIntlClientProvider } from "next-intl";
import { afterEach, describe, expect, it, vi } from "vitest";

import PasskeyApprovalDialog from "@/app/components/account/PasskeyApprovalDialog";
import { ApiError, MFA_ATTESTATION_REFUSED_CODE } from "@/app/lib/api";
import enAccount from "@/messages/en/account.json";
import enErrors from "@/messages/en/errors.json";
import jaAccount from "@/messages/ja/account.json";
import jaErrors from "@/messages/ja/errors.json";
import {
    installInteractiveDocument,
    type InteractiveElement,
} from "@/test/unit/helpers/interactiveDocument";

const CREDENTIAL = { id: "credential-id", type: "public-key" };

const {
    beginMfaAttestationMock,
    completePrivilegedMfaEnrollmentMock,
    redeemMfaAttestationMock,
    startAuthenticationMock,
    toastErrorMock,
    toastInfoMock,
} = vi.hoisted(() => ({
    beginMfaAttestationMock: vi.fn<() => Promise<unknown>>(),
    completePrivilegedMfaEnrollmentMock: vi.fn(),
    redeemMfaAttestationMock: vi.fn<(code: string, credential: unknown) => Promise<{ orgId: number }>>(),
    startAuthenticationMock: vi.fn<() => Promise<unknown>>(),
    toastErrorMock: vi.fn(),
    toastInfoMock: vi.fn(),
}));

vi.mock("@/app/lib/api", async (importOriginal) => ({
    ...await importOriginal<typeof import("@/app/lib/api")>(),
    beginMfaAttestation: beginMfaAttestationMock,
    completePrivilegedMfaEnrollment: completePrivilegedMfaEnrollmentMock,
    redeemMfaAttestation: redeemMfaAttestationMock,
}));

vi.mock("@simplewebauthn/browser", async (importOriginal) => ({
    ...await importOriginal<typeof import("@simplewebauthn/browser")>(),
    startAuthentication: startAuthenticationMock,
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
])("redeeming a passkey approval code in $locale", ({ locale, messages }) => {
    async function redeemWith(code: string) {
        const onApproved = vi.fn();
        const onOpenChange = vi.fn();
        const interactive = installInteractiveDocument();
        const { createRoot } = await import("react-dom/client");
        const root = createRoot(interactive.container);
        await act(async () => {
            root.render(
                <NextIntlClientProvider locale={locale} messages={messages} timeZone="UTC">
                    <PasskeyApprovalDialog open onOpenChange={onOpenChange} onApproved={onApproved} />
                </NextIntlClientProvider>,
            );
        });
        const input = interactive.elements.find(
            (element) => element.tagName === "INPUT" && element.id === "passkey-approval-code",
        );
        if (!input) throw new Error("The code input did not render");
        await act(async () => change(input, code));
        const form = interactive.elements.find((element) => element.tagName === "FORM");
        if (!form) throw new Error("The code form did not render");
        await act(async () => interactive.dispatch("submit", form));

        const alert = interactive.elements.find((element) => element.getAttribute("role") === "alert"
            && isConnected(element));
        const result = { onApproved, onOpenChange, alertText: alert?.textContent ?? null, input };
        await act(async () => root.unmount());
        return result;
    }

    it("approves the passkey that signs, and hands back the organization", async () => {
        beginMfaAttestationMock.mockResolvedValueOnce({ challenge: "challenge" });
        startAuthenticationMock.mockResolvedValueOnce(CREDENTIAL);
        redeemMfaAttestationMock.mockResolvedValueOnce({ orgId: 3 });

        const { onApproved, onOpenChange, alertText } = await redeemWith("abcd efgh jkmn pqrs");

        expect(redeemMfaAttestationMock).toHaveBeenCalledWith("ABCD-EFGH-JKMN-PQRS", CREDENTIAL);
        expect(completePrivilegedMfaEnrollmentMock).toHaveBeenCalledOnce();
        expect(onApproved).toHaveBeenCalledWith(3);
        expect(onOpenChange).toHaveBeenCalledWith(false);
        expect(alertText).toBeNull();
        expect(toastErrorMock).not.toHaveBeenCalled();
    });

    it("answers every refused code with the same inline message and no toast", async () => {
        beginMfaAttestationMock.mockResolvedValueOnce({ challenge: "challenge" });
        startAuthenticationMock.mockResolvedValueOnce(CREDENTIAL);
        redeemMfaAttestationMock.mockRejectedValueOnce(
            new ApiError("That attestation code cannot be used", 403, MFA_ATTESTATION_REFUSED_CODE),
        );

        const { onApproved, alertText, input } = await redeemWith("ABCD-EFGH-JKMN-PQRS");

        expect(alertText).toBe(messages.AccountSecurity.approvalRefused);
        expect(input.getAttribute("aria-invalid")).toBe("true");
        expect(completePrivilegedMfaEnrollmentMock).not.toHaveBeenCalled();
        expect(onApproved).not.toHaveBeenCalled();
        expect(toastErrorMock).not.toHaveBeenCalled();
    });

    it("treats a dismissed passkey prompt as a quiet cancellation", async () => {
        beginMfaAttestationMock.mockResolvedValueOnce({ challenge: "challenge" });
        startAuthenticationMock.mockRejectedValueOnce(
            Object.assign(new Error("The operation was not allowed"), { name: "NotAllowedError" }),
        );

        const { onApproved, alertText } = await redeemWith("ABCD-EFGH-JKMN-PQRS");

        expect(toastInfoMock).toHaveBeenCalledWith(messages.AccountSecurity.stepUpCanceled);
        expect(redeemMfaAttestationMock).not.toHaveBeenCalled();
        expect(onApproved).not.toHaveBeenCalled();
        expect(alertText).toBeNull();
        expect(toastErrorMock).not.toHaveBeenCalled();
    });

    it("reports a passkey the server could not verify as a failed check, not an ended session", async () => {
        beginMfaAttestationMock.mockResolvedValueOnce({ challenge: "challenge" });
        startAuthenticationMock.mockResolvedValueOnce(CREDENTIAL);
        redeemMfaAttestationMock.mockRejectedValueOnce(new ApiError("Authentication failed", 401));

        const { onApproved, onOpenChange, alertText } = await redeemWith("ABCD-EFGH-JKMN-PQRS");

        expect(toastErrorMock).toHaveBeenCalledOnce();
        expect(toastErrorMock).toHaveBeenCalledWith(messages.PasskeyStepUp.failed);
        expect(completePrivilegedMfaEnrollmentMock).not.toHaveBeenCalled();
        expect(onApproved).not.toHaveBeenCalled();
        expect(onOpenChange).not.toHaveBeenCalled();
        expect(alertText).toBeNull();
    });

    it("reports any other failure in the shared error voice", async () => {
        beginMfaAttestationMock.mockRejectedValueOnce(new ApiError("unavailable", 503));

        const { alertText } = await redeemWith("ABCD-EFGH-JKMN-PQRS");

        expect(toastErrorMock).toHaveBeenCalledOnce();
        expect(toastErrorMock.mock.calls[0][0]).toBe(messages.AccountSecurity.approvalFailed);
        expect(alertText).toBeNull();
    });

    it("waits for a whole code before asking for a passkey", async () => {
        const { onApproved } = await redeemWith("ABCD-EFGH-JKMN");

        expect(beginMfaAttestationMock).not.toHaveBeenCalled();
        expect(startAuthenticationMock).not.toHaveBeenCalled();
        expect(onApproved).not.toHaveBeenCalled();
    });
});

function isConnected(element: InteractiveElement): boolean {
    let current: InteractiveElement | null = element;
    while (current !== null) {
        if (current.tagName === "BODY") return true;
        current = current.parentNode;
    }
    return false;
}

function change(input: InteractiveElement, value: string): void {
    input.value = value;
    const reactPropsKey = Reflect.ownKeys(input).find((key) => (
        typeof key === "string" && key.startsWith("__reactProps$")
    ));
    const reactProps: unknown = reactPropsKey === undefined ? null : Reflect.get(input, reactPropsKey);
    if (
        typeof reactProps !== "object"
        || reactProps === null
        || !("onChange" in reactProps)
        || typeof reactProps.onChange !== "function"
    ) {
        throw new Error("Expected a React change handler");
    }
    Reflect.apply(reactProps.onChange, undefined, [{ target: { value } }]);
}
