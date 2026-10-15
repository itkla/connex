// @vitest-environment jsdom
import { act, useEffect } from "react";
import { createRoot, type Root } from "react-dom/client";
import { NextIntlClientProvider } from "next-intl";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { ApiError, type PrivilegedMfaSettleOutcome } from "@/app/lib/api";
import type { PasskeyEnrollment, PasskeyEnrollmentRequirements } from "@/app/hooks/usePasskeyEnrollment";
import enAccount from "@/messages/en/account.json";

const mocks = vi.hoisted(() => ({
    begin: vi.fn<(password?: string) => Promise<unknown>>(),
    finish: vi.fn(async () => undefined),
    start: vi.fn(async () => ({ id: "credential" })),
    toastError: vi.fn(),
    toastSuccess: vi.fn(),
    toastInfo: vi.fn(),
}));

vi.mock("@/app/lib/api", async (importOriginal) => ({
    ...await importOriginal<typeof import("@/app/lib/api")>(),
    beginPasskeyRegistration: mocks.begin,
    finishPasskeyRegistration: mocks.finish,
}));
vi.mock("@simplewebauthn/browser", async (importOriginal) => ({
    ...await importOriginal<typeof import("@simplewebauthn/browser")>(),
    startRegistration: mocks.start,
}));
vi.mock("@/app/hooks/usePasskeyStepUpError", () => ({ usePasskeyStepUpErrorHandler: () => () => false }));
vi.mock("@/app/lib/toast", () => ({
    toastError: mocks.toastError,
    toastSuccess: mocks.toastSuccess,
    toastInfo: mocks.toastInfo,
}));

let container: HTMLDivElement;
let root: Root;
let latest: PasskeyEnrollment | null = null;

const firstPasskey: PasskeyEnrollmentRequirements = {
    hasPasskeys: false,
    currentPasswordRequired: true,
    emailConfirmationRequired: false,
    emailConfirmationSatisfied: false,
};

type EnrollmentCallbacks = { onEnrolled: () => Promise<PrivilegedMfaSettleOutcome>; onProofRejected: () => void };

async function mountEnrollment(
    requirements: PasskeyEnrollmentRequirements,
    callbacks: EnrollmentCallbacks = { onEnrolled: vi.fn(async () => "applied" as const), onProofRejected: vi.fn() },
): Promise<EnrollmentCallbacks> {
    const { usePasskeyEnrollment } = await import("@/app/hooks/usePasskeyEnrollment");
    function Probe() {
        const enrollment = usePasskeyEnrollment(requirements, callbacks);
        useEffect(() => {
            latest = enrollment;
        });
        return null;
    }
    await act(async () => root.render(
        <NextIntlClientProvider locale="en" messages={enAccount}>
            <Probe />
        </NextIntlClientProvider>,
    ));
    return callbacks;
}

function current(): PasskeyEnrollment {
    if (latest === null) throw new Error("usePasskeyEnrollment did not render");
    return latest;
}

beforeEach(() => {
    container = document.createElement("div");
    document.body.appendChild(container);
    root = createRoot(container);
    latest = null;
    mocks.begin.mockReset();
    mocks.begin.mockResolvedValue({ challenge: "challenge" });
});

afterEach(async () => {
    await act(async () => root.unmount());
    container.remove();
    vi.clearAllMocks();
});

