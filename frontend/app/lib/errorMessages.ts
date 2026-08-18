import { type useTranslations } from "next-intl";

import { ApiError } from "@/app/lib/api";
import { isSessionExpired, redirectToSignIn } from "@/app/lib/sessionExpiry";
import { toastError } from "@/app/lib/toast";

/**
 * A next-intl translator bound to the **message root** — `useTranslations()` with no namespace — so
 * one function resolves both this module's `ApiErrors.*` copy and a caller's namespaced fallback
 * key. Passing a namespaced translator would leave both unresolved; use `useApiErrorToast`, which
 * binds the right one for you.
 */
export type MessageTranslator = ReturnType<typeof useTranslations>;

/** Localized failure copy in the product's one error dialect: a short title, a full-sentence body. */
export type UserFacingMessage = {
    /** A short fragment with no trailing period, naming the operation that didn't happen. */
    title: string;
    /** Full sentences saying what happened, whether anything was lost, and the one next action. */
    description: string;
};

const GENERIC_TITLE_KEY = "ApiErrors.title";
const GENERIC_KEY = "ApiErrors.generic";
const INVALID_KEY = "ApiErrors.invalid";
const FORBIDDEN_KEY = "ApiErrors.forbidden";
const NOT_FOUND_KEY = "ApiErrors.notFound";
const CONFLICT_KEY = "ApiErrors.conflict";
const TOO_MANY_REQUESTS_KEY = "ApiErrors.tooManyRequests";
const OFFLINE_KEY = "ApiErrors.offline";
const SERVER_ERROR_KEY = "ApiErrors.serverError";
const SESSION_EXPIRED_KEY = "ApiErrors.sessionExpired";
const REFERENCE_KEY = "ApiErrors.reference";

/**
 * Stable backend error codes that carry a meaning no status alone conveys, mapped to their copy.
 * Seeded with the codes the backend emits today that no surface already owns; the workstream's
 * backend lane adds the rest behind this mapper, and an unknown code falls through to the status
 * rules rather than surfacing anything the backend wrote (#1337).
 */
const CODE_MESSAGE_KEYS = new Map<string, string>([
    ["IDENTITY_COLLISION_REPORT_TIMEOUT", "ApiErrors.identityCollisionReportTimeout"],
]);

function statusMessageKey(status: number): string {
    if (status === 400 || status === 422) return INVALID_KEY;
    if (status === 403) return FORBIDDEN_KEY;
    if (status === 404 || status === 410) return NOT_FOUND_KEY;
    if (status === 409) return CONFLICT_KEY;
    if (status === 429) return TOO_MANY_REQUESTS_KEY;
    if (status >= 500) return SERVER_ERROR_KEY;
    return GENERIC_KEY;
}

function descriptionFor(error: unknown, t: MessageTranslator): string {
    if (error instanceof TypeError) return t(OFFLINE_KEY);
    if (!(error instanceof ApiError)) return t(GENERIC_KEY);

    const codeKey = error.code === undefined ? undefined : CODE_MESSAGE_KEYS.get(error.code);
    const description = t(codeKey ?? statusMessageKey(error.status));
    if (!error.correlationId) return description;

    return `${description} ${t(REFERENCE_KEY, { id: error.correlationId })}`;
}

/**
 * Translates any rejected request into the copy the user should read. The error's own text is never
 * consulted: backend prose, browser-engine prose, and status codes are engineering artefacts, so the
 * message is selected from the error's *meaning* — its stable code first, then its status, then a
 * fail-safe generic — and an unrecognized failure still yields sound copy.
 *
 * A lost session is not a failure to report: it sends the user to sign in, remembering where they
 * were, and reports nothing. Where that navigation cannot happen — an authentication page is already
 * showing, or this is running on the server — the user is told plainly instead of being left in
 * silence.
 * @param error a rejected request's reason
 * @param t a translator bound to the message root
 * @param fallbackKey the caller's own title key naming the operation, resolved from the message root
 * @returns the copy to render, or null when the user is on their way to sign in
 */
export function userMessageFor(
    error: unknown,
    t: MessageTranslator,
    fallbackKey?: string,
): UserFacingMessage | null {
    const expired = isSessionExpired(error);
    if (expired && redirectToSignIn()) return null;

    const fallbackTitle = fallbackKey !== undefined && fallbackKey !== "" && t.has(fallbackKey)
        ? t(fallbackKey)
        : undefined;
    const usableFallback = fallbackTitle !== undefined
        && fallbackTitle !== fallbackKey
        && !fallbackTitle.includes("{")
        ? fallbackTitle
        : undefined;
    return {
        title: usableFallback ?? t(GENERIC_TITLE_KEY),
        description: expired ? t(SESSION_EXPIRED_KEY) : descriptionFor(error, t),
    };
}

/**
 * Reports a rejected request as the branded error toast, in the product's one error dialect. This is
 * the only sanctioned way to toast a failure — see {@link userMessageFor} for what reaches the user
 * and what deliberately does not.
 * @param error a rejected request's reason
 * @param t a translator bound to the message root
 * @param fallbackKey the caller's own title key naming the operation, resolved from the message root
 */
export function toastApiError(error: unknown, t: MessageTranslator, fallbackKey?: string): void {
    const message = userMessageFor(error, t, fallbackKey);
    if (message === null) return;

    toastError(message.title, { description: message.description });
}
