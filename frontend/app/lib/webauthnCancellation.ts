import { WebAuthnError } from "@simplewebauthn/browser";

/**
 * Whether a passkey ceremony ended because the person dismissed the browser's passkey prompt. That
 * is a choice to acknowledge quietly, not a failure to report.
 *
 * @param error the rejection from a WebAuthn ceremony
 * @returns true when the browser reported the prompt as dismissed
 */
export function isWebAuthnCancellation(error: unknown): boolean {
    if (error instanceof WebAuthnError && error.cause instanceof Error && error.cause.name === "NotAllowedError") {
        return true;
    }
    return error instanceof Error && error.name === "NotAllowedError";
}