describe("enrolling a passkey", () => {
    it("asks for the current password before a password-backed account's first passkey", async () => {
        await mountEnrollment(firstPasskey);

        await act(async () => current().addPasskey());

        expect(current().password.open).toBe(true);
        expect(mocks.begin).not.toHaveBeenCalled();
    });

    it("refuses to close the password step while the password is being confirmed", async () => {
        let release: (value: unknown) => void = () => undefined;
        mocks.begin.mockImplementationOnce(() => new Promise((resolve) => {
            release = resolve;
        }));
        await mountEnrollment(firstPasskey);
        await act(async () => current().addPasskey());
        await act(async () => current().password.setValue("correct horse"));

        let confirming: Promise<void> = Promise.resolve();
        await act(async () => {
            confirming = current().password.confirm();
        });
        expect(current().password.confirming).toBe(true);
        await act(async () => current().password.setOpen(false));
        expect(current().password.open).toBe(true);

        await act(async () => {
            release({ challenge: "challenge" });
            await confirming;
        });
        expect(mocks.begin).toHaveBeenCalledWith("correct horse");
        expect(current().password.open).toBe(false);
    });

    it("keeps the step open with an inline error for a wrong password", async () => {
        mocks.begin.mockRejectedValueOnce(new ApiError("Wrong password", 401));
        await mountEnrollment(firstPasskey);
        await act(async () => current().addPasskey());
        await act(async () => current().password.setValue("wrong"));

        await act(async () => current().password.confirm());

        expect(current().password.open).toBe(true);
        expect(current().password.error).toBe(enAccount.AccountSecurity.incorrectPassword);
        expect(current().password.value).toBe("");
    });

    it("re-reads what is required when the server refuses a first-passkey proof", async () => {
        mocks.begin.mockRejectedValueOnce(new ApiError("Refused", 403));
        const callbacks = await mountEnrollment({ ...firstPasskey, currentPasswordRequired: false });

        await act(async () => current().addPasskey());

        expect(callbacks.onProofRejected).toHaveBeenCalledOnce();
        expect(mocks.toastError).toHaveBeenCalledWith(enAccount.AccountSecurity.freshSignInRequired);
    });

    it("returns to the proofs when the server refuses the password step", async () => {
        mocks.begin.mockRejectedValueOnce(new ApiError("Refused", 403));
        const callbacks = await mountEnrollment(firstPasskey);
        await act(async () => current().addPasskey());
        await act(async () => current().password.setValue("correct horse"));

        await act(async () => current().password.confirm());

        expect(callbacks.onProofRejected).toHaveBeenCalledOnce();
        expect(current().password.open).toBe(false);
        expect(mocks.toastError).toHaveBeenCalledWith(enAccount.AccountSecurity.freshSignInRequired);
    });

    it("announces an accepted passkey even when the follow-up refresh fails", async () => {
        const callbacks = {
            onEnrolled: vi.fn(async () => {
                throw new Error("offline");
            }),
            onProofRejected: vi.fn(),
        };
        await mountEnrollment(firstPasskey, callbacks);
        await act(async () => current().addPasskey());
        await act(async () => current().password.setValue("correct horse"));

        await act(async () => current().password.confirm());

        expect(mocks.finish).toHaveBeenCalledOnce();
        expect(mocks.toastSuccess).toHaveBeenCalledWith(enAccount.AccountSecurity.added);
        expect(mocks.toastError).toHaveBeenCalledWith(enAccount.AccountSecurity.loadFailed);
        expect(mocks.toastError).not.toHaveBeenCalledWith(enAccount.AccountSecurity.addFailed);
        expect(current().password.open).toBe(false);
    });

    it("says the page could not update when only the standing read after enrollment failed", async () => {
        const callbacks = {
            onEnrolled: vi.fn(async () => "failed" as const),
            onProofRejected: vi.fn(),
        };
        await mountEnrollment({ ...firstPasskey, hasPasskeys: true }, callbacks);

        await act(async () => current().addPasskey());

        expect(mocks.finish).toHaveBeenCalledOnce();
        expect(mocks.toastSuccess).toHaveBeenCalledWith(enAccount.AccountSecurity.added);
        expect(mocks.toastError).toHaveBeenCalledWith(enAccount.AccountSecurity.addedRefreshFailed);
        expect(mocks.toastError).not.toHaveBeenCalledWith(enAccount.AccountSecurity.loadFailed);
    });

    it("adds nothing to the announcement when the standing read after enrollment settles", async () => {
        await mountEnrollment({ ...firstPasskey, hasPasskeys: true });

        await act(async () => current().addPasskey());

        expect(mocks.toastSuccess).toHaveBeenCalledWith(enAccount.AccountSecurity.added);
        expect(mocks.toastError).not.toHaveBeenCalled();
    });

    it("runs the caller's follow-up before announcing an accepted passkey", async () => {
        const order: string[] = [];
        const callbacks = {
            onEnrolled: vi.fn(async () => {
                order.push("enrolled");
                return "applied" as const;
            }),
            onProofRejected: vi.fn(),
        };
        mocks.toastSuccess.mockImplementation(() => order.push("toast"));
        await mountEnrollment({ ...firstPasskey, hasPasskeys: true }, callbacks);

        await act(async () => current().addPasskey());

        expect(mocks.finish).toHaveBeenCalledOnce();
        expect(order).toEqual(["enrolled", "toast"]);
    });
});
