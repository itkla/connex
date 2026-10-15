"use client";

import { useState } from "react";
import { useTranslations } from "next-intl";
import { startRegistration } from "@simplewebauthn/browser";

import {
    ApiError,
    beginPasskeyRegistration,
    finishPasskeyRegistration,
    MAIL_TRANSPORT_UNAVAILABLE_CODE,
    requestPasskeyBootstrapConfirmation,
    type PrivilegedMfaSettleOutcome,
} from "@/app/lib/api";
import { usePasskeyStepUpErrorHandler } from "@/app/hooks/usePasskeyStepUpError";
import { isWebAuthnCancellation } from "@/app/lib/webauthnCancellation";
import { toastError, toastInfo, toastSuccess } from "@/app/lib/toast";

/** The proofs a passkey enrollment needs, as the account's requirements report them. */
export type PasskeyEnrollmentRequirements = {
    /** Whether the account already holds a passkey, so the first-passkey proofs no longer apply. */
    hasPasskeys: boolean;
    currentPasswordRequired: boolean;
    emailConfirmationRequired: boolean;
    emailConfirmationSatisfied: boolean;
};

/** The current-password step a first passkey can require, for {@link PasskeyEnrollmentPasswordDialog}. */
export type PasskeyEnrollmentPassword = {
    open: boolean;
    value: string;
    error: string | null;
    confirming: boolean;
    setValue: (value: string) => void;
    /** Opens or closes the step; closing is refused while the password is being confirmed. */
    setOpen: (open: boolean) => void;
    confirm: () => Promise<void>;
};

/** Adding a passkey, with the first-passkey email and password proofs it may need. */
export type PasskeyEnrollment = {
    adding: boolean;
    sendingConfirmation: boolean;
    addPasskey: () => Promise<void>;
    sendConfirmation: () => Promise<void>;
    password: PasskeyEnrollmentPassword;
};

/**
 * Enrolls a passkey on any surface that may need to, including onboarding for an account that has
 * no workspace (#1534). A privileged, password-backed account's first passkey needs the emailed
 * confirmation and then its current password; the server checks both proofs again, so this only
 * sequences the ceremony. Once the server accepts a passkey, `onEnrolled` runs before the success
 * toast and resolves how its standing read settled. If that follow-up fails, the enrollment is still
 * announced, since the passkey exists, with a separate note that the list could not be refreshed;
 * if only the standing read failed, the note says the page could not update instead. A refusal of
 * the first-passkey proofs, with or without the password step, calls `onProofRejected` so the
 * caller can re-read what is required.
 * @param requirements what the account's requirements say the enrollment needs
 * @param callbacks what to do after an accepted enrollment and after a refused first-passkey proof
 * @returns the enrollment state and actions
 */
export function usePasskeyEnrollment(
    requirements: PasskeyEnrollmentRequirements,
    callbacks: { onEnrolled: () => Promise<PrivilegedMfaSettleOutcome>; onProofRejected: () => void },
): PasskeyEnrollment {
    const t = useTranslations("AccountSecurity");
    const handlePasskeyStepUpError = usePasskeyStepUpErrorHandler();
    const [adding, setAdding] = useState(false);
    const [sendingConfirmation, setSendingConfirmation] = useState(false);
    const [passwordOpen, setPasswordOpen] = useState(false);
    const [currentPassword, setCurrentPassword] = useState("");
    const [passwordError, setPasswordError] = useState<string | null>(null);
    const [confirmingPassword, setConfirmingPassword] = useState(false);
    const { hasPasskeys, currentPasswordRequired, emailConfirmationRequired, emailConfirmationSatisfied } = requirements;

    const finishRegistration = async (optionsJSON: Awaited<ReturnType<typeof beginPasskeyRegistration>>) => {
        const credential = await startRegistration({ optionsJSON });
        await finishPasskeyRegistration(deviceLabel(), credential);
        const standing = await callbacks.onEnrolled().catch(() => "unloaded" as const);
        toastSuccess(t("added"));
        if (standing === "unloaded") toastError(t("loadFailed"));
        else if (standing === "failed") {
            toastError(t("addedRefreshFailed"), { description: t("addedRefreshFailedDescription") });
        }
    };

    const sendConfirmation = async () => {
        if (sendingConfirmation) return;
        setSendingConfirmation(true);
        try {
            await requestPasskeyBootstrapConfirmation();
            toastSuccess(t("confirmationSent"));
        } catch (err) {
            toastError(
                err instanceof ApiError && err.code === MAIL_TRANSPORT_UNAVAILABLE_CODE
                    ? t("confirmationUnavailable")
                    : t("confirmationFailed"),
            );
        } finally {
            setSendingConfirmation(false);
        }
    };

    const addPasskey = async () => {
        if (!hasPasskeys && emailConfirmationRequired && !emailConfirmationSatisfied) {
            toastError(t("confirmationRequired"));
            return;
        }
        if (!hasPasskeys && currentPasswordRequired) {
            setPasswordError(null);
            setPasswordOpen(true);
            return;
        }
        setAdding(true);
        let registrationStarted = false;
        try {
            const optionsJSON = await beginPasskeyRegistration();
            registrationStarted = true;
            await finishRegistration(optionsJSON);
        } catch (err) {
            if (isWebAuthnCancellation(err)) {
                toastInfo(t(registrationStarted ? "canceled" : "stepUpCanceled"));
            } else if (handlePasskeyStepUpError(err)) {
                return;
            } else if (err instanceof ApiError && err.status === 403 && !hasPasskeys) {
                toastError(emailConfirmationRequired ? t("confirmationRequired") : t("freshSignInRequired"));
                callbacks.onProofRejected();
            } else {
                toastError(t("addFailed"));
                if (!hasPasskeys) {
                    callbacks.onProofRejected();
                }
            }
        } finally {
            setAdding(false);
        }
    };

    const confirmFirstPasskey = async () => {
        if (!currentPassword || confirmingPassword) return;
        setConfirmingPassword(true);
        setPasswordError(null);
        try {
            const optionsJSON = await beginPasskeyRegistration(currentPassword);
            setCurrentPassword("");
            await finishRegistration(optionsJSON);
            setPasswordOpen(false);
        } catch (err) {
            setCurrentPassword("");
            if (isWebAuthnCancellation(err)) {
                toastInfo(t("canceled"));
                setPasswordOpen(false);
            } else if (err instanceof ApiError && err.status === 401) {
                setPasswordError(t("incorrectPassword"));
            } else if (err instanceof ApiError && err.status === 403) {
                toastError(emailConfirmationRequired ? t("confirmationRequired") : t("freshSignInRequired"));
                setPasswordOpen(false);
                callbacks.onProofRejected();
            } else {
                toastError(t("addFailed"));
            }
        } finally {
            setConfirmingPassword(false);
        }
    };

    return {
        adding,
        sendingConfirmation,
        addPasskey,
        sendConfirmation,
        password: {
            open: passwordOpen,
            value: currentPassword,
            error: passwordError,
            confirming: confirmingPassword,
            setValue: (value) => {
                setCurrentPassword(value);
                setPasswordError(null);
            },
            setOpen: (open) => {
                if (confirmingPassword) return;
                setPasswordOpen(open);
                if (!open) {
                    setCurrentPassword("");
                    setPasswordError(null);
                }
            },
            confirm: confirmFirstPasskey,
        },
    };
}

/** A label naming the browser and system the passkey was created on, for the passkey list. */
function deviceLabel(): string {
    if (typeof navigator === "undefined") return "Passkey";
    const ua = navigator.userAgent;
    const os = /Windows/.test(ua)
        ? "Windows"
        : /iPhone|iPad|iPod/.test(ua)
          ? "iOS"
          : /Mac/.test(ua)
            ? "macOS"
            : /Android/.test(ua)
              ? "Android"
              : /Linux/.test(ua)
                ? "Linux"
                : null;
    const browser = /Edg\//.test(ua)
        ? "Edge"
        : /Firefox\//.test(ua)
          ? "Firefox"
          : /Chrome\//.test(ua)
            ? "Chrome"
            : /Safari\//.test(ua)
              ? "Safari"
              : null;
    if (browser && os) return `${browser} on ${os}`;
    return os ?? "Passkey";
}
