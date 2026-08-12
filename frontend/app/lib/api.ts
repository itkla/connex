const API_BASE =
    typeof window === "undefined"
        ? process.env.API_URL ?? process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080"
        : "";

import { clearAllDrafts } from "@/app/lib/formDrafts";
import { resolveAiGeneration } from '@/app/lib/aiGeneration';
import { isProtectedPath } from "@/app/lib/protectedRoutes";
import { isProtectedMediaPath } from "@/app/lib/protectedMedia";

import type {
    AuthenticationResponseJSON,
    PublicKeyCredentialCreationOptionsJSON,
    PublicKeyCredentialRequestOptionsJSON,
    RegistrationResponseJSON,
} from '@simplewebauthn/browser';

import * as Types from '@/app/lib/types';
import { localeFromCookieHeader } from '@/i18n/config';
// Types

function requestLocale(init: RequestInit): string {
    const cookieHeader = typeof document === "undefined"
        ? new Headers(init.headers).get("cookie")
        : document.cookie;
    return localeFromCookieHeader(cookieHeader);
}

// The active workspace, read from the non-HttpOnly connex_workspace cookie and sent as a
// header on every client request. SSR callers forward the cookie itself (see safeReadWithCookie).
function clientWorkspaceId(): string | null {
    if (typeof document === "undefined") {
        return null;
    }
    const match = document.cookie.match(/(?:^|;\s*)connex_workspace=([^;]+)/);
    return match ? decodeURIComponent(match[1]) : null;
}

function workspaceIdFromCookieHeader(cookie: string): number | null {
    const match = cookie.match(/(?:^|;\s*)connex_workspace=([^;]+)/);
    if (!match) return null;
    const value = match[1].trim();
    if (!/^[+-]?\d+$/.test(value)) return null;
    const workspaceId = Number(value);
    return Number.isInteger(workspaceId) && workspaceId > 0 && workspaceId <= 2_147_483_647
        ? workspaceId
        : null;
}

function clientRecoveryWorkspaceId(): string | null {
    if (typeof document === "undefined") return null;
    const workspaceId = workspaceIdFromCookieHeader(document.cookie);
    return workspaceId == null ? null : String(workspaceId);
}

// CSRF token, fetched once from the backend and echoed in a header on state-changing requests.
// The frontend and backend can be different origins, so the token is delivered via this endpoint
// rather than a cookie the JS would otherwise be unable to read cross-origin.
type CsrfBootstrap = {
    token: string;
    headerName: string;
    requestIdentity: string | null;
};

type InFlightAiMutation = {
    controller: AbortController;
    request: Promise<unknown>;
};

type ResolvedClientRequestIdentity = {
    request: string;
};

const CLIENT_IDENTITY_EVENT_KEY = "connex:client-request-identity";
let csrfTokenCache: CsrfBootstrap | null = null;
let clientRequestIdentityEpoch = 0;
const inFlightAiMutations = new Map<string, InFlightAiMutation>();
const inFlightReportGenerations = new Map<string, {
    controller: AbortController;
    request: Promise<Types.ReportDocument>;
}>();
const inFlightReportRequests = new Set<AbortController>();
const inFlightAssistantTurns = new Map<string, {
    controller: AbortController;
    request: Promise<Types.AiChatTurnAccepted>;
}>();
const clientRequestIdentityInvalidationListeners = new Set<() => void>();

if (typeof window !== "undefined") {
    window.addEventListener("storage", (event) => {
        if (event.key === CLIENT_IDENTITY_EVENT_KEY) {
            invalidateClientRequestIdentity();
            if (event.newValue?.startsWith("logout:")) {
                clearAllDrafts();
                window.location.reload();
            } else if (event.newValue?.startsWith("workspace:")) {
                if (isProtectedPath(window.location.pathname)) {
                    window.location.replace("/dashboard");
                } else {
                    window.location.reload();
                }
            } else if (event.newValue?.startsWith("refresh:")) {
                window.location.reload();
            }
        }
    });
}

async function fetchCsrfToken(): Promise<CsrfBootstrap | null> {
    try {
        const res = await fetch(`${API_BASE}/api/auth/csrf`, { credentials: "include", cache: "no-store" });
        if (!res.ok) return null;
        const text = await res.text();
        if (!text) return null;
        const data = JSON.parse(text) as {
            token?: string;
            headerName?: string;
            requestIdentity?: string | null;
        };
        return data.token && data.headerName
            ? {
                token: data.token,
                headerName: data.headerName,
                requestIdentity: typeof data.requestIdentity === "string" && data.requestIdentity.length > 0
                    ? data.requestIdentity
                    : null,
            }
            : null;
    } catch {
        return null;
    }
}

/**
 * Resolves the CSRF header the backend expects on state-changing requests,
 * as a single-entry `{ [headerName]: token }` map (empty during SSR). Shared
 * with the realtime client, which echoes the same token on the STOMP CONNECT.
 * @param forceRefresh when true, discards the cached token and refetches it
 * @returns the CSRF header map, or an empty map when unavailable
 */
export async function csrfHeader(forceRefresh = false): Promise<Record<string, string>> {
    if (typeof window === "undefined") return {}; // SSR issues GETs only; CSRF does not apply
    if (forceRefresh) csrfTokenCache = null;
    if (!csrfTokenCache) csrfTokenCache = await fetchCsrfToken();
    return csrfTokenCache ? { [csrfTokenCache.headerName]: csrfTokenCache.token } : {};
}

function invalidateClientRequestIdentity() {
    csrfTokenCache = null;
    clientRequestIdentityEpoch += 1;
    for (const mutation of inFlightAiMutations.values()) {
        mutation.controller.abort();
    }
    inFlightAiMutations.clear();
    for (const generation of inFlightReportGenerations.values()) {
        generation.controller.abort();
    }
    inFlightReportGenerations.clear();
    for (const controller of inFlightReportRequests) {
        controller.abort();
    }
    inFlightReportRequests.clear();
    for (const turn of inFlightAssistantTurns.values()) {
        turn.controller.abort();
    }
    inFlightAssistantTurns.clear();
    for (const listener of [...clientRequestIdentityInvalidationListeners]) {
        try {
            listener();
        } catch {}
    }
}

/** Subscribes browser resources that must be revoked whenever authentication identity changes. */
export function subscribeClientRequestIdentityInvalidation(listener: () => void): () => void {
    clientRequestIdentityInvalidationListeners.add(listener);
    return () => clientRequestIdentityInvalidationListeners.delete(listener);
}

/** Fetches one canonical protected image under an explicitly captured workspace identity. */
export function fetchProtectedMediaResponse(
    path: string,
    workspaceId: number,
    signal: AbortSignal,
): Promise<Response> {
    if (
        !isProtectedMediaPath(path)
        || !Number.isInteger(workspaceId)
        || workspaceId < 1
        || workspaceId > 2_147_483_647
    ) {
        return Promise.reject(new RangeError("Protected media request scope is invalid"));
    }
    return fetch(`${API_BASE}${path}`, {
        credentials: "same-origin",
        cache: "no-store",
        headers: { "X-Workspace-Id": String(workspaceId) },
        mode: "same-origin",
        redirect: "error",
        signal,
    });
}

type ClientIdentityTransition = "invalidate" | "refresh" | "logout" | "workspace";

function broadcastClientRequestIdentityTransition(action: ClientIdentityTransition) {
    if (typeof window === "undefined") return;
    try {
        const eventId = typeof window.crypto.randomUUID === "function"
            ? window.crypto.randomUUID()
            : `${Date.now()}:${clientRequestIdentityEpoch}`;
        window.localStorage.setItem(CLIENT_IDENTITY_EVENT_KEY, `${action}:${eventId}`);
    } catch {
        return;
    }
}

function signalClientRequestIdentityTransition(action: ClientIdentityTransition) {
    invalidateClientRequestIdentity();
    broadcastClientRequestIdentityTransition(action);
}

async function resolveClientRequestIdentity(): Promise<ResolvedClientRequestIdentity | null> {
    const workspaceId = clientWorkspaceId();
    const currentCsrf = await fetchCsrfToken();
    if (workspaceId == null || currentCsrf == null || currentCsrf.requestIdentity == null) {
        return null;
    }
    const previousCsrf = csrfTokenCache;
    if (previousCsrf != null && (
        previousCsrf.token !== currentCsrf.token
        || previousCsrf.headerName !== currentCsrf.headerName
        || previousCsrf.requestIdentity !== currentCsrf.requestIdentity
    )) {
        const serverIdentityChanged = previousCsrf.requestIdentity !== currentCsrf.requestIdentity;
        invalidateClientRequestIdentity();
        if (serverIdentityChanged) {
            broadcastClientRequestIdentityTransition("refresh");
        }
    }
    csrfTokenCache = currentCsrf;
    const locale = localeFromCookieHeader(document.cookie);
    return {
        request: [
            clientRequestIdentityEpoch,
            workspaceId,
            locale,
            currentCsrf.requestIdentity,
            currentCsrf.headerName,
            currentCsrf.token,
        ].join("\u0000"),
    };
}

async function currentClientRequestIdentity(): Promise<string | null> {
    return (await resolveClientRequestIdentity())?.request ?? null;
}

/** Returns a non-reversible browser-storage context for the active user and workspace. */
export async function clientRecoveryContext(
    init: RequestInit = {},
): Promise<Types.BusinessCardRecoveryContext | null> {
    const workspaceId = clientRecoveryWorkspaceId();
    if (workspaceId == null || typeof window === "undefined" || !window.crypto.subtle) return null;
    const headers = new Headers(init.headers);
    headers.set("X-Workspace-Id", workspaceId);
    let body: unknown;
    try {
        body = await withBusinessCardRequestTimeout(
            10_000,
            init.signal,
            async (signal) => {
                const response = await fetch(`${API_BASE}/api/auth/me`, {
                    ...init,
                    credentials: "include",
                    cache: "no-store",
                    headers,
                    signal,
                });
                if (!response.ok) return null;
                const responseBody: unknown = await response.json().catch(() => null);
                return responseBody;
            },
        );
    } catch (error) {
        if (init.signal?.aborted || error instanceof ApiError) throw error;
        return null;
    }
    if (typeof body !== "object" || body === null || !("id" in body)) return null;
    if (clientRecoveryWorkspaceId() !== workspaceId) return null;
    const userId = body.id;
    if (typeof userId !== "number" || !Number.isInteger(userId) || userId <= 0) return null;
    const digest = await window.crypto.subtle.digest(
        "SHA-256",
        new TextEncoder().encode([workspaceId, userId].join("\u0000")),
    );
    if (clientRecoveryWorkspaceId() !== workspaceId) return null;
    return {
        scope: Array.from(
            new Uint8Array(digest),
            (byte) => byte.toString(16).padStart(2, "0"),
        ).join(""),
        workspaceId,
    };
}

async function requireBusinessCardRecoveryContext(
    expected: Types.BusinessCardRecoveryContext,
    signal?: AbortSignal | null,
): Promise<void> {
    const current = await clientRecoveryContext({ signal });
    if (!current
        || current.scope !== expected.scope
        || current.workspaceId !== expected.workspaceId) {
        throw new ApiError(
            "Business-card request context changed",
            409,
            "BUSINESS_CARD_CONTEXT_CHANGED",
        );
    }
}

function businessCardRequestInit(
    context: Types.BusinessCardRecoveryContext,
    init: RequestInit,
): RequestInit {
    const headers = new Headers(init.headers);
    headers.set("X-Workspace-Id", context.workspaceId);
    return { ...init, headers };
}

async function withClientRequestIdentityReset<T>(
    request: () => Promise<T>,
    successTransition: ClientIdentityTransition = "refresh",
): Promise<T> {
    signalClientRequestIdentityTransition("invalidate");
    try {
        const result = await request();
        signalClientRequestIdentityTransition(successTransition);
        return result;
    } catch (error) {
        invalidateClientRequestIdentity();
        throw error;
    }
}

function isMutating(method?: string): boolean {
    const m = (method ?? "GET").toUpperCase();
    return m !== "GET" && m !== "HEAD" && m !== "OPTIONS";
}

const CSRF_RETRY_EXEMPT_MUTATION_PATHS = new Set([
    "/api/auth/login",
    "/api/auth/register",
    "/api/auth/logout",
    "/api/auth/forgot-password",
    "/api/auth/reset-password",
    "/api/auth/sso/link/confirm",
    "/api/auth/webauthn/authenticate",
]);

const RECENT_AUTHENTICATION_REQUIRED_CODE = "RECENT_AUTHENTICATION_REQUIRED";
export const PASSKEY_ENROLLMENT_REQUIRED_CODE = "PASSKEY_ENROLLMENT_REQUIRED";
export const PASSKEY_STEP_UP_CANCELED_CODE = "PASSKEY_STEP_UP_CANCELED";
export const PASSKEY_STEP_UP_FAILED_CODE = "PASSKEY_STEP_UP_FAILED";
const PASSKEY_STEP_UP_PATHS = new Set([
    "/api/auth/webauthn/step-up/options",
    "/api/auth/webauthn/step-up",
]);
let passkeyStepUpPromise: Promise<void> | null = null;
let passkeyStepUpGeneration = 0;

function isCsrfRetryExemptMutation(path: string): boolean {
    const pathname = path.split("?")[0];
    return CSRF_RETRY_EXEMPT_MUTATION_PATHS.has(pathname) ||
        pathname.startsWith("/api/auth/webauthn/authenticate/");
}

async function requestJson<T>(
    path: string,
    init: RequestInit = {},
    workspaceSelectionBody: "standard" | "required" | "optional" = "standard",
): Promise<T> {
    const locale = requestLocale(init);
    const workspaceId = clientWorkspaceId();
    const mutating = isMutating(init.method);
    const stepUpGeneration = passkeyStepUpGeneration;
    const hasMultipartBody = typeof FormData !== "undefined" && init.body instanceof FormData;

    const send = (csrf: Record<string, string>) => {
        const headers = new Headers({
            ...(init.body && !hasMultipartBody ? { "Content-Type": "application/json" } : {}),
            "Accept-Language": locale,
            ...(workspaceId ? { "X-Workspace-Id": workspaceId } : {}),
            ...csrf,
        });
        new Headers(init.headers).forEach((value, key) => headers.set(key, value));
        return fetch(`${API_BASE}${path}`, {
            ...init,
            credentials: "include",
            headers,
        });
    };

    const sendWithCsrfRetry = async () => {
        let response = await send(mutating ? await csrfHeader() : {});
        if (await shouldRetryWithFreshCsrf(path, response, mutating)) {
            response = await send(await csrfHeader(true));
        }
        return response;
    };

    let res = await sendWithCsrfRetry();
    if (await shouldRetryAfterPasskeyStepUp(path, res, mutating)) {
        if (stepUpGeneration === passkeyStepUpGeneration) {
            await performPasskeyStepUp();
        }
        res = await sendWithCsrfRetry();
    }

    if (!res.ok) {
        throw await getApiError(res);
    }

    try {
        const text = await res.text();

        if (!text) {
            if (workspaceSelectionBody === "required") {
                throw new SyntaxError("Workspace selection response body was empty");
            }
            return undefined as T;
        }

        return JSON.parse(text) as T;
    } catch (error) {
        if (workspaceSelectionBody !== "standard") {
            throw new WorkspaceSelectionResponseBodyError(error);
        }
        throw error;
    }
}

class WorkspaceSelectionResponseBodyError extends Error {
    readonly responseBodyError: unknown;

    constructor(responseBodyError: unknown) {
        super(responseBodyError instanceof Error
            ? responseBodyError.message
            : "Workspace selection response body failed");
        this.name = "WorkspaceSelectionResponseBodyError";
        this.responseBodyError = responseBodyError;
    }
}

/**
 * Reports that a successful workspace-selection response changed the browser cookie but neither
 * its body nor the authoritative workspace snapshot could establish the selection to publish.
 * Callers with a mounted {@code WorkspaceProvider} must fail closed until an ordered retry succeeds.
 */
export class WorkspaceSelectionUnavailableError extends Error {
    readonly responseBodyError: unknown;
    readonly recoveryError: unknown;

    constructor(responseBodyError: unknown, recoveryError: unknown) {
        super(recoveryError instanceof Error
            ? recoveryError.message
            : "Workspace selection is temporarily unavailable");
        this.name = "WorkspaceSelectionUnavailableError";
        this.responseBodyError = responseBodyError;
        this.recoveryError = recoveryError;
    }
}

/**
 * Completes a selection-changing request whose successful headers arrived before its body failed.
 * Fetch failures before a {@code Response} exists and non-success responses reject unchanged. Once
 * successful headers may have applied {@code connex_workspace}, this performs an ordered read of
 * the authoritative workspace snapshot and projects it into the endpoint's normal return shape so
 * the existing success path publishes and navigates exactly once. Recovery remains awaited by the
 * caller, so a surrounding selection mutex stays held. A failed or inconsistent re-read throws
 * {@link WorkspaceSelectionUnavailableError} instead of allowing stale provider state to continue.
 */
export async function recoverWorkspaceSelectionResponse<T>(
    request: () => Promise<T>,
    recover: (snapshot: Types.MyWorkspaces) => T,
): Promise<T> {
    try {
        return await request();
    } catch (error) {
        if (!(error instanceof WorkspaceSelectionResponseBodyError)) throw error;
        try {
            return recover(await readAuthoritativeWorkspaceSelection(false));
        } catch (recoveryError) {
            signalClientRequestIdentityTransition("workspace");
            throw new WorkspaceSelectionUnavailableError(
                error.responseBodyError,
                recoveryError,
            );
        }
    }
}

async function requestMultipart<T>(
    path: string,
    method: "POST" | "PUT",
    body: FormData,
    init: RequestInit = {},
): Promise<T> {
    const locale = requestLocale(init);
    const workspaceId = clientWorkspaceId();
    const stepUpGeneration = passkeyStepUpGeneration;
    const send = (csrf: Record<string, string>) => {
        const headers = new Headers({
            "Accept-Language": locale,
            ...(workspaceId ? { "X-Workspace-Id": workspaceId } : {}),
            ...csrf,
        });
        new Headers(init.headers).forEach((value, key) => headers.set(key, value));
        return fetch(`${API_BASE}${path}`, {
            ...init,
            method,
            body,
            credentials: "include",
            headers,
        });
    };
    const sendWithCsrfRetry = async () => {
        let response = await send(await csrfHeader());
        if (await shouldRetryWithFreshCsrf(path, response, true)) {
            response = await send(await csrfHeader(true));
        }
        return response;
    };
    let response = await sendWithCsrfRetry();
    if (await shouldRetryAfterPasskeyStepUp(path, response, true)) {
        if (stepUpGeneration === passkeyStepUpGeneration) {
            await performPasskeyStepUp();
        }
        response = await sendWithCsrfRetry();
    }
    if (!response.ok) {
        throw await getApiError(response);
    }
    const text = await response.text();
    return text ? JSON.parse(text) as T : undefined as T;
}

async function shouldRetryAfterPasskeyStepUp(path: string, res: Response, mutating: boolean): Promise<boolean> {
    const pathname = path.split("?")[0];
    if (!mutating || res.status !== 403 || typeof window === "undefined" || PASSKEY_STEP_UP_PATHS.has(pathname)) {
        return false;
    }
    const text = await res.clone().text().catch(() => "");
    if (!text) return false;
    try {
        const data = JSON.parse(text) as unknown;
        return isStringRecord(data) && data.code === RECENT_AUTHENTICATION_REQUIRED_CODE;
    } catch {
        return false;
    }
}

async function performPasskeyStepUp(): Promise<void> {
    if (passkeyStepUpPromise) {
        return passkeyStepUpPromise;
    }
    const ceremony = completePasskeyStepUp();
    passkeyStepUpPromise = ceremony;
    try {
        await ceremony;
    } finally {
        if (passkeyStepUpPromise === ceremony) {
            passkeyStepUpPromise = null;
        }
    }
}

async function completePasskeyStepUp(): Promise<void> {
    try {
        const { startAuthentication } = await import('@simplewebauthn/browser');
        const optionsJSON = await beginPasskeyStepUp();
        const credential = await startAuthentication({ optionsJSON });
        await finishPasskeyStepUp(credential);
        passkeyStepUpGeneration += 1;
    } catch (error) {
        if (error instanceof ApiError && error.code === PASSKEY_ENROLLMENT_REQUIRED_CODE) throw error;
        if (error instanceof ApiError) {
            throw new ApiError("Passkey verification failed", error.status, PASSKEY_STEP_UP_FAILED_CODE);
        }
        const canceled = errorName(error) === "NotAllowedError" || errorCauseName(error) === "NotAllowedError";
        throw new ApiError(
            canceled ? "Passkey verification was canceled" : "Passkey verification failed",
            400,
            canceled ? PASSKEY_STEP_UP_CANCELED_CODE : PASSKEY_STEP_UP_FAILED_CODE,
        );
    }
}

function errorName(value: unknown): string | null {
    if (value instanceof Error) return value.name;
    if (typeof value === "object" && value !== null && "name" in value && typeof value.name === "string") {
        return value.name;
    }
    return null;
}

function errorCauseName(value: unknown): string | null {
    if (typeof value !== "object" || value === null || !("cause" in value)) return null;
    return errorName(value.cause);
}

async function shouldRetryWithFreshCsrf(path: string, res: Response, mutating: boolean): Promise<boolean> {
    if (res.status !== 403 || !mutating || typeof window === "undefined" || isCsrfRetryExemptMutation(path)) {
        return false;
    }

    const text = await res.clone().text().catch(() => "");
    if (!text) {
        return true;
    }

    try {
        const data = JSON.parse(text) as unknown;
        if (isStringRecord(data)) {
            return false;
        }
    } catch {
        return text.toLowerCase().includes("csrf");
    }

    return true;
}

async function postJson<T>(path: string, body: unknown = {}, init: RequestInit = {}): Promise<T> {
    return requestJson<T>(path, {
        ...init,
        method: "POST",
        body: JSON.stringify(body),
    });
}

async function postWorkspaceSelectionJson<T>(
    path: string,
    body: unknown = {},
    bodyRequired = true,
): Promise<T> {
    return requestJson<T>(path, {
        method: "POST",
        body: JSON.stringify(body),
    }, bodyRequired ? "required" : "optional");
}

async function postFormData<T>(path: string, body: FormData, init: RequestInit = {}): Promise<T> {
    return requestJson<T>(path, {
        ...init,
        method: "POST",
        body,
    });
}

async function getJson<T>(path: string, init: RequestInit = {}): Promise<T> {
    return requestJson<T>(path, { ...init, method: "GET" });
}

async function pollAiGeneration<T>(
    initial: Types.AiGenerationStatus<T>,
    init: RequestInit,
): Promise<T> {
    return resolveAiGeneration(
        initial,
        (handle) => getJson<Types.AiGenerationStatus<T>>(
            `/api/ai/generations/${encodeURIComponent(handle)}`,
            { ...init, cache: 'no-store' },
        ),
        {
            signal: init.signal,
            shouldRetryError: (error) => !(error instanceof ApiError)
                || error.status === 408
                || error.status === 425
                || error.status === 429
                || error.status >= 500,
        },
    );
}

/**
 * De-duplicates concurrent AI mutations only within one opaque server-issued authenticated-session
 * generation, active workspace, and locale. Requests without a provable identity bypass de-duplication.
 */
async function dedupedAiPost<T>(path: string, init: RequestInit = {}): Promise<T> {
    if (typeof window === "undefined" || Object.keys(init).length > 0) {
        const accepted = await postJson<Types.AiGenerationStatus<T>>(path, {}, init);
        return pollAiGeneration(accepted, init);
    }
    const identity = await currentClientRequestIdentity();
    if (identity == null) {
        const accepted = await postJson<Types.AiGenerationStatus<T>>(path, {}, init);
        return pollAiGeneration(accepted, init);
    }
    const key = `${identity}\u0000${path}`;
    const existing = inFlightAiMutations.get(key);
    if (existing) {
        return existing.request as Promise<T>;
    }
    const controller = new AbortController();
    const request = (async () => {
        const accepted = await postJson<Types.AiGenerationStatus<T>>(
            path,
            {},
            { signal: controller.signal },
        );
        const response = await pollAiGeneration<T>(accepted, { signal: controller.signal });
        if (await currentClientRequestIdentity() !== identity) {
            throw new Error("AI request identity changed before completion");
        }
        return response;
    })().finally(() => {
        if (inFlightAiMutations.get(key)?.request === request) {
            inFlightAiMutations.delete(key);
        }
    });
    inFlightAiMutations.set(key, { controller, request });
    return request;
}

/** Resolves a previously accepted generation using only the shared status endpoint. */
export async function resolveAcceptedAiGeneration<T>(
    initial: Types.AiGenerationStatus<T>,
    init: RequestInit = {},
): Promise<T> {
    if (typeof window === 'undefined') {
        return pollAiGeneration(initial, init);
    }
    const identity = await currentClientRequestIdentity();
    if (identity == null) {
        throw new Error('Unable to establish the authenticated AI request identity');
    }
    const controller = new AbortController();
    inFlightReportRequests.add(controller);
    const signal = init.signal
        ? AbortSignal.any([controller.signal, init.signal])
        : controller.signal;
    try {
        const response = await pollAiGeneration<T>(initial, { ...init, signal });
        if (await currentClientRequestIdentity() !== identity) {
            throw new Error('AI request identity changed before completion');
        }
        return response;
    } finally {
        inFlightReportRequests.delete(controller);
    }
}

async function putJson<T>(path: string, body: unknown = {}, init: RequestInit = {}): Promise<T> {
    return requestJson<T>(path, {
        ...init,
        method: "PUT",
        body: JSON.stringify(body),
    });
}

async function patchJson<T>(path: string, body: unknown = {}, init: RequestInit = {}): Promise<T> {
    return requestJson<T>(path, {
        ...init,
        method: "PATCH",
        body: JSON.stringify(body),
    });
}

async function deleteJson<T>(path: string, init: RequestInit = {}): Promise<T> {
    return requestJson<T>(path, {
        method: "DELETE",
        ...init,
    });
}

async function safeReadWithCookie<T>(
    fetcher: (init: RequestInit) => Promise<T[]>,
    cookie: string | null,
): Promise<T[]> {
    if (!cookie) return [];
    try {
        return await fetcher({ headers: { cookie }, cache: "no-store" });
    } catch {
        return [];
    }
}

export type CookieResult<T> = { ok: true; data: T } | { ok: false };

/**
 * As {@link safeReadWithCookie}, but reports a fetch failure distinctly from an empty result so the
 * caller can render an error state instead of presenting a backend fault as an empty workspace.
 * A missing cookie also reports {@code ok: false} — the data could not be loaded either way.
 */
async function resultWithCookie<T>(
    fetcher: (init: RequestInit) => Promise<T>,
    cookie: string | null,
): Promise<CookieResult<T>> {
    if (!cookie) return { ok: false };
    try {
        return { ok: true, data: await fetcher({ headers: { cookie }, cache: "no-store" }) };
    } catch {
        return { ok: false };
    }
}

/**
 * Wraps an in-flight request in the {@link CookieResult} shape, for fetchers that are
 * already bound to their arguments and so cannot go through {@link resultWithCookie}.
 * Lets a caller distinguish a failure from a legitimately empty payload without
 * inventing a zeroed fallback.
 * @param request the pending request
 */
export async function toResult<T>(request: Promise<T>): Promise<CookieResult<T>> {
    try {
        return { ok: true, data: await request };
    } catch {
        return { ok: false };
    }
}

function buildQuery(params: Record<string, unknown>): string {
    const search = new URLSearchParams();
    for (const [key, value] of Object.entries(params)) {
        if (value === undefined || value === null) continue;
        if (Array.isArray(value)) {
            for (const v of value) if (v !== undefined && v !== null) search.append(key, String(v));
        } else {
            search.set(key, String(value));
        }
    }
    const qs = search.toString();
    return qs ? `?${qs}` : "";
}

const WORKSPACE_LIST_PAGE_SIZE = 100;
const RELATIONSHIP_MAP_RECORD_LIMIT = 2_000;
const RELATIONSHIP_MAP_TOUCH_LIMIT = 4_000;

function hasQueryValues(params: Record<string, unknown>): boolean {
    return Object.values(params).some((value) => {
        if (Array.isArray(value)) return value.length > 0;
        if (typeof value === "string") return value.trim().length > 0;
        if (typeof value === "boolean") return value;
        return value !== undefined && value !== null;
    });
}

async function getCompletePageItems<T>(
    fetchPage: (params: Types.PageParams, init: RequestInit) => Promise<Types.Page<T>>,
    init: RequestInit = {},
): Promise<T[]> {
    const firstPage = await fetchPage({ page: 1, size: WORKSPACE_LIST_PAGE_SIZE }, init);
    return getCompleteKnownPage(firstPage, fetchPage, init);
}

async function getCompleteKnownPage<T>(
    firstPage: Types.Page<T>,
    fetchPage: (params: Types.PageParams, init: RequestInit) => Promise<Types.Page<T>>,
    init: RequestInit,
): Promise<T[]> {
    const pageCount = Math.ceil(firstPage.total / WORKSPACE_LIST_PAGE_SIZE);
    if (pageCount <= 1) return firstPage.items;
    const items = [...firstPage.items];
    for (let page = 2; page <= pageCount; page += 1) {
        const response = await fetchPage({ page, size: WORKSPACE_LIST_PAGE_SIZE }, init);
        items.push(...response.items);
        if (response.items.length === 0) break;
    }
    return items;
}

/** Default per-source item cap for calendar reads. Five requests at the server's max page size. */
export const CALENDAR_SOURCE_ITEM_CAP = 500;

/**
 * A bounded collection read: the items actually fetched, the server's reported total,
 * and whether the cap cut the result short.
 */
export type CappedItems<T> = { items: T[]; total: number; truncated: boolean };

/**
 * Reads at most `cap` items from a paged endpoint.
 *
 * Unlike {@link getCompletePageItems}, which walks every page and so issues an
 * unbounded number of serial requests on a large workspace, this stops at the cap and
 * reports the truncation so the caller can disclose it. Callers must surface
 * `truncated` — silently showing a partial collection as if it were the whole one is
 * the failure mode this exists to prevent.
 * @param fetchPage the paged endpoint to read
 * @param cap maximum number of items to accumulate
 */
async function getCappedPageItems<T>(
    fetchPage: (params: Types.PageParams, init: RequestInit) => Promise<Types.Page<T>>,
    cap: number,
    init: RequestInit = {},
): Promise<CappedItems<T>> {
    const items: T[] = [];
    let total = 0;
    for (let page = 1; items.length < cap; page += 1) {
        const response = await fetchPage({ page, size: WORKSPACE_LIST_PAGE_SIZE }, init);
        total = response.total;
        if (response.items.length === 0) break;
        items.push(...response.items);
        if (items.length >= total) break;
    }
    const bounded = items.slice(0, cap);
    return { items: bounded, total, truncated: total > bounded.length };
}

export function getTasksCappedResultFromCookie(cookie: string | null, cap = CALENDAR_SOURCE_ITEM_CAP) {
    return resultWithCookie<CappedItems<Types.Task>>(
        (init) => getCappedPageItems<Types.Task>(getTasksPage, cap, init),
        cookie,
    );
}

export function getActivitiesCappedResultFromCookie(cookie: string | null, cap = CALENDAR_SOURCE_ITEM_CAP) {
    return resultWithCookie<CappedItems<Types.Activity>>(
        (init) => getCappedPageItems<Types.Activity>(
            (params, requestInit) => getActivitiesPage(params, requestInit),
            cap,
            init,
        ),
        cookie,
    );
}

export function getNotesCappedResultFromCookie(cookie: string | null, cap = CALENDAR_SOURCE_ITEM_CAP) {
    return resultWithCookie<CappedItems<Types.Note>>(
        (init) => getCappedPageItems<Types.Note>(getNotesPage, cap, init),
        cookie,
    );
}

export function getContactsCappedResultFromCookie(cookie: string | null, cap = CALENDAR_SOURCE_ITEM_CAP) {
    return resultWithCookie<CappedItems<Types.Contact>>(
        (init) => getCappedPageItems<Types.Contact>(
            (params, requestInit) => getContactsPage(params, requestInit),
            cap,
            init,
        ),
        cookie,
    );
}

export function getDealsCappedResultFromCookie(cookie: string | null, cap = CALENDAR_SOURCE_ITEM_CAP) {
    return resultWithCookie<CappedItems<Types.Deal>>(
        (init) => getCappedPageItems<Types.Deal>(
            (params, requestInit) => getDealsPage(params, requestInit),
            cap,
            init,
        ),
        cookie,
    );
}

/** Complete, explicitly capped inputs for the relationship graph. */
export async function getRelationshipMapData(init: RequestInit = {}) {
    const [companies, contacts, deals, activities, tasks, notes] = await Promise.all([
        getCompaniesPage({ page: 1, size: WORKSPACE_LIST_PAGE_SIZE }, init),
        getContactsPage({ page: 1, size: WORKSPACE_LIST_PAGE_SIZE }, init),
        getDealsPage({ page: 1, size: WORKSPACE_LIST_PAGE_SIZE }, init),
        getActivitiesPage({ page: 1, size: WORKSPACE_LIST_PAGE_SIZE }, init),
        getTasksPage({ page: 1, size: WORKSPACE_LIST_PAGE_SIZE }, init),
        getNotesPage({ page: 1, size: WORKSPACE_LIST_PAGE_SIZE, workspaceOnly: true }, init),
    ]);
    if (companies.total + contacts.total + deals.total > RELATIONSHIP_MAP_RECORD_LIMIT
        || activities.total + tasks.total + notes.total > RELATIONSHIP_MAP_TOUCH_LIMIT) {
        throw new Error('The relationship map exceeds its safe rendering limit');
    }
    const [allCompanies, allContacts, allDeals, allActivities, allTasks, allNotes] = await Promise.all([
        getCompleteKnownPage(companies, getCompaniesPage, init),
        getCompleteKnownPage(contacts, getContactsPage, init),
        getCompleteKnownPage(deals, getDealsPage, init),
        getCompleteKnownPage(activities, getActivitiesPage, init),
        getCompleteKnownPage(tasks, getTasksPage, init),
        getCompleteKnownPage(
            notes,
            (params, requestInit) => getNotesPage({ ...params, workspaceOnly: true }, requestInit),
            init,
        ),
    ]);
    return {
        companies: allCompanies,
        contacts: allContacts,
        deals: allDeals,
        activities: allActivities,
        tasks: allTasks,
        notes: allNotes,
    };
}

/*
* == Authentication
*/

export type ApiFieldErrors = Record<string, string>;

export class ApiError extends Error {
    status: number;
    code?: string;
    fieldErrors?: ApiFieldErrors;
    correlationId?: string;
    /**
     * True when the response carried no body. A bodyless 401/403 is how Spring Security signals an
     * unauthenticated caller (expired or missing session), as opposed to a genuine authorization
     * denial, which the backend answers with an explanatory body.
     */
    emptyBody?: boolean;

    constructor(
        message: string,
        status: number,
        code?: string,
        fieldErrors?: ApiFieldErrors,
        correlationId?: string,
        emptyBody?: boolean,
    ) {
        super(message);
        this.name = "ApiError";
        this.status = status;
        this.code = code;
        this.fieldErrors = fieldErrors;
        this.correlationId = correlationId;
        this.emptyBody = emptyBody;
    }
}

export function isFieldError(err: unknown): err is ApiError & { fieldErrors: ApiFieldErrors } {
    return err instanceof ApiError && !!err.fieldErrors && Object.keys(err.fieldErrors).length > 0;
}

/** Classifies card scan/import failures into stable UI states without exposing backend messages. */
export function businessCardRequestErrorKind(error: unknown): Types.BusinessCardRequestErrorKind {
    if (error instanceof Error && error.name === "AbortError") return "aborted";
    if (error instanceof Error && error.name === "TimeoutError") return "timeout";
    if (!(error instanceof ApiError)) return "failed";

    const code = error.code?.toUpperCase() ?? "";
    if (code.includes("TIMEOUT")) return "timeout";
    if (code.includes("UNAVAILABLE")) return "unavailable";

    switch (error.status) {
        case 401:
            return "unauthorized";
        case 403:
            return "forbidden";
        case 408:
        case 504:
            return "timeout";
        case 409:
            return "conflict";
        case 410:
            return "gone";
        case 413:
            return "tooLarge";
        case 415:
            return "unsupportedType";
        case 422:
            return "unreadable";
        case 429:
            return "busy";
        case 502:
        case 503:
            return "unavailable";
        default:
            return error.status > 0 && error.status < 500 ? "rejected" : "failed";
    }
}

async function withBusinessCardRequestTimeout<T>(
    timeoutMilliseconds: number,
    parentSignal: AbortSignal | null | undefined,
    request: (signal: AbortSignal) => Promise<T>,
): Promise<T> {
    const controller = new AbortController();
    let timedOut = false;
    const abortFromParent = () => controller.abort();
    if (parentSignal?.aborted) {
        abortFromParent();
    } else {
        parentSignal?.addEventListener("abort", abortFromParent, { once: true });
    }
    const timer = globalThis.setTimeout(() => {
        timedOut = true;
        controller.abort();
    }, timeoutMilliseconds);
    try {
        return await request(controller.signal);
    } catch (error) {
        if (timedOut) {
            throw new ApiError("Business-card request timed out", 408, "CLIENT_TIMEOUT");
        }
        throw error;
    } finally {
        globalThis.clearTimeout(timer);
        parentSignal?.removeEventListener("abort", abortFromParent);
    }
}

function isStringRecord(value: unknown): value is Record<string, string> {
    return (
        typeof value === "object" &&
        value !== null &&
        !Array.isArray(value) &&
        Object.values(value).every((entry) => typeof entry === "string")
    );
}

async function getApiError(res: Response): Promise<ApiError> {
    const text = await res.text().catch(() => "");

    if (!text) {
        return new ApiError(`Request failed (${res.status})`, res.status, undefined, undefined, undefined, true);
    }

    try {
        const data = JSON.parse(text) as unknown;

        if (isStringRecord(data)) {
            const { message, error, code, correlationId, ...fieldErrors } = data;
            const fields = Object.keys(fieldErrors).length > 0 ? fieldErrors : undefined;

            return new ApiError(
                message ?? error ?? "Please fix the highlighted fields.",
                res.status,
                code,
                fields,
                correlationId,
            );
        }

        return new ApiError(text, res.status);
    } catch {
        return new ApiError(text, res.status);
    }
}

/**
 * Calls a public, unauthenticated endpoint without the workspace header, CSRF token, or credentials
 * the tenant-scoped helpers attach. Used for links a recipient opens with no Connex session.
 * @param path the API path to request
 * @param method the HTTP method
 * @param init optional fetch overrides
 * @returns the parsed JSON body, or {@code undefined} for an empty response
 * @throws ApiError when the response status is not ok
 */
async function publicJson<T>(
    path: string,
    method: "GET" | "POST",
    init: RequestInit = {},
): Promise<T> {
    const res = await fetch(`${API_BASE}${path}`, {
        cache: "no-store",
        ...init,
        method,
        headers: { Accept: "application/json", ...init.headers },
    });
    if (!res.ok) {
        throw await getApiError(res);
    }
    const text = await res.text();
    return text ? (JSON.parse(text) as T) : (undefined as T);
}


/**
 * Logs in a user with the provided credentials.
 * 
 * @param payload - An object containing the username and password for login
 * @returns A promise that resolves to the logged-in user's information
 * @throws An error if the login request fails, including the response text if available
 */
export function login(payload: Types.LoginPayload) {
    return withClientRequestIdentityReset(() => postJson<Types.AuthResponse>("/api/auth/login", payload));
}

/**
 * POST endpoint to register a new user.
 * 
 * @param payload
 * @return
 */
export function register(payload: Types.RegisterPayload) {
    return withClientRequestIdentityReset(() => postJson<Types.AuthResponse>("/api/auth/register", payload));
}

/**
 * Retrieves the currently authenticated user's profile.
 * 
 * @returns A promise that resolves to the authenticated user's profile information
 * @throws An error if the profile retrieval request fails, including the response text if available
 */
export function me(init: RequestInit = {}) {
    return getJson<Types.User>("/api/auth/me", init);
}

/**
 * Resolves the authenticated user from a forwarded cookie header during SSR.
 * Returns null when the session is absent or the backend rejects it, so pages
 * can redirect to login; rethrows network-level failures (the backend being
 * unreachable) so they surface in the segment error boundary instead of
 * masquerading as a logged-out session.
 * @param cookie the incoming request's cookie header, or null
 * @returns the authenticated user, or null when unauthenticated
 */
export async function getCurrentUserFromCookie(cookie: string | null) {
    if (!cookie) {
        return null;
    }

    try {
        return await me({
            headers: { cookie },
            cache: "no-store",
        });
    } catch (error) {
        if (error instanceof TypeError) {
            throw error;
        }
        return null;
    }
}

/**
 * Resolves the authenticated user while preserving the difference between a rejected session and
 * an unavailable authentication check. The backend reports expired sessions with HTTP 401; every
 * other failed response remains unavailable rather than becoming a logged-out decision.
 * @param cookie the incoming request's cookie header, or null
 * @returns a resolved user or null authentication decision, or an unavailable result
 */
export async function getCurrentUserResultFromCookie(
    cookie: string | null,
): Promise<CookieResult<Types.User | null>> {
    if (!cookie) {
        return { ok: true, data: null };
    }

    try {
        const user = await me({
            headers: { cookie },
            cache: "no-store",
        });
        return { ok: true, data: user };
    } catch (error) {
        if (error instanceof ApiError && error.status === 401) {
            return { ok: true, data: null };
        }
        return { ok: false };
    }
}

/**
 * Resolves the authenticated user for a **public** page — documentation, marketing, legal —
 * where the session only selects which call to action the header shows.
 *
 * Unlike {@link getCurrentUserFromCookie}, a transport failure resolves to `null` instead of
 * propagating. That rethrow exists so an unreachable backend cannot masquerade as a logged-out
 * session on an authenticated surface, but a page carrying no authenticated content has nothing
 * to protect: applying it there turns any cookie — `NEXT_LOCALE` alone is enough — into a 500 on
 * the pages most likely to be read during an outage. Degrading to the signed-out call to action
 * costs a signed-in visitor one extra click and keeps the page readable.
 *
 * Authenticated surfaces must use {@link getCurrentUserFromCookie} or, when they make an access
 * decision, {@link getCurrentUserResultFromCookie}; they must not use this public resolver.
 * @param cookie the incoming request's cookie header, or null
 * @returns the authenticated user, or null when unauthenticated or the backend is unreachable
 */
export async function getPublicPageUserFromCookie(cookie: string | null): Promise<Types.User | null> {
    const user = await toResult(getCurrentUserFromCookie(cookie));
    return user.ok ? user.data : null;
}

export function logout() {
    clearAllDrafts();
    return withClientRequestIdentityReset(() => postJson<void>("/api/auth/logout"), "logout");
}

/**
 * Requests a password reset email for the given address. The backend always responds
 * identically whether or not the account exists, so callers must not infer existence.
 *
 * @param payload - The email to send a reset link to
 * @returns A promise resolving to the generic confirmation message
 */
export function requestPasswordReset(payload: Types.ForgotPasswordPayload) {
    return postJson<Types.AuthResponse>("/api/auth/forgot-password", payload);
}

/**
 * Checks whether a password reset token is still valid (unconsumed and unexpired).
 *
 * @param token - The raw reset token from the link
 * @returns A promise resolving to the validation result
 */
export function validateResetToken(token: string, init: RequestInit = {}) {
    return getJson<Types.ResetTokenValidation>(
        `/api/auth/reset-password/validate?token=${encodeURIComponent(token)}`,
        init,
    );
}

/**
 * Sets a new password using a valid reset token.
 *
 * @param payload - The reset token and the new password
 * @returns A promise resolving to the confirmation message
 * @throws ApiError with fieldErrors when the password fails policy or the token is invalid
 */
export function resetPassword(payload: Types.ResetPasswordPayload) {
    return postJson<Types.AuthResponse>("/api/auth/reset-password", payload);
}

/**
 * Checks whether a registration email-verification token is still valid.
 * @param token - The raw token from the verification link
 * @returns A promise resolving to the validation result
 */
export function validateEmailVerificationToken(token: string, init: RequestInit = {}) {
    return getJson<Types.ResetTokenValidation>(
        `/api/auth/verify-email/validate?token=${encodeURIComponent(token)}`,
        init,
    );
}

/**
 * Marks the account behind a valid verification token as email-verified.
 * @param token - The raw token from the verification link
 * @returns A promise resolving to the confirmation message
 */
export function confirmEmailVerification(token: string) {
    return postJson<Types.AuthResponse>("/api/auth/verify-email/confirm", { token });
}

/**
 * Requests a verified change of the signed-in user's email address. The backend emails a
 * confirmation link to the new address and only applies the change once it is redeemed.
 *
 * @param payload - The new email and the current password proving ownership
 * @returns A promise resolving to the confirmation message
 * @throws ApiError with fieldErrors when the password is wrong or the email is invalid or taken
 */
export function requestEmailChange(payload: Types.EmailChangePayload) {
    return postJson<Types.AuthResponse>("/api/users/me/email-change", payload);
}

/**
 * Checks whether an email-change token is still valid (unconsumed and unexpired).
 * @param token - The raw token from the confirmation link
 * @returns A promise resolving to the validation result
 */
export function validateEmailChangeToken(token: string, init: RequestInit = {}) {
    return getJson<Types.ResetTokenValidation>(
        `/api/auth/email-change/validate?token=${encodeURIComponent(token)}`,
        init,
    );
}

/**
 * Applies a pending email change behind a valid confirmation token.
 * @param token - The raw token from the confirmation link
 * @returns A promise resolving to the confirmation message
 */
export function confirmEmailChange(token: string) {
    return postJson<Types.AuthResponse>("/api/auth/email-change/confirm", { token });
}

export function getPasskeys(init: RequestInit = {}) {
    return getJson<Types.Passkey[]>("/api/auth/webauthn/credentials", { cache: "no-store", ...init });
}

export function getPasskeyRegistrationRequirements(init: RequestInit = {}) {
    return getJson<{ currentPasswordRequired: boolean }>(
        "/api/auth/webauthn/register/requirements",
        { cache: "no-store", ...init },
    );
}

export function beginPasskeyRegistration(currentPassword?: string) {
    return postJson<PublicKeyCredentialCreationOptionsJSON>(
        "/api/auth/webauthn/register/options",
        currentPassword ? { currentPassword } : {},
    );
}

export function finishPasskeyRegistration(label: string, credential: RegistrationResponseJSON) {
    return postJson<{ credentialId: string }>(
        `/api/auth/webauthn/register?label=${encodeURIComponent(label)}`,
        credential,
    );
}

export function beginPasskeyAuthentication() {
    return postJson<PublicKeyCredentialRequestOptionsJSON>("/api/auth/webauthn/authenticate/options");
}

export function finishPasskeyAuthentication(credential: AuthenticationResponseJSON) {
    return withClientRequestIdentityReset(
        () => postJson<Types.AuthResponse>("/api/auth/webauthn/authenticate", credential),
    );
}

export function beginPasskeyStepUp() {
    return postJson<PublicKeyCredentialRequestOptionsJSON>("/api/auth/webauthn/step-up/options");
}

export function finishPasskeyStepUp(credential: AuthenticationResponseJSON) {
    return postJson<Types.AuthResponse>("/api/auth/webauthn/step-up", credential);
}

export function renamePasskey(credentialId: string, label: string) {
    return patchJson<Types.AuthResponse>(
        `/api/auth/webauthn/credentials/${encodeURIComponent(credentialId)}`,
        { label },
    );
}

export function deletePasskey(credentialId: string) {
    return deleteJson<void>(`/api/auth/webauthn/credentials/${encodeURIComponent(credentialId)}`);
}

/*
* == Enterprise SSO
*/

/**
 * Fetches the instance capability flags that gate optional UI: enterprise SSO, consumer social
 * login, instance-managed mail, business-card scanning, source-image import, and native campaign
 * delivery. Consolidates the former per-feature `/enabled` endpoints.
 * @param init optional fetch overrides
 * @returns the resolved instance capabilities
 */
export function getCapabilities(init: RequestInit = {}) {
    return getJson<Types.InstanceCapabilities>("/api/capabilities", { cache: "no-store", ...init });
}

export function getCapabilitiesResultFromCookie(cookie: string | null) {
    return resultWithCookie<Types.InstanceCapabilities>((init) => getCapabilities(init), cookie);
}

/**
 * The fail-safe capabilities used when {@link getCapabilities} cannot be reached: everything off,
 * so optional UI stays hidden rather than rendering a feature the backend will reject.
 */
export const DEFAULT_CAPABILITIES: Types.InstanceCapabilities = {
    sso: false,
    socialLogin: { google: false, microsoft: false },
    connectedAccounts: { google: false, microsoft: false },
    connectedCapture: { google: false, microsoft: false },
    mailManaged: false,
    businessCardScanning: false,
    businessCardImport: false,
    campaignDelivery: false,
};

export function getProviderConnections(init: RequestInit = {}) {
    return getJson<Types.ProviderConnection[]>(`/api/account/connections`, { cache: "no-store", ...init });
}

export function getProviderConnectionsResultFromCookie(cookie: string | null) {
    return resultWithCookie<Types.ProviderConnection[]>(
        (init) => getProviderConnections(init),
        cookie,
    );
}

export function beginProviderConnection(provider: Types.ConnectedAccountProvider) {
    return postJson<{ url: string }>(`/api/account/connections/${provider}/authorize`, {});
}

export function pauseProviderConnection(provider: Types.ConnectedAccountProvider) {
    return postJson<Types.ProviderConnection>(`/api/account/connections/${provider}/pause`, {});
}

export function resumeProviderConnection(provider: Types.ConnectedAccountProvider) {
    return postJson<Types.ProviderConnection>(`/api/account/connections/${provider}/resume`, {});
}

export function disconnectProviderConnection(provider: Types.ConnectedAccountProvider, init: RequestInit = {}) {
    return deleteJson<void[]>(`/api/account/connections/${provider}`, init);
}

export function getCaptureOverview(init: RequestInit = {}) {
    return getJson<Types.CaptureOverview>("/api/account/connections/capture", {
        cache: "no-store",
        ...init,
    });
}

export function getCaptureOverviewResultFromCookie(cookie: string | null) {
    return resultWithCookie<Types.CaptureOverview>((init) => getCaptureOverview(init), cookie);
}

export function updateProviderCapturePolicy(
    provider: Types.ConnectedAccountProvider,
    policy: Types.ProviderCapturePolicy,
    init: RequestInit = {},
) {
    return putJson<Types.ProviderCaptureOverview>(
        `/api/account/connections/${provider}/capture-policy`,
        {
            enabled: policy.enabled,
            calendar: policy.calendar,
            mailInbox: policy.mailInbox,
            mailSent: policy.mailSent,
            backfillDays: policy.backfillDays,
            includeBodies: policy.includeBodies,
            admissionMode: policy.admissionMode,
            excludedPeople: policy.excludedPeople,
            excludedConversations: policy.excludedConversations,
            version: policy.version,
        },
        init,
    );
}

export function updateWorkspaceCapturePolicy(
    provider: Types.ConnectedAccountProvider,
    policy: Types.WorkspaceCapturePolicy,
    init: RequestInit = {},
) {
    return putJson<Types.ProviderCaptureOverview>(
        `/api/account/connections/${provider}/workspace-policy`,
        {
            allowed: policy.allowed,
            calendar: policy.calendar,
            mailInbox: policy.mailInbox,
            mailSent: policy.mailSent,
            maxBackfillDays: policy.maxBackfillDays,
            bodyCaptureAllowed: policy.bodyCaptureAllowed,
            reviewRequired: policy.reviewRequired,
            excludePrivateEvents: policy.excludePrivateEvents,
            excludeInternalOnly: policy.excludeInternalOnly,
            excludedDomains: policy.excludedDomains,
            version: policy.version,
        },
        init,
    );
}

export function syncProviderCapture(
    provider: Types.ConnectedAccountProvider,
    init: RequestInit = {},
) {
    return postJson<Types.ProviderCaptureOverview>(
        `/api/account/connections/${provider}/sync`,
        {},
        init,
    );
}

export function getCaptureReviews(
    provider: Types.ConnectedAccountProvider,
    page = 1,
    size = 20,
    init: RequestInit = {},
) {
    const params = new URLSearchParams({
        page: String(page),
        size: String(size),
    });
    return getJson<Types.CaptureReviewPage>(
        `/api/account/connections/${provider}/reviews?${params}`,
        { cache: "no-store", ...init },
    );
}

export function decideCaptureReview(
    provider: Types.ConnectedAccountProvider,
    reviewId: number,
    decision: Types.CaptureReviewDecision,
    init: RequestInit = {},
) {
    const body = decision.action === "create"
        ? {
            action: decision.action,
            version: decision.version,
            rememberExact: decision.rememberExact,
            contact: decision.contact,
            duplicateReviewToken: decision.duplicateReviewToken,
        }
        : decision;
    return postJson<Types.ProviderCaptureOverview>(
        `/api/account/connections/${provider}/reviews/${reviewId}`,
        body,
        init,
    );
}

export function approveCapturedItem(
    provider: Types.ConnectedAccountProvider,
    capturedItemId: number,
    version: number,
    init: RequestInit = {},
) {
    return postJson<Types.ProviderCaptureOverview>(
        `/api/account/connections/${provider}/captured/${capturedItemId}/approve`,
        { version },
        init,
    );
}

export function deleteCapturedProviderData(
    provider: Types.ConnectedAccountProvider,
    init: RequestInit = {},
) {
    return deleteJson<Types.CapturePurgeState>(
        `/api/account/connections/${provider}/captured-data`,
        init,
    );
}

export function discoverSso(email: string, init: RequestInit = {}) {
    return getJson<Types.SsoDiscovery>(
        `/api/auth/sso/discover?email=${encodeURIComponent(email)}`,
        { cache: "no-store", ...init },
    );
}

export function getSsoConfig(workspaceId: number, init: RequestInit = {}) {
    return getJson<Types.SsoConnectionDto>(`/api/auth/sso/config?workspaceId=${workspaceId}`, {
        cache: "no-store",
        ...init,
    });
}

export function saveSsoConfig(workspaceId: number, request: Types.SsoConnectionRequest) {
    return putJson<Types.SsoConnectionDto>(`/api/auth/sso/config?workspaceId=${workspaceId}`, request);
}

export function confirmSsoLink(token: string, password: string) {
    return withClientRequestIdentityReset(
        () => postJson<Types.AuthResponse>("/api/auth/sso/link/confirm", { token, password }),
    );
}

/**
 * The backend entry point a browser must fully navigate to (not fetch) to begin an
 * SP-initiated SSO login. OIDC starts under the proxied {@code /api} prefix; SAML uses
 * Spring's {@code /saml2} authenticate endpoint (rewritten to the backend in next.config).
 * @param registrationId the {@code org-<id>} registration from discovery
 * @param protocol the connection protocol
 * @returns the absolute path to navigate the browser to
 */
export function ssoStartPath(registrationId: string, protocol: Types.SsoProtocol) {
    const id = encodeURIComponent(registrationId);
    return protocol === "saml" ? `/saml2/authenticate/${id}` : `/api/oauth2/authorization/${id}`;
}

/*
* == Consumer social login
*/

/**
 * The backend entry point a browser must fully navigate to (not fetch) to begin a consumer
 * social login. Mirrors {@link ssoStartPath}; the provider's OAuth2 flow starts under the
 * proxied {@code /api} prefix.
 * @param provider the social provider to authenticate with
 * @returns the absolute path to navigate the browser to
 */
export function socialLoginStartPath(provider: "google" | "microsoft") {
    return `/api/oauth2/authorization/${provider}`;
}

/*
* == User profile management
*/

export function updateUser(id: number, payload: Types.UpdateUserPayload) {
    return putJson<Types.User>(`/api/users/${id}`, payload);
}

export function updateMyTimezone(timezone: string) {
    return patchJson<Types.User>("/api/users/me", { timezone });
}

export function updateMyLocale(locale: Types.User["locale"]) {
    return patchJson<Types.User>("/api/users/me/locale", { locale });
}

export async function uploadCurrentUserProfilePicture(file: File) {
    const formData = new FormData();
    formData.append("file", file);
    const user = await requestMultipart<Types.User>("/api/users/me/profile-picture", "PUT", formData);
    if (!user.profilePictureUrl) {
        throw new ApiError("Profile picture upload returned no URL", 502);
    }
    return user.profilePictureUrl;
}

export function createUser(payload: Types.RegisterPayload) {
    return postJson<Types.User>(`/api/users`, payload);
}

export function getUserById(id: number, init: RequestInit = {}) {
    return getJson<Types.User>(`/api/users/${id}`, init);
}

export async function getUserReferences(ids: number[], init: RequestInit = {}) {
    const uniqueIds = [...new Set(ids)];
    const batches = Array.from(
        { length: Math.ceil(uniqueIds.length / WORKSPACE_LIST_PAGE_SIZE) },
        (_, index) => uniqueIds.slice(
            index * WORKSPACE_LIST_PAGE_SIZE,
            (index + 1) * WORKSPACE_LIST_PAGE_SIZE,
        ),
    );
    const references = await Promise.all(batches.map((batch) =>
        getJson<Types.UserReference[]>(`/api/users/references${buildQuery({ ids: batch })}`, init),
    ));
    return references.flat();
}

export function getUsers(init: RequestInit = {}) {
    return getJson<Types.User[]>(`/api/users`, init);
}

export function getUsersFromCookie(cookie: string | null) {
    return safeReadWithCookie<Types.User>((init) => getUsers(init), cookie);
}

/*
* == User-associated records
*/

export function getUserTasks(id: number, init: RequestInit = {}) {
    return getJson<Types.Task[]>(`/api/users/${id}/tasks`, init);
}

export function getUserActivities(id: number, init: RequestInit = {}) {
    return getJson<Types.Activity[]>(`/api/users/${id}/activities`, init);
}

export function getUserNotes(id: number, init: RequestInit = {}) {
    return getJson<Types.Note[]>(`/api/users/${id}/notes`, init);
}

export function getUserTasksFromCookie(id: number, cookie: string | null) {
    return safeReadWithCookie<Types.Task>((init) => getUserTasks(id, init), cookie);
}

export function getUserActivitiesFromCookie(id: number, cookie: string | null) {
    return safeReadWithCookie<Types.Activity>((init) => getUserActivities(id, init), cookie);
}

export function getUserNotesFromCookie(id: number, cookie: string | null) {
    return safeReadWithCookie<Types.Note>((init) => getUserNotes(id, init), cookie);
}

/*
* == Task management
*/

export function getTasks(init: RequestInit = {}) {
    return getCompletePageItems<Types.Task>(getTasksPage, init);
}

export function getTasksPage(params: Types.PageParams = {}, init: RequestInit = {}) {
    return getJson<Types.Page<Types.Task>>(`/api/tasks/page${buildQuery(params)}`, init);
}

export function getTaskById(id: number, init: RequestInit = {}) {
    return getJson<Types.Task>(`/api/tasks/${id}`, init);
}

export function getTasksFromCookie(cookie: string | null) {
    return safeReadWithCookie<Types.Task>((init) => getTasks(init), cookie);
}

export function getTasksPageResultFromCookie(
    cookie: string | null,
    params: Types.PageParams = {},
) {
    return resultWithCookie<Types.Page<Types.Task>>((init) => getTasksPage(params, init), cookie);
}

/** Bounded due-date-ordered open-task preview for the dashboard. */
export function getUpcomingTasksFromCookie(cookie: string | null, limit = 4) {
    return getJson<Types.Task[]>(`/api/tasks/upcoming${buildQuery({ limit })}`, withCookie(cookie));
}

export function createTask(payload: Types.CreateTaskPayload, init: RequestInit = {}) {
    return postJson<Types.Task>(`/api/tasks`, payload, init);
}

export function deleteTask(id: number, init: RequestInit = {}) {
    return deleteJson<void[]>(`/api/tasks/${id}`, init);
}

export function updateTask(id: number, payload: Types.UpdateTaskPayload, init: RequestInit = {}) {
    return putJson<Types.Task>(`/api/tasks/${id}`, payload, init);
}

export function completeTask(id: number, init: RequestInit = {}) {
    return postJson<Types.Task>(`/api/tasks/${id}/complete`, {}, init);
}

/**
 * Moves a task to a target status column and 0-based position on the Kanban board. Dragging to
 * `done` completes the task (assignee-only on the server).
 */
export function moveTask(id: number, status: Types.TaskStatus, position: number, init: RequestInit = {}) {
    return postJson<Types.Task>(`/api/tasks/${id}/move`, { status, position }, init);
}

/**
 * Changes only a task's due date (a `YYYY-MM-DD` calendar day) without touching any other field.
 * Safe for optimistic reschedule; the server rejects a stale full-payload update, so this narrow
 * intent endpoint is used instead of `updateTask`.
 */
export function rescheduleTask(id: number, dueDate: string, init: RequestInit = {}) {
    return postJson<Types.Task>(`/api/tasks/${id}/reschedule`, { dueDate }, init);
}

/*
* == Activity management
*/

export function getActivities(init: RequestInit = {}) {
    return getCompletePageItems<Types.Activity>(
        (params, requestInit) => getActivitiesPage(params, requestInit),
        init,
    );
}

export function getActivitiesPage(params: Types.ActivitiesPageParams = {}, init: RequestInit = {}) {
    return getJson<Types.Page<Types.Activity>>(`/api/activities/page${buildQuery(params)}`, init);
}

export function getActivityById(id: number, init: RequestInit = {}) {
    return getJson<Types.Activity>(`/api/activities/${id}`, init);
}

export function getActivitiesFromCookie(cookie: string | null) {
    return safeReadWithCookie<Types.Activity>((init) => getActivities(init), cookie);
}

export function getActivitiesPageResultFromCookie(
    cookie: string | null,
    params: Types.ActivitiesPageParams = {},
) {
    return resultWithCookie<Types.Page<Types.Activity>>(
        (init) => getActivitiesPage(params, init),
        cookie,
    );
}

export function createActivity(payload: Types.CreateActivityPayload, init: RequestInit = {}) {
    return postJson<Types.Activity>(`/api/activities`, payload, init);
}

export function updateActivity(id: number, payload: Types.UpdateActivityPayload, init: RequestInit = {}) {
    return putJson<Types.Activity>(`/api/activities/${id}`, payload, init);
}

export function deleteActivity(id: number, init: RequestInit = {}) {
    return deleteJson<void[]>(`/api/activities/${id}`, init);
}

/*
* == Note management
*/

export function getNotes(init: RequestInit = {}) {
    return getCompletePageItems<Types.Note>(getNotesPage, init);
}

export function getNotesPage(params: Types.NotesPageParams = {}, init: RequestInit = {}) {
    return getJson<Types.Page<Types.Note>>(`/api/notes/page${buildQuery(params)}`, init);
}

/** Workspace-visible notes only, for shared analytics rather than personal note views. */
export function getWorkspaceNotes(init: RequestInit = {}) {
    return getCompletePageItems<Types.Note>(
        (params, requestInit) => getNotesPage({ ...params, workspaceOnly: true }, requestInit),
        init,
    );
}

export function getNotesFromCookie(cookie: string | null) {
    return safeReadWithCookie<Types.Note>((init) => getNotes(init), cookie);
}

export function getNotesPageResultFromCookie(
    cookie: string | null,
    params: Types.NotesPageParams = {},
) {
    return resultWithCookie<Types.Page<Types.Note>>((init) => getNotesPage(params, init), cookie);
}

export function getNoteById(id: number, init: RequestInit = {}) {
    return getJson<Types.Note>(`/api/notes/${id}`, init);
}

export function getNotesReferencing(refType: string, refId: number, init: RequestInit = {}) {
    return getJson<Types.Note[]>(
        `/api/notes/referencing?refType=${encodeURIComponent(refType)}&refId=${refId}`,
        init,
    );
}

export function createNote(payload: Types.CreateNotePayload, init: RequestInit = {}) {
    return postJson<Types.Note>(`/api/notes`, payload, init);
}

export function updateNote(id: number, payload: Types.UpdateNotePayload, init: RequestInit = {}) {
    return putJson<Types.Note>(`/api/notes/${id}`, payload, init);
}

export function deleteNote(id: number, init: RequestInit = {}) {
    return deleteJson<void[]>(`/api/notes/${id}`, init);
}

/*
* == Company management
*/

export function getCompanies(init: RequestInit = {}) {
    return getCompletePageItems<Types.Company>(
        (params, requestInit) => getCompaniesPage(params, requestInit),
        init,
    );
}

export function getCompaniesPage(params: Types.CompaniesPageParams = {}, init: RequestInit = {}) {
    return getJson<Types.Page<Types.Company>>(`/api/companies/page${buildQuery(params)}`, init);
}

export function getCompaniesPageResultFromCookie(
    cookie: string | null,
    params: Types.CompaniesPageParams = {},
) {
    return resultWithCookie<Types.Page<Types.Company>>(
        (init) => getCompaniesPage(params, init),
        cookie,
    );
}

/** Resolves a bounded id set in URL-safe batches for selector labels. */
export async function getCompaniesByIds(ids: number[], init: RequestInit = {}) {
    const unique = Array.from(new Set(ids.filter((id) => Number.isInteger(id) && id > 0)));
    if (unique.length === 0) return [] as Types.Company[];
    const pages = await Promise.all(
        Array.from({ length: Math.ceil(unique.length / WORKSPACE_LIST_PAGE_SIZE) }, (_, index) => {
            const batch = unique.slice(
                index * WORKSPACE_LIST_PAGE_SIZE,
                (index + 1) * WORKSPACE_LIST_PAGE_SIZE,
            );
            return getCompaniesPage({ ids: batch, size: WORKSPACE_LIST_PAGE_SIZE }, init);
        }),
    );
    return pages.flatMap((page) => page.items);
}

export function getCompaniesSegmentPage(params: Types.CompanySegmentPageParams, init: RequestInit = {}) {
    return postJson<Types.Page<Types.Company>>(`/api/companies/segment/page`, params, init);
}

export function getCompanyFacets(init: RequestInit = {}) {
    return getJson<Types.CompanyFacets>(`/api/companies/facets`, init);
}

/** Ids of every company matching an active filter, capped by the backend bulk-operation limit. */
export function getCompanyIds(params: Types.CompaniesPageParams = {}, init: RequestInit = {}) {
    const query = buildQuery({
        q: params.q, industry: params.industry, noIndustry: params.noIndustry, ids: params.ids,
        scope: params.scope, memberIds: params.memberIds, archived: params.archived,
    });
    return getJson<number[]>(`/api/companies/ids${query}`, init);
}

export function getCompanySegmentIds(params: Types.CompanySegmentPageParams, init: RequestInit = {}) {
    return postJson<number[]>(`/api/companies/segment/ids`, params, init);
}

export function getCompaniesFromCookie(cookie: string | null) {
    return safeReadWithCookie<Types.Company>((init) => getCompanies(init), cookie);
}

export function getCompanyById(id: number, init: RequestInit = {}) {
    return getJson<Types.Company>(`/api/companies/${id}`, init);
}

export function createCompany(payload: Types.CreateCompanyPayload, init: RequestInit = {}) {
    return postJson<Types.Company>(`/api/companies`, payload, init);
}

/** Checks proposed company values against visible canonical identities and exact names. */
export function preflightCompanyDuplicates(
    payload: Types.CompanyDuplicatePreflightRequest,
    init: RequestInit = {},
) {
    return postJson<Types.DuplicatePreflightResponse>(
        `/api/duplicate-preflight/companies`,
        payload,
        init,
    );
}

export function updateCompany(id: number, payload: Types.UpdateCompanyPayload) {
    return putJson<Types.Company>(`/api/companies/${id}`, payload);
}

export async function uploadCompanyLogo(companyId: number, file: File, init: RequestInit = {}) {
    const formData = new FormData();
    formData.append("file", file);
    const company = await requestMultipart<Types.Company>(`/api/companies/${companyId}/logo`, "PUT", formData, init);
    return company.logoUrl;
}

/** Archives a company: reversible, and the only way to remove one from the workspace (issue #854). */
export function archiveCompany(id: number, init: RequestInit = {}) {
    return postJson<Types.Company>(`/api/companies/${id}/archive`, {}, init);
}

/** Returns an archived company to the active working set. */
export function restoreCompany(id: number, init: RequestInit = {}) {
    return postJson<Types.Company>(`/api/companies/${id}/restore`, {}, init);
}

export function getCompanyPeople(id: number, init: RequestInit = {}) {
    return getJson<Types.Contact[]>(`/api/companies/${id}/people`, init);
}

export function getCompanyDeals(id: number, init: RequestInit = {}) {
    return getJson<Types.Deal[]>(`/api/companies/${id}/deals`, init);
}

export function getCompanyEngagement(id: number, init: RequestInit = {}) {
    return getJson<Types.CompanyEngagement>(`/api/companies/${id}/engagement`, init);
}

export function getCompanyTimeline(id: number, limit = 100, init: RequestInit = {}) {
    return getJson<Types.CompanyTimeline>(
        `/api/companies/${id}/timeline${buildQuery({ limit })}`,
        init,
    );
}

export function getCompanyTags(id: number, init: RequestInit = {}) {
    return getJson<Types.Tag[]>(`/api/companies/${id}/tags`, init);
}

export function addCompanyTag(id: number, tagId: number, init: RequestInit = {}) {
    return postJson<void>(`/api/companies/${id}/tags/${tagId}`, {}, init);
}

export function removeCompanyTag(id: number, tagId: number, init: RequestInit = {}) {
    return deleteJson<void>(`/api/companies/${id}/tags/${tagId}`, init);
}

/*
* == Contact management
*/

export function getContacts(filters: Types.ContactFilters = {}, init: RequestInit = {}) {
    if (!hasQueryValues(filters)) {
        return getCompletePageItems<Types.Contact>(
            (params, requestInit) => getContactsPage(params, requestInit),
            init,
        );
    }
    return getJson<Types.Contact[]>(`/api/persons${buildQuery(filters)}`, init);
}

export function getContactsFromCookie(cookie: string | null, filters: Types.ContactFilters = {}) {
    return safeReadWithCookie<Types.Contact>((init) => getContacts(filters, init), cookie);
}

export function getContactsPage(params: Types.ContactsPageParams = {}, init: RequestInit = {}) {
    return getJson<Types.Page<Types.Contact>>(`/api/persons/page${buildQuery(params)}`, init);
}

export function getContactsPageResultFromCookie(
    cookie: string | null,
    params: Types.ContactsPageParams = {},
) {
    return resultWithCookie<Types.Page<Types.Contact>>(
        (init) => getContactsPage(params, init),
        cookie,
    );
}

/*
* == CSV import / export
*/

export function previewImport(entity: Types.ImportEntity, body: Types.ImportRequest, init: RequestInit = {}) {
    return postJson<Types.ImportPreviewResult>(`/api/imports/${entity}/preview`, body, init);
}

export function commitImport(entity: Types.ImportEntity, body: Types.ImportRequest, init: RequestInit = {}) {
    return postJson<Types.ImportResult>(`/api/imports/${entity}`, body, init);
}

export function previewInteractionHistoryImport(
    kind: Types.HistoryImportKind,
    body: Types.HistoryImportRequest,
    init: RequestInit = {},
) {
    return postJson<Types.HistoryImportPreviewResult>(
        `/api/imports/history/${kind}/preview`,
        body,
        init,
    );
}

export function commitInteractionHistoryImport(
    kind: Types.HistoryImportKind,
    body: Types.HistoryImportRequest,
    init: RequestInit = {},
) {
    return postJson<Types.HistoryImportResult>(`/api/imports/history/${kind}`, body, init);
}

/**
 * Streams a CSV from the backend with locale and workspace headers (a plain anchor would not carry
 * the workspace context) and triggers a browser download. Callers may pin a workspace header for an
 * operation that must remain bound to its invocation scope.
 */
export async function downloadCsv(path: string, filename: string, init: RequestInit = {}): Promise<void> {
    const locale = localeFromCookieHeader(document.cookie);
    const workspaceId = clientWorkspaceId();
    const mutating = isMutating(init.method);
    const send = (csrf: Record<string, string>) => {
        const headers = new Headers({
            ...(init.body ? { "Content-Type": "application/json" } : {}),
            "Accept-Language": locale,
            ...(workspaceId ? { "X-Workspace-Id": workspaceId } : {}),
            ...csrf,
        });
        new Headers(init.headers).forEach((value, key) => headers.set(key, value));
        return fetch(`${API_BASE}${path}`, {
            ...init,
            credentials: "include",
            headers,
        });
    };
    let res = await send(mutating ? await csrfHeader() : {});
    if (await shouldRetryWithFreshCsrf(path, res, mutating)) {
        res = await send(await csrfHeader(true));
    }
    if (!res.ok) {
        throw await getApiError(res);
    }
    const blob = await res.blob();
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = filename;
    document.body.appendChild(anchor);
    anchor.click();
    anchor.remove();
    URL.revokeObjectURL(url);
}

export function exportContactsCsv(params: Types.ContactsPageParams = {}, init: RequestInit = {}) {
    const query = buildQuery({
        q: params.q, companies: params.companies, titles: params.titles, noCompany: params.noCompany,
        scope: params.scope, memberIds: params.memberIds,
    });
    return downloadCsv(`/api/exports/persons${query}`, "contacts.csv", init);
}

export function exportCompaniesCsv(params: Types.CompaniesPageParams = {}, init: RequestInit = {}) {
    const query = buildQuery({
        q: params.q, industry: params.industry, noIndustry: params.noIndustry, ids: params.ids,
        scope: params.scope, memberIds: params.memberIds,
    });
    return downloadCsv(`/api/exports/companies${query}`, "companies.csv", init);
}

export function exportDealsCsv(params: Types.DealFilterParams = {}, init: RequestInit = {}) {
    const query = buildQuery({
        q: params.q, currency: params.currency, pipelineId: params.pipelineId, stageId: params.stageId,
        companyId: params.companyId, noCompany: params.noCompany, status: params.status, risk: params.risk,
        scope: params.scope, memberIds: params.memberIds,
    });
    return downloadCsv(`/api/exports/deals${query}`, "deals.csv", init);
}

export function exportProductsCsv(params: Types.ProductSearchParams = {}, init: RequestInit = {}) {
    return downloadCsv(`/api/exports/products${buildQuery({ q: params.q })}`, "products.csv", init);
}

export function exportDealSegmentCsv(params: Types.DealSegmentPageParams, init: RequestInit = {}) {
    return downloadCsv(`/api/exports/deals/segment`, "deals.csv", {
        ...init,
        method: "POST",
        body: JSON.stringify(params),
    });
}

export function getPersonFacets(init: RequestInit = {}) {
    return getJson<Types.PersonFacets>(`/api/persons/facets`, init);
}

/*
* == Bulk operations
*/

/**
 * The backend bounds a single bulk request to 1000 ids. Larger selections are split into
 * sequential chunks and their results merged, so the caller always gets one combined outcome with
 * {@link Types.BulkOperationError.rowIndex} offset to the position in the full id list.
 */
const BULK_CHUNK_SIZE = 1000;

async function runBulk(
    ids: number[],
    call: (chunk: number[]) => Promise<Types.BulkOperationResult>,
): Promise<Types.BulkOperationResult> {
    if (ids.length === 0) return { succeeded: 0, failed: 0, errors: [] };
    if (ids.length <= BULK_CHUNK_SIZE) return call(ids);
    const merged: Types.BulkOperationResult = { succeeded: 0, failed: 0, errors: [] };
    for (let offset = 0; offset < ids.length; offset += BULK_CHUNK_SIZE) {
        const chunk = ids.slice(offset, offset + BULK_CHUNK_SIZE);
        const result = await call(chunk);
        merged.succeeded += result.succeeded;
        merged.failed += result.failed;
        for (const error of result.errors) {
            merged.errors.push({ rowIndex: error.rowIndex + offset, reason: error.reason });
        }
    }
    return merged;
}

export function bulkAddTagToContacts(ids: number[], tagId: number) {
    return runBulk(ids, (chunk) => postJson<Types.BulkOperationResult>(`/api/persons/bulk/tags/add`, { ids: chunk, tagId }));
}

export function bulkRemoveTagFromContacts(ids: number[], tagId: number) {
    return runBulk(ids, (chunk) => postJson<Types.BulkOperationResult>(`/api/persons/bulk/tags/remove`, { ids: chunk, tagId }));
}

export function bulkArchiveContacts(ids: number[]) {
    return runBulk(ids, (chunk) => postJson<Types.BulkOperationResult>(`/api/persons/bulk/archive`, { ids: chunk }));
}

export function bulkRestoreContacts(ids: number[]) {
    return runBulk(ids, (chunk) => postJson<Types.BulkOperationResult>(`/api/persons/bulk/restore`, { ids: chunk }));
}

export function bulkAddTagToCompanies(ids: number[], tagId: number) {
    return runBulk(ids, (chunk) => postJson<Types.BulkOperationResult>(`/api/companies/bulk/tags/add`, { ids: chunk, tagId }));
}

export function bulkRemoveTagFromCompanies(ids: number[], tagId: number) {
    return runBulk(ids, (chunk) => postJson<Types.BulkOperationResult>(`/api/companies/bulk/tags/remove`, { ids: chunk, tagId }));
}

export function bulkArchiveCompanies(ids: number[]) {
    return runBulk(ids, (chunk) => postJson<Types.BulkOperationResult>(`/api/companies/bulk/archive`, { ids: chunk }));
}

export function bulkRestoreCompanies(ids: number[]) {
    return runBulk(ids, (chunk) => postJson<Types.BulkOperationResult>(`/api/companies/bulk/restore`, { ids: chunk }));
}

export function bulkAssignCompanyOwner(ids: number[], ownerId: number | null) {
    return runBulk(ids, (chunk) => postJson<Types.BulkOperationResult>(`/api/companies/bulk/owner`, { ids: chunk, ownerId }));
}

export function bulkAssignPersonOwner(ids: number[], ownerId: number | null) {
    return runBulk(ids, (chunk) => postJson<Types.BulkOperationResult>(`/api/persons/bulk/owner`, { ids: chunk, ownerId }));
}

export function updateCompanyOwner(id: number, ownerId: number | null) {
    return putJson<Types.Company>(`/api/companies/${id}/owner`, { ownerId });
}

export function updatePersonOwner(id: number, ownerId: number | null) {
    return putJson<Types.Contact>(`/api/persons/${id}/owner`, { ownerId });
}

export function bulkAddTagToDeals(ids: number[], tagId: number) {
    return runBulk(ids, (chunk) => postJson<Types.BulkOperationResult>(`/api/deals/bulk/tags/add`, { ids: chunk, tagId }));
}

export function bulkRemoveTagFromDeals(ids: number[], tagId: number) {
    return runBulk(ids, (chunk) => postJson<Types.BulkOperationResult>(`/api/deals/bulk/tags/remove`, { ids: chunk, tagId }));
}

export function bulkDeleteDeals(ids: number[]) {
    return runBulk(ids, (chunk) => postJson<Types.BulkOperationResult>(`/api/deals/bulk/delete`, { ids: chunk }));
}

export function bulkAssignDealOwner(ids: number[], ownerId: number | null) {
    return runBulk(ids, (chunk) => postJson<Types.BulkOperationResult>(`/api/deals/bulk/owner`, { ids: chunk, ownerId }));
}

export function bulkChangeDealStage(ids: number[], stageId: number) {
    return runBulk(ids, (chunk) => postJson<Types.BulkOperationResult>(`/api/deals/bulk/stage`, { ids: chunk, stageId }));
}

/** Ids of every contact matching an active filter, capped by the backend bulk-operation limit. */
export function getContactIds(params: Types.ContactsPageParams = {}, init: RequestInit = {}) {
    const query = buildQuery({
        q: params.q, companies: params.companies, titles: params.titles, noCompany: params.noCompany,
        scope: params.scope, memberIds: params.memberIds, archived: params.archived,
    });
    return getJson<number[]>(`/api/persons/ids${query}`, init);
}

export function getContactById(id: number, init: RequestInit = {}) {
    return getJson<Types.Contact>(`/api/persons/${id}`, init);
}

export function getContactEmployment(id: number, init: RequestInit = {}) {
    return getJson<Types.PersonEmployment[]>(`/api/persons/${id}/employment`, init);
}

export function getRecentMoves(init: RequestInit = {}) {
    return getJson<Types.JobMove[]>(`/api/persons/recent-moves`, init);
}

export function getRecentMovesFromCookie(cookie: string | null) {
    return safeReadWithCookie<Types.JobMove>((init) => getRecentMoves(init), cookie);
}

export function getRecentMovesResultFromCookie(cookie: string | null) {
    return resultWithCookie<Types.JobMove[]>((init) => getRecentMoves(init), cookie);
}

export function getContactConnections(id: number, init: RequestInit = {}) {
    return getJson<Types.PersonConnection[]>(`/api/persons/${id}/connections`, init);
}

export function addContactConnection(id: number, payload: Types.ConnectionPayload, init: RequestInit = {}) {
    return postJson<void>(`/api/persons/${id}/connections`, payload, init);
}

export function removeContactConnection(id: number, targetId: number, init: RequestInit = {}) {
    return deleteJson<void>(`/api/persons/${id}/connections/${targetId}`, init);
}

export function getContactIntroPath(id: number, init: RequestInit = {}) {
    return getJson<Types.IntroPath>(`/api/persons/${id}/intro-path`, init);
}

/*
* == Relationship temperature (warmth) scoring
*/

export async function getContactTemperatures(ids: number[], init: RequestInit = {}) {
    if (ids.length === 0) return Promise.resolve([] as Types.RelationshipTemperature[]);
    const batches = await Promise.all(
        Array.from({ length: Math.ceil(ids.length / WORKSPACE_LIST_PAGE_SIZE) }, (_, index) =>
            getJson<Types.RelationshipTemperature[]>(
                `/api/scoring/contacts${buildQuery({
                    ids: ids.slice(index * WORKSPACE_LIST_PAGE_SIZE, (index + 1) * WORKSPACE_LIST_PAGE_SIZE),
                })}`,
                init,
            )),
    );
    return batches.flat().sort((left, right) => right.score - left.score);
}

export function getContactEvidence(id: number, init: RequestInit = {}) {
    return getJson<Types.RelationshipEvidence>(`/api/scoring/contacts/${id}/evidence`, init);
}

export function getContactTemperaturesFromCookie(cookie: string | null, ids: number[]) {
    if (ids.length === 0) return Promise.resolve([] as Types.RelationshipTemperature[]);
    return safeReadWithCookie<Types.RelationshipTemperature>((init) => getContactTemperatures(ids, init), cookie);
}

export function getContactTemperaturesResultFromCookie(cookie: string | null, ids: number[]) {
    if (ids.length === 0) {
        return Promise.resolve({ ok: true as const, data: [] as Types.RelationshipTemperature[] });
    }
    return resultWithCookie<Types.RelationshipTemperature[]>(
        (init) => getContactTemperatures(ids, init),
        cookie,
    );
}

export function getCoolingContactTemperaturesFromCookie(cookie: string | null, limit = 6) {
    return getJson<Types.RelationshipTemperature[]>(
        `/api/scoring/contacts/cooling${buildQuery({ limit })}`,
        withCookie(cookie),
    );
}

export async function getCompanyTemperatures(ids: number[], init: RequestInit = {}) {
    if (ids.length === 0) return Promise.resolve([] as Types.RelationshipTemperature[]);
    return postJson<Types.RelationshipTemperature[]>(`/api/scoring/companies/batch`, { ids }, init);
}

export function getCompanyEvidence(id: number, init: RequestInit = {}) {
    return getJson<Types.RelationshipEvidence>(`/api/scoring/companies/${id}/evidence`, init);
}

export function getMapCompanyTemperatures(init: RequestInit = {}) {
    return getJson<Types.RelationshipTemperature[]>(`/api/scoring/companies/map`, init);
}

export function getCoolingCompanyTemperaturesFromCookie(cookie: string | null, limit = 6) {
    return getJson<Types.RelationshipTemperature[]>(
        `/api/scoring/companies/cooling${buildQuery({ limit })}`,
        withCookie(cookie),
    );
}

/*
* == Map replay (time-travel, #48)
*/

export function getMapReplay(params: Types.ReplayParams, init: RequestInit = {}) {
    return getJson<Types.MapReplay>(`/api/map/replay${buildQuery({ ...params })}`, init);
}

/*
* == Reverse introductions (the "give side" of the graph)
*/

export function getIntroSuggestions(init: RequestInit = {}, limit?: number) {
    return getJson<Types.IntroSuggestion[]>(`/api/introductions/suggestions${buildQuery({ limit })}`, init);
}

/**
 * AI-generated "why introduce them" rationale for a suggested reverse introduction. Graceful domain
 * unavailability resolves normally; provider failure and timeout reject distinctly.
 */
export function generateIntroRationale(personAId: number, personBId: number, init: RequestInit = {}) {
    return dedupedAiPost<Types.IntroRationale>(
        `/api/introductions/suggestions/rationale${buildQuery({ personA: personAId, personB: personBId })}`,
        init,
    );
}

export function getIntroSuggestionsFromCookie(cookie: string | null, limit?: number) {
    return safeReadWithCookie<Types.IntroSuggestion>((init) => getIntroSuggestions(init, limit), cookie);
}

export function getIntroSuggestionsResultFromCookie(cookie: string | null, limit?: number) {
    return resultWithCookie<Types.IntroSuggestion[]>(
        (init) => getIntroSuggestions(init, limit),
        cookie,
    );
}

/**
 * Failure-aware variant of {@link getIntroductions} for the introductions page (see
 * {@link resultWithCookie}), so a lineage fetch failure is not presented as zero intros made.
 */
export function getIntroductionsResultFromCookie(
    cookie: string | null,
    params: { page?: number; size?: number } = {},
) {
    return resultWithCookie<Types.Page<Types.IntroductionRecord>>(
        (init) => getIntroductions(params, init),
        cookie,
    );
}

export function getIntroductions(params: { page?: number; size?: number } = {}, init: RequestInit = {}) {
    return getJson<Types.Page<Types.IntroductionRecord>>(`/api/introductions${buildQuery(params)}`, init);
}

export function recordIntroduction(payload: Types.IntroductionPayload, init: RequestInit = {}) {
    return postJson<Types.IntroductionRecord>(`/api/introductions`, payload, init);
}

export function dismissIntroSuggestion(payload: Types.IntroductionPayload, init: RequestInit = {}) {
    return postJson<void>(`/api/introductions/dismiss`, payload, init);
}

/*
* == Warm paths (the "receive side" of the graph, #614)
*/

/** The combined introductions feed — suggestions + warm paths from one backend warmth pass. */
export function getIntroOverview(init: RequestInit = {}, suggestions?: number, paths?: number) {
    return getJson<Types.IntroOverview>(
        `/api/introductions/overview${buildQuery({ suggestions, paths })}`,
        init,
    );
}

/**
 * Failure-aware overview fetch for the introductions page (see {@link resultWithCookie}), so a
 * backend fault renders as error states instead of an empty page.
 */
export function getIntroOverviewResultFromCookie(
    cookie: string | null,
    suggestions?: number,
    paths?: number,
) {
    return resultWithCookie<Types.IntroOverview>(
        (init) => getIntroOverview(init, suggestions, paths),
        cookie,
    );
}

/** Accepts a warm path: the backend creates the follow-up task and retires the avenue. */
export function acceptWarmPath(payload: Types.WarmPathPayload, init: RequestInit = {}) {
    return postJson<Types.Task>(`/api/introductions/paths/accept`, payload, init);
}

/** Dismisses one avenue when {@code bridgePersonId} is set, otherwise every path to the target. */
export function dismissWarmPath(payload: Types.WarmPathPayload, init: RequestInit = {}) {
    return postJson<void>(`/api/introductions/paths/dismiss`, payload, init);
}

export type RadarCookieResult =
    | { ok: true; data: Types.RadarPayload }
    | { ok: false; status: number };

/** Reads the canonical relationship-signal feed without collapsing refusal and failure into empty data. */
export function getRadar(init: RequestInit = {}) {
    return getJson<Types.RadarPayload>('/api/radar', { cache: 'no-store', ...init });
}

/** Server-side Radar read that preserves the HTTP status required for honest route states. */
export async function getRadarResultFromCookie(cookie: string | null): Promise<RadarCookieResult> {
    if (!cookie) return { ok: false, status: 401 };
    try {
        return { ok: true, data: await getRadar({ headers: { cookie }, cache: 'no-store' }) };
    } catch (error) {
        return { ok: false, status: error instanceof ApiError ? error.status : 503 };
    }
}

function radarVersionInit(version: string, init: RequestInit = {}): RequestInit {
    const headers = new Headers(init.headers);
    headers.set('If-Match', `"${version}"`);
    return { ...init, headers };
}

export function followRadarSignal(id: number, version: string, init: RequestInit = {}) {
    return postJson<Types.RadarSignal>(
        `/api/radar/${id}/follow`,
        {},
        radarVersionInit(version, init),
    );
}

export function snoozeRadarSignal(id: number, version: string, until: string, init: RequestInit = {}) {
    return postJson<Types.RadarSignal>(
        `/api/radar/${id}/snooze`,
        { until },
        radarVersionInit(version, init),
    );
}

export function dismissRadarSignal(id: number, version: string, init: RequestInit = {}) {
    return postJson<Types.RadarSignal>(
        `/api/radar/${id}/dismiss`,
        {},
        radarVersionInit(version, init),
    );
}

export function createRadarTask(
    id: number,
    version: string,
    payload: Types.RadarTaskPayload,
    init: RequestInit = {},
) {
    return postJson<Types.RadarSignal>(
        `/api/radar/${id}/tasks`,
        payload,
        radarVersionInit(version, init),
    );
}

export function getRadarContext(id: number, init: RequestInit = {}) {
    return getJson<Types.RadarContext>(`/api/radar/${id}/context`, init);
}

export function createContact(payload: Types.CreateContactPayload, init: RequestInit = {}) {
    return postJson<Types.Contact>(`/api/persons`, payload, init);
}

/** Checks proposed contact values against visible canonical identities and exact names. */
export function preflightPersonDuplicates(
    payload: Types.PersonDuplicatePreflightRequest,
    init: RequestInit = {},
) {
    return postJson<Types.DuplicatePreflightResponse>(
        `/api/duplicate-preflight/persons`,
        payload,
        init,
    );
}

/** Reads business-card readiness for the authorized active workspace. */
export function getBusinessCardAvailability(init: RequestInit = {}) {
    return getJson<Types.BusinessCardAvailability>("/api/business-cards/availability", {
        cache: "no-store",
        ...init,
    });
}

/** Reads contact candidates from one business-card image without mutating workspace data. */
export function scanBusinessCard(
    image: File,
    context: Types.BusinessCardRecoveryContext,
    init: RequestInit = {},
) {
    const body = new FormData();
    body.append("image", image, image.name);
    return postFormData<Types.BusinessCardScanResult>(
        "/api/business-cards/scan",
        body,
        businessCardRequestInit(context, init),
    );
}

/** Reserves an opaque import key before private multipart content is submitted. */
export function reserveBusinessCardImport(
    requestId: string,
    context: Types.BusinessCardRecoveryContext,
    init: RequestInit = {},
) {
    const boundInit = businessCardRequestInit(context, init);
    const headers = new Headers(boundInit.headers);
    headers.set("Idempotency-Key", requestId);
    return withBusinessCardRequestTimeout(
        10_000,
        init.signal,
        async (signal) => {
            await requireBusinessCardRecoveryContext(context, signal);
            return postJson<Types.BusinessCardImportReservation>(
                "/api/business-cards/import/reservation",
                {},
                { ...boundInit, headers, signal },
            );
        },
    );
}

/** Creates or reuses the reviewed contact and stores its source card in one backend transaction. */
export function importBusinessCard(draft: Types.BusinessCardImportDraft, init: RequestInit = {}) {
    const body = new FormData();
    body.append("image", draft.image, draft.image.name);
    body.append("contact", new Blob([JSON.stringify(draft.contact)], { type: "application/json" }));
    body.append("personAction", new Blob([JSON.stringify(draft.personAction)], { type: "application/json" }));
    body.append("companyAction", new Blob([JSON.stringify(draft.companyAction)], { type: "application/json" }));
    const boundInit = businessCardRequestInit(draft.recoveryContext, init);
    const headers = new Headers(boundInit.headers);
    headers.set("Idempotency-Key", draft.requestId);
    return withBusinessCardRequestTimeout(
        30_000,
        init.signal,
        async (signal) => {
            await requireBusinessCardRecoveryContext(draft.recoveryContext, signal);
            return postFormData<Types.BusinessCardImportResult>(
                "/api/business-cards/import",
                body,
                { ...boundInit, headers, signal },
            );
        },
    );
}

/** Reconciles a completed import without resubmitting private card or contact content. */
export function getBusinessCardImportStatus(
    requestId: string,
    context: Types.BusinessCardRecoveryContext,
    init: RequestInit = {},
) {
    const boundInit = businessCardRequestInit(context, init);
    const headers = new Headers(boundInit.headers);
    headers.set("Idempotency-Key", requestId);
    return withBusinessCardRequestTimeout(
        10_000,
        init.signal,
        async (signal) => {
            await requireBusinessCardRecoveryContext(context, signal);
            return getJson<Types.BusinessCardImportResult>("/api/business-cards/import", {
                ...boundInit,
                cache: "no-store",
                headers,
                signal,
            });
        },
    );
}

/** Archives a contact: reversible, and the only way to remove one from the workspace (issue #854). */
export function archiveContact(id: number, init: RequestInit = {}) {
    return postJson<Types.Contact>(`/api/persons/${id}/archive`, {}, init);
}

/** Returns an archived contact to the active working set. */
export function restoreContact(id: number, init: RequestInit = {}) {
    return postJson<Types.Contact>(`/api/persons/${id}/restore`, {}, init);
}

export function updateContact(id: number, payload: Types.UpdateContactPayload) {
    return putJson<Types.Contact>(`/api/persons/${id}`, payload);
}

export async function uploadContactPicture(contactId: number, file: File, init: RequestInit = {}) {
    const formData = new FormData();
    formData.append("file", file);
    const contact = await requestMultipart<Types.Contact>(`/api/persons/${contactId}/profile-picture`, "PUT", formData, init);
    return contact.imageUrl;
}

export function updateContactEvaluation(id: number, payload: Types.UpdateContactEvaluationPayload) {
    return putJson<Types.Contact>(`/api/persons/${id}/evaluation`, payload);
}

export function getContactTags(id: number, init: RequestInit = {}) {
    return getJson<Types.Tag[]>(`/api/persons/${id}/tags`, init);
}
export function getContactTagsFromCookie(id: number, cookie: string | null) {
    return safeReadWithCookie<Types.Tag>((init) => getContactTags(id, init), cookie);
}

export function addContactTag(id: number, tagId: number, init: RequestInit = {}) {
    return postJson<void>(`/api/persons/${id}/tags/${tagId}`, {}, init);
}

export function removeContactTag(id: number, tagId: number, init: RequestInit = {}) {
    return deleteJson<void>(`/api/persons/${id}/tags/${tagId}`, init);
}

export function replaceContactTags(id: number, tagIds: number[], init: RequestInit = {}) {
    return putJson<Types.Tag[]>(`/api/persons/${id}/tags`, tagIds, init);
}

export function getContactDeals(id: number, init: RequestInit = {}) {
    return getJson<Types.Deal[]>(`/api/persons/${id}/deals`, init);
}
export function getContactDealsFromCookie(id: number, cookie: string | null) {
    return safeReadWithCookie<Types.Deal>((init) => getContactDeals(id, init), cookie);
}

export function getContactActivities(id: number, init: RequestInit = {}) {
    return getJson<Types.Activity[]>(`/api/persons/${id}/activities`, init);
}
export function getContactActivitiesFromCookie(id: number, cookie: string | null) {
    return safeReadWithCookie<Types.Activity>((init) => getContactActivities(id, init), cookie);
}

export function getContactNotes(id: number, init: RequestInit = {}) {
    return getJson<Types.Note[]>(`/api/persons/${id}/notes`, init);
}
export function getContactNotesFromCookie(id: number, cookie: string | null) {
    return safeReadWithCookie<Types.Note>((init) => getContactNotes(id, init), cookie);
}

export function getContactTasks(id: number, init: RequestInit = {}) {
    return getJson<Types.Task[]>(`/api/persons/${id}/tasks`, init);
}
export function getContactTasksFromCookie(id: number, cookie: string | null) {
    return safeReadWithCookie<Types.Task>((init) => getContactTasks(id, init), cookie);
}

/*
* == Deal management
*/

export function getDeals(init: RequestInit = {}) {
    return getCompletePageItems<Types.Deal>(getDealsPage, init);
}

export function getDealsPage(params: Types.DealsPageParams = {}, init: RequestInit = {}) {
    return getJson<Types.Page<Types.Deal>>(`/api/deals/page${buildQuery(params)}`, init);
}

export function getDealsSegmentPage(params: Types.DealSegmentPageParams, init: RequestInit = {}) {
    return postJson<Types.Page<Types.Deal>>(`/api/deals/segment/page`, params, init);
}

/** Ids of every deal matching the active filters, capped by the backend bulk-operation limit. */
export function getDealIds(params: Types.DealsPageParams = {}, init: RequestInit = {}) {
    const query = buildQuery({
        q: params.q, currency: params.currency, pipelineId: params.pipelineId, stageId: params.stageId,
        companyId: params.companyId, noCompany: params.noCompany, status: params.status, risk: params.risk,
        scope: params.scope, memberIds: params.memberIds,
    });
    return getJson<number[]>(`/api/deals/ids${query}`, init);
}

export function getDealSegmentIds(params: Types.DealSegmentPageParams, init: RequestInit = {}) {
    return postJson<number[]>(`/api/deals/segment/ids`, params, init);
}

/** Returns one complete, server-bounded pipeline board for absolute Kanban ordering. */
export function getDealBoard(pipelineId: number, init: RequestInit = {}) {
    return getJson<Types.Deal[]>(`/api/deals/board${buildQuery({ pipelineId })}`, init);
}

export function getDealsFromCookie(cookie: string | null) {
    return safeReadWithCookie<Types.Deal>((init) => getDeals(init), cookie);
}

export function getDealsPageResultFromCookie(
    cookie: string | null,
    params: Types.DealsPageParams = {},
) {
    return resultWithCookie<Types.Page<Types.Deal>>((init) => getDealsPage(params, init), cookie);
}

/**
 * Workspace-wide deal totals (open pipeline, closed forecast, realized revenue, counts),
 * grouped per currency, computed server-side over ALL matching deals — not just a page.
 * Optional filter params narrow the aggregation to match a filtered table view.
 */
export function getDealMetrics(params: Types.DealFilterParams = {}, init: RequestInit = {}) {
    return getJson<Types.DealMetrics>(`/api/deals/metrics${buildQuery(params)}`, init);
}

export function getDealSegmentMetrics(params: Types.DealSegmentPageParams, init: RequestInit = {}) {
    return postJson<Types.DealMetrics>(`/api/deals/segment/metrics`, params, init);
}

export function getDealMetricsFromCookie(cookie: string | null, params: Types.DealFilterParams = {}) {
    return getJson<Types.DealMetrics>(
        `/api/deals/metrics${buildQuery(params)}`,
        cookie ? { headers: { cookie }, cache: "no-store" } : {},
    );
}

export function getDealMetricsResultFromCookie(
    cookie: string | null,
    params: Types.DealFilterParams = {},
) {
    return resultWithCookie<Types.DealMetrics>(
        (init) => getJson<Types.DealMetrics>(`/api/deals/metrics${buildQuery(params)}`, init),
        cookie,
    );
}

/**
 * Stable filter-facet vocabulary (status, stage, pipeline, company, currency, owners) with
 * counts, computed server-side over the whole workspace so options never vanish when the
 * visible page lacks them (e.g. the "Closed" status option). The owners facet in particular
 * always reflects all-team counts — including the `__empty__` unassigned bucket — regardless
 * of any active member scope, so the owner picker stays complete while a scope is applied.
 */
export function getDealFacets(init: RequestInit = {}) {
    return getJson<Types.DealFacets>(`/api/deals/facets`, init);
}

/**
 * Server-computed monthly revenue trend (realized won revenue by scheduled close month, projected
 * by expected-close month) over ALL deals, optionally scoped to a currency. The IANA timezone
 * applies the viewer's historical offset rules when bucketing realized revenue.
 */
export function getDealRevenueTimeseries(
    currency?: string, timezone?: string, scope: Types.MemberScopeParams = {}, init: RequestInit = {},
) {
    return getJson<Types.DealRevenueSeries>(
        `/api/deals/revenue-timeseries${buildQuery({ currency, timezone, ...scope })}`, init);
}

/**
 * Server-computed calendar-bucketed revenue series (realized won revenue vs projected value by
 * expected close) over an explicit window at day/week/month granularity, zero-filled per bucket.
 */
export function getDealRevenueSeries(
    window: Types.AnalyticsWindowParams,
    currency?: string,
    scope: Types.MemberScopeParams = {},
    init: RequestInit = {},
) {
    return getJson<Types.DealRevenuePeriodSeries>(
        `/api/deals/revenue-series${buildQuery({ currency, ...scope, ...window })}`, init);
}

const withCookie = (cookie: string | null): RequestInit => (cookie ? { headers: { cookie }, cache: "no-store" } : {});

/**
 * Server-computed deal KPIs over ALL deals, optionally scoped to a currency. Windowed calls pass
 * {@code window} (calendar-aligned from/to + granularity, superseding {@code range}); legacy calls
 * pass {@code range} (30d/90d/12m). Replaces the client-side KPI/win-rate math over a bounded page slice.
 */
export function getDealKpis(
    currency?: string,
    range?: string,
    scope: Types.MemberScopeParams = {},
    window?: Types.AnalyticsWindowParams,
    init: RequestInit = {},
) {
    return getJson<Types.DealKpis>(
        `/api/deals/kpis${buildQuery({ currency, range: window ? undefined : range, ...scope, ...window })}`,
        init,
    );
}

export function getDealKpisFromCookie(cookie: string | null, currency?: string, range?: string) {
    return getJson<Types.DealKpis>(`/api/deals/kpis${buildQuery({ currency, range })}`, withCookie(cookie));
}

/** Server-computed per-pipeline won-in-range + open rollup; {@code window} bounds the won window. */
export function getDealPipelineValue(
    currency?: string,
    range?: string,
    scope: Types.MemberScopeParams = {},
    window?: Types.AnalyticsWindowParams,
    init: RequestInit = {},
) {
    const windowParams = window
        ? { from: window.from, to: window.to, timezone: window.timezone }
        : { range };
    return getJson<Types.DealPipelineValue[]>(
        `/api/deals/pipeline-value${buildQuery({ currency, ...scope, ...windowParams })}`, init);
}

export function getDealPipelineValueFromCookie(cookie: string | null, currency?: string, range?: string) {
    return getJson<Types.DealPipelineValue[]>(`/api/deals/pipeline-value${buildQuery({ currency, range })}`, withCookie(cookie));
}

/** Server-computed per-stage open-deal age buckets. */
export function getDealAging(currency?: string, scope: Types.MemberScopeParams = {}, init: RequestInit = {}) {
    return getJson<Types.DealAging[]>(`/api/deals/aging${buildQuery({ currency, ...scope })}`, init);
}

export function getDealAgingFromCookie(cookie: string | null, currency?: string) {
    return getJson<Types.DealAging[]>(`/api/deals/aging${buildQuery({ currency })}`, withCookie(cookie));
}

/** Server-computed top open/won deals, optionally scoped to a currency. */
export function getDealTop(currency?: string, scope: Types.MemberScopeParams = {}, init: RequestInit = {}) {
    return getJson<Types.DealTop>(`/api/deals/top${buildQuery({ currency, ...scope })}`, init);
}

export function getDealTopFromCookie(cookie: string | null, currency?: string) {
    return getJson<Types.DealTop>(`/api/deals/top${buildQuery({ currency })}`, withCookie(cookie));
}

/** Server-computed count of open deals with an expected close in the next {@code days} (default 7). */
export function getDealClosingSoonCount(days?: number, init: RequestInit = {}) {
    return getJson<Types.Count>(`/api/deals/closing-soon-count${buildQuery({ days })}`, init);
}

export function getDealClosingSoonCountFromCookie(cookie: string | null, days?: number) {
    return getJson<Types.Count>(`/api/deals/closing-soon-count${buildQuery({ days })}`, withCookie(cookie));
}

export function getDealClosingSoonFromCookie(cookie: string | null, days = 7, limit = 6) {
    return getJson<Types.Deal[]>(`/api/deals/closing-soon${buildQuery({ days, limit })}`, withCookie(cookie));
}

/**
 * Server-computed activity counts by type per time bucket, either over a calendar-aligned
 * {@code window} (day/week/month buckets carrying {@code periodStart}) or the legacy
 * {@code range} (30d/90d/12m).
 */
export function getActivityVolume(
    range?: string,
    scope: Types.MemberScopeParams = {},
    window?: Types.AnalyticsWindowParams,
    init: RequestInit = {},
) {
    return getJson<Types.ActivityVolumeBucket[]>(
        `/api/activities/volume${buildQuery({ range: window ? undefined : range, ...scope, ...window })}`,
        init,
    );
}

export function getActivityVolumeFromCookie(cookie: string | null, range?: string) {
    return getJson<Types.ActivityVolumeBucket[]>(`/api/activities/volume${buildQuery({ range })}`, withCookie(cookie));
}

/**
 * Server-computed per-user touch counts (activities + completed tasks + notes), over a
 * calendar-aligned {@code window} or the legacy {@code range}.
 */
export function getTeamLeaderboard(
    range?: string,
    window?: Types.AnalyticsWindowParams,
    init: RequestInit = {},
) {
    const windowParams = window
        ? { from: window.from, to: window.to, timezone: window.timezone }
        : { range };
    return getJson<Types.TeamLeaderboardEntry[]>(
        `/api/activities/leaderboard${buildQuery(windowParams)}`, init);
}

export function getTeamLeaderboardFromCookie(cookie: string | null, range?: string) {
    return getJson<Types.TeamLeaderboardEntry[]>(`/api/activities/leaderboard${buildQuery({ range })}`, withCookie(cookie));
}

/** Server-computed count of activities scheduled in the next {@code days} (default 7). */
export function getUpcomingActivityCount(days?: number, init: RequestInit = {}) {
    return getJson<Types.Count>(`/api/activities/upcoming-count${buildQuery({ days })}`, init);
}

export function getUpcomingActivityCountFromCookie(cookie: string | null, days?: number) {
    return getJson<Types.Count>(`/api/activities/upcoming-count${buildQuery({ days })}`, withCookie(cookie));
}

/** Server-computed task status + due-window counts over ALL tasks. */
export function getTaskSummary(scope: Types.MemberScopeParams = {}, init: RequestInit = {}) {
    return getJson<Types.TaskSummary>(`/api/tasks/summary${buildQuery({ ...scope })}`, init);
}

export function getTaskSummaryFromCookie(cookie: string | null) {
    return getJson<Types.TaskSummary>(`/api/tasks/summary`, withCookie(cookie));
}

/** Server-computed workspace-wide warmth summary (band/trend/decay counts) over ALL contacts/companies. */
export function getWarmthSummary(init: RequestInit = {}) {
    return getJson<Types.WarmthSummary>(`/api/scoring/summary`, init);
}

export function getWarmthSummaryFromCookie(cookie: string | null) {
    return getJson<Types.WarmthSummary>(`/api/scoring/summary`, withCookie(cookie));
}

/** One shared warmth/risk snapshot for the dashboard relationship widgets. */
export function getRelationshipDashboardFromCookie(cookie: string | null) {
    return getJson<Types.RelationshipDashboard>(`/api/scoring/dashboard`, withCookie(cookie));
}

export function getRelationshipDashboardResultFromCookie(cookie: string | null) {
    return resultWithCookie<Types.RelationshipDashboard>(
        (init) => getJson<Types.RelationshipDashboard>(`/api/scoring/dashboard`, init),
        cookie,
    );
}

/**
 * Server-computed per-stage open/closed rollup over ALL deals, optionally scoped to a currency.
 * Feeds the deals page stage-distribution chart.
 */
export function getDealStageDistribution(
    currency?: string, scope: Types.MemberScopeParams = {}, init: RequestInit = {},
) {
    return getJson<Types.DealStageDistribution[]>(
        `/api/deals/stage-distribution${buildQuery({ currency, ...scope })}`, init);
}

export function getDealFacetsFromCookie(cookie: string | null) {
    return getJson<Types.DealFacets>(`/api/deals/facets`, cookie ? { headers: { cookie }, cache: "no-store" } : {});
}

export function getDealById(id: number, init: RequestInit = {}) {
    return getJson<Types.Deal>(`/api/deals/${id}`, init);
}

export function getDealSummary(id: number, init: RequestInit = {}) {
    return getJson<Types.DealSummary>(`/api/deals/${id}/summary`, init);
}

/** When the deal reached each stage, earliest first. Drives the lifecycle progress timestamps. */
export function getDealStageHistory(id: number, init: RequestInit = {}) {
    return getJson<Types.DealStageHistory[]>(`/api/deals/${id}/stage-history`, init);
}

/** Risk assessment for a bounded requested deal set, highest risk first. */
export async function getDealRisks(ids: number[], init: RequestInit = {}) {
    if (ids.length === 0) return Promise.resolve([] as Types.DealRisk[]);
    const uniqueIds = Array.from(new Set(ids));
    const batches = await Promise.all(
        Array.from({ length: Math.ceil(uniqueIds.length / WORKSPACE_LIST_PAGE_SIZE) }, (_, index) =>
            getJson<Types.DealRisk[]>(
                `/api/deals/risk${buildQuery({
                    ids: uniqueIds.slice(
                        index * WORKSPACE_LIST_PAGE_SIZE,
                        (index + 1) * WORKSPACE_LIST_PAGE_SIZE,
                    ),
                })}`,
                init,
            )),
    );
    return batches.flat();
}

/** Risk assessment for a single deal; {@code level} is {@code "none"} when it is not at risk. */
export function getDealRisk(id: number, init: RequestInit = {}) {
    return getJson<Types.DealRisk>(`/api/deals/${id}/risk`, init);
}

export function getDealRisksFromCookie(cookie: string | null, ids: number[]) {
    return safeReadWithCookie<Types.DealRisk>((init) => getDealRisks(ids, init), cookie);
}

export function getDealRiskAnalyticsFromCookie(cookie: string | null) {
    return getJson<Types.DealRiskAnalytics>(`/api/deals/risk/analytics`, withCookie(cookie));
}

/** Server-computed compact per-currency deal-risk analytics, optionally scoped to a member. */
export function getDealRiskAnalytics(scope: Types.MemberScopeParams = {}, init: RequestInit = {}) {
    return getJson<Types.DealRiskAnalytics>(`/api/deals/risk/analytics${buildQuery({ ...scope })}`, init);
}

/**
 * AI-generated brief for a deal. Graceful domain unavailability resolves normally; provider failure
 * and timeout reject distinctly.
 */
export function generateDealBrief(id: number, refresh = false, init: RequestInit = {}) {
    return dedupedAiPost<Types.DealBrief>(
        `/api/deals/${id}/brief${refresh ? buildQuery({ refresh: true }) : ''}`,
        init,
    );
}

/**
 * AI-generated risk rationale for a deal. Graceful domain unavailability resolves normally;
 * provider failure and timeout reject distinctly.
 */
export function generateDealRationale(id: number, refresh = false, init: RequestInit = {}) {
    return dedupedAiPost<Types.DealRationale>(
        `/api/deals/${id}/rationale${refresh ? buildQuery({ refresh: true }) : ''}`,
        init,
    );
}

/**
 * Org-admin BYOP AI provider settings, addressed through the acting workspace. Saving requires
 * recent authentication (step-up); credentials are write-only and never returned.
 */
export function getAiProviderConfig(workspaceId: number, init: RequestInit = {}) {
    return getJson<Types.AiProviderConfig>(`/api/ai/provider?workspaceId=${workspaceId}`, {
        cache: "no-store",
        ...init,
    });
}

export function saveAiProviderConfig(workspaceId: number, request: Types.AiProviderConfigRequest) {
    return putJson<Types.AiProviderConfig>(`/api/ai/provider?workspaceId=${workspaceId}`, request);
}

export function revokeAiProviderConfig(workspaceId: number) {
    return deleteJson<void>(`/api/ai/provider?workspaceId=${workspaceId}`);
}

export function createDeal(payload: Types.CreateDealPayload, init: RequestInit = {}) {
    return postJson<Types.Deal>(`/api/deals`, payload, init);
}

/** Checks a proposed deal against the canonical normalized-name and company identity. */
export function preflightDealDuplicates(
    payload: Types.DealDuplicatePreflightRequest,
    init: RequestInit = {},
) {
    return postJson<Types.DuplicatePreflightResponse>(
        `/api/duplicate-preflight/deals`,
        payload,
        init,
    );
}

export function updateDeal(id: number, payload: Types.UpdateDealPayload) {
    return putJson<Types.Deal>(`/api/deals/${id}`, payload);
}

/** Renames a deal without touching any other field (safe against concurrent edits). */
export function updateDealName(id: number, name: string) {
    return putJson<Types.Deal>(`/api/deals/${id}/name`, { name });
}

/** Updates only a deal's projected value; rejected with 409 while the deal has line items. */
export function updateDealValue(id: number, value: number) {
    return putJson<Types.Deal>(`/api/deals/${id}/value`, { value });
}

export function deleteDeal(id: number, init: RequestInit = {}) {
    return deleteJson<void[]>(`/api/deals/${id}`, init);
}

/**
 * Closes a deal atomically (intent endpoint) with an explicit outcome (won: true = won,
 * false = lost). The server stamps the close date; it does not move the stage, so the deal
 * records where it was closed. Unlike updateDeal it can't clobber unrelated fields.
 */
export function closeDeal(id: number, payload: { won: boolean; reason?: string | null; actualValue?: number }) {
    return postJson<Types.Deal>(`/api/deals/${id}/close`, payload);
}

/** Reopens a closed deal: clears the outcome/close date and moves it off any terminal stage. */
export function reopenDeal(id: number) {
    return postJson<Types.Deal>(`/api/deals/${id}/reopen`, {});
}

/**
 * Moves a deal to a target stage and 0-based position on the Kanban board. The server renumbers
 * the affected stage column(s) so positions stay contiguous.
 */
export function moveDeal(id: number, stageId: number, position: number) {
    return postJson<Types.Deal>(`/api/deals/${id}/move`, { stageId, position });
}

/**
 * Changes only a deal's expected close date (a `YYYY-MM-DD` calendar day) without touching any
 * other field. Unlike `updateDeal` this cannot clobber a concurrent edit or reopen a closed deal,
 * so it is used for optimistic reschedule.
 */
export function rescheduleDeal(id: number, expectedCloseDate: string) {
    return postJson<Types.Deal>(`/api/deals/${id}/reschedule`, { expectedCloseDate });
}

export function updateDealOwner(id: number, ownerId: number | null) {
    return putJson<Types.Deal>(`/api/deals/${id}/owner`, { ownerId });
}

export function updateDealEvaluation(id: number, payload: Types.UpdateDealEvaluationPayload) {
    return putJson<Types.Deal>(`/api/deals/${id}/evaluation`, payload);
}

export function getDealCollaborators(id: number, init: RequestInit = {}) {
    return getJson<Types.User[]>(`/api/deals/${id}/collaborators`, init);
}

export function replaceDealCollaborators(id: number, userIds: number[]) {
    return putJson<Types.User[]>(`/api/deals/${id}/collaborators`, { userIds });
}

export function getDealPeople(id: number, init: RequestInit = {}) {
    return getJson<Types.DealPerson[]>(`/api/deals/${id}/people`, init);
}

export function getDealPrimaryContacts(ids: number[], init: RequestInit = {}) {
    if (ids.length === 0) return Promise.resolve([] as Types.DealPrimaryContact[]);
    return getJson<Types.DealPrimaryContact[]>(`/api/deals/people/primary${buildQuery({ ids })}`, init);
}

export function addDealPerson(id: number, personId: number, role: string, init: RequestInit = {}) {
    const params = new URLSearchParams({ role });
    return postJson<void[]>(`/api/deals/${id}/people/${personId}?${params}`, {}, init);
}

export function updateDealPersonRole(id: number, personId: number, role: string, init: RequestInit = {}) {
    const params = new URLSearchParams({ role });
    return putJson<void[]>(`/api/deals/${id}/people/${personId}?${params}`, {}, init);
}

export function removeDealPerson(id: number, personId: number, init: RequestInit = {}) {
    return deleteJson<void[]>(`/api/deals/${id}/people/${personId}`, init);
}

export function replaceDealPeople(id: number, people: Types.Contact[], init: RequestInit = {}) {
    return putJson<Types.Contact[]>(`/api/deals/${id}/people`, people, init);
}

export function getActivitiesForDeal(id: number, init: RequestInit = {}) {
    return getJson<Types.Activity[]>(`/api/deals/${id}/activities`, init);
}

export function getNotesForDeal(id: number, init: RequestInit = {}) {
    return getJson<Types.Note[]>(`/api/deals/${id}/notes`, init);
}

export function getTasksForDeal(id: number, init: RequestInit = {}) {
    return getJson<Types.Task[]>(`/api/deals/${id}/tasks`, init);
}

export function getTagsForDeal(id: number, init: RequestInit = {}) {
    return getJson<Types.Tag[]>(`/api/deals/${id}/tags`, init);
}

export function addTagToDeal(id: number, tagId: number, init: RequestInit = {}) {
    return postJson<void[]>(`/api/deals/${id}/tags/${tagId}`, {}, init);
}

export function removeTagFromDeal(id: number, tagId: number, init: RequestInit = {}) {
    return deleteJson<void[]>(`/api/deals/${id}/tags/${tagId}`, init);
}

export function replaceTagsForDeal(id: number, tagIds: number[], init: RequestInit = {}) {
    return putJson<Types.Tag[]>(`/api/deals/${id}/tags`, tagIds, init);
}

/*
* == Notifications
*/

export function getNotifications(params: Types.NotificationParams = {}, init: RequestInit = {}) {
    return getJson<Types.NotificationPage>(`/api/notifications${buildQuery(params)}`, {
        cache: "no-store",
        ...init,
    });
}

export function getNotificationCounts(init: RequestInit = {}) {
    return getJson<Types.NotificationCounts>("/api/notifications/counts", {
        cache: "no-store",
        ...init,
    });
}

export function getNotificationFacets(init: RequestInit = {}) {
    return getJson<Types.NotificationFacets>("/api/notifications/facets", {
        cache: "no-store",
        ...init,
    });
}

export function getNotificationPreferences(init: RequestInit = {}) {
    return getJson<Types.NotificationPreference[]>("/api/notification-preferences", {
        cache: "no-store",
        ...init,
    });
}

export function updateNotificationPreferences(preferences: Types.NotificationPreference[]) {
    return putJson<Types.NotificationPreference[]>("/api/notification-preferences", preferences);
}

export function getContextNotifications(
    contextType: string,
    contextId: number,
    init: RequestInit = {},
) {
    return getNotifications(
        { contextType, contextId, status: "unread", page: 1, size: 50 },
        init,
    );
}

export function markNotificationRead(id: number) {
    return postJson<Types.Notification>(`/api/notifications/${id}/read`);
}

export function markNotificationUnread(id: number) {
    return postJson<Types.Notification>(`/api/notifications/${id}/unread`);
}

export function dismissNotification(id: number) {
    return postJson<Types.Notification>(`/api/notifications/${id}/dismiss`);
}

export function restoreNotification(id: number) {
    return postJson<Types.Notification>(`/api/notifications/${id}/restore`);
}

export function snoozeNotification(id: number, body: Types.SnoozeRequest) {
    return postJson<Types.Notification>(`/api/notifications/${id}/snooze`, body);
}

export function unsnoozeNotification(id: number) {
    return postJson<Types.Notification>(`/api/notifications/${id}/unsnooze`);
}

export function getQuietHours(init: RequestInit = {}) {
    return getJson<Types.QuietHours>("/api/notification-preferences/quiet-hours", {
        cache: "no-store",
        ...init,
    });
}

export function updateQuietHours(config: Types.QuietHoursConfig) {
    return putJson<Types.QuietHours>("/api/notification-preferences/quiet-hours", config);
}

export function markAllNotificationsRead() {
    return postJson<Types.NotificationMarkAllResult>("/api/notifications/read-all");
}

/*
* == Pipeline management
*/

export function getPipelines(init: RequestInit = {}) {
    return getJson<Types.Pipeline[]>(`/api/pipelines`, init);
}

export function getPipelinesFromCookie(cookie: string | null) {
    return safeReadWithCookie<Types.Pipeline>((init) => getPipelines(init), cookie);
}

export function getPipelinesResultFromCookie(cookie: string | null) {
    return resultWithCookie<Types.Pipeline[]>((init) => getPipelines(init), cookie);
}

/** Returns every pipeline stage visible in the active workspace in one request. */
export function getAllStages(init: RequestInit = {}) {
    return getJson<Types.Stage[]>(`/api/pipelines/stages`, init);
}

export function getAllStagesFromCookie(cookie: string | null) {
    return safeReadWithCookie<Types.Stage>((init) => getAllStages(init), cookie);
}

export function getAllStagesResultFromCookie(cookie: string | null) {
    return resultWithCookie<Types.Stage[]>((init) => getAllStages(init), cookie);
}

export function getStagesByPipelineId(pipelineId: number, init: RequestInit = {}) {
    return getJson<Types.Stage[]>(`/api/pipelines/${pipelineId}/stages`, init);
}

export function createStage(pipelineId: number, payload: Types.CreateStagePayload) {
    return postJson<Types.Stage>(`/api/pipelines/${pipelineId}/stages`, payload);
}

export function updateStage(id: number, payload: Types.UpdateStagePayload) {
    return putJson<Types.Stage>(`/api/pipelines/stages/${id}`, payload);
}

export function deleteStage(id: number, init: RequestInit = {}) {
    return deleteJson<void>(`/api/pipelines/stages/${id}`, init);
}

export function createPipeline(payload: Types.CreatePipelinePayload) {
    return postJson<Types.Pipeline>(`/api/pipelines`, payload);
}

export function updatePipeline(id: number, payload: Types.UpdatePipelinePayload) {
    return putJson<Types.Pipeline>(`/api/pipelines/${id}`, payload);
}

export function getPipelineById(id: number, init: RequestInit = {}) {
    return getJson<Types.Pipeline>(`/api/pipelines/${id}`, init);
}

export function deletePipeline(id: number, init: RequestInit = {}) {
    return deleteJson<void[]>(`/api/pipelines/${id}`, init);
}

/*
* == Tag management
*/

export function getTags(init: RequestInit = {}) {
    return getJson<Types.Tag[]>(`/api/tags`, init);
}

export function getTagsFromCookie(cookie: string | null) {
    return safeReadWithCookie<Types.Tag>((init) => getTags(init), cookie);
}

export function getTagById(id: number, init: RequestInit = {}) {
    return getJson<Types.Tag>(`/api/tags/${id}`, init);
}

export function createTag(payload: Types.CreateTagPayload) {
    return postJson<Types.Tag>(`/api/tags`, payload);
}

export function updateTag(id: number, payload: Types.UpdateTagPayload) {
    return putJson<Types.Tag>(`/api/tags/${id}`, payload);
}

export function deleteTag(id: number, init: RequestInit = {}) {
    return deleteJson<void[]>(`/api/tags/${id}`, init);
}

export function getProducts(params: Types.ProductSearchParams = {}, init: RequestInit = {}) {
    return getJson<Types.Product[]>(`/api/products${buildQuery({ q: params.q })}`, init);
}

export function getProductsFromCookie(cookie: string | null) {
    return safeReadWithCookie<Types.Product>((init) => getProducts({}, init), cookie);
}

export function getProductById(id: number, init: RequestInit = {}) {
    return getJson<Types.Product>(`/api/products/${id}`, init);
}

export function createProduct(payload: Types.CreateProductPayload) {
    return postJson<Types.Product>(`/api/products`, payload);
}

export function updateProduct(id: number, payload: Types.UpdateProductPayload) {
    return putJson<Types.Product>(`/api/products/${id}`, payload);
}

export function deleteProduct(id: number, init: RequestInit = {}) {
    return deleteJson<void[]>(`/api/products/${id}`, init);
}

export function getDocumentTemplates(init: RequestInit = {}) {
    return getJson<Types.DocumentTemplate[]>(`/api/document-templates`, init);
}

export function getDocumentTemplatesFromCookie(cookie: string | null) {
    return safeReadWithCookie<Types.DocumentTemplate>((init) => getDocumentTemplates(init), cookie);
}

export function getDocumentTemplateById(id: number, init: RequestInit = {}) {
    return getJson<Types.DocumentTemplate>(`/api/document-templates/${id}`, init);
}

export function createDocumentTemplate(payload: Types.CreateDocumentTemplatePayload) {
    return postJson<Types.DocumentTemplate>(`/api/document-templates`, payload);
}

export function updateDocumentTemplate(id: number, payload: Types.UpdateDocumentTemplatePayload) {
    return putJson<Types.DocumentTemplate>(`/api/document-templates/${id}`, payload);
}

export function deleteDocumentTemplate(id: number, init: RequestInit = {}) {
    return deleteJson<void[]>(`/api/document-templates/${id}`, init);
}

export function getDealDocuments(dealId: number, init: RequestInit = {}) {
    return getJson<Types.DealDocument[]>(`/api/deals/${dealId}/documents`, init);
}

export function getDealDocumentsFromCookie(dealId: number, cookie: string | null) {
    return safeReadWithCookie<Types.DealDocument>(
        (init) => getDealDocuments(dealId, init), cookie);
}

export function getDealDocumentById(dealId: number, documentId: number, init: RequestInit = {}) {
    return getJson<Types.DealDocument>(`/api/deals/${dealId}/documents/${documentId}`, init);
}

export function generateDealDocument(dealId: number, templateId: number) {
    return postJson<Types.DealDocument>(`/api/deals/${dealId}/documents`, { templateId });
}

export function updateDealDocumentStatus(dealId: number, documentId: number, status: Types.DocumentClientStatus) {
    return putJson<Types.DealDocument>(`/api/deals/${dealId}/documents/${documentId}/status`, { status });
}

export function deleteDealDocument(dealId: number, documentId: number, init: RequestInit = {}) {
    return deleteJson<void[]>(`/api/deals/${dealId}/documents/${documentId}`, init);
}

export function getApprovalPolicies(init: RequestInit = {}) {
    return getJson<Types.ApprovalPolicy[]>(`/api/approval-policies`, init);
}

export function getApprovalPoliciesFromCookie(cookie: string | null) {
    return safeReadWithCookie<Types.ApprovalPolicy>((init) => getApprovalPolicies(init), cookie);
}

export function createApprovalPolicy(payload: Types.CreateApprovalPolicyPayload) {
    return postJson<Types.ApprovalPolicy>(`/api/approval-policies`, payload);
}

export function updateApprovalPolicy(id: number, payload: Types.UpdateApprovalPolicyPayload) {
    return putJson<Types.ApprovalPolicy>(`/api/approval-policies/${id}`, payload);
}

export function deleteApprovalPolicy(id: number, init: RequestInit = {}) {
    return deleteJson<void[]>(`/api/approval-policies/${id}`, init);
}

export function requestDocumentApproval(dealId: number, documentId: number, comment?: string | null) {
    return postJson<Types.DocumentApproval>(
        `/api/deals/${dealId}/documents/${documentId}/approval`, { comment: comment ?? null });
}

export function decideDocumentApproval(
    dealId: number,
    documentId: number,
    decision: 'approved' | 'rejected',
    comment?: string | null,
) {
    return postJson<Types.DocumentApproval>(
        `/api/deals/${dealId}/documents/${documentId}/approval/decision`, { decision, comment: comment ?? null });
}

export function cancelDocumentApproval(dealId: number, documentId: number) {
    return postJson<Types.DocumentApproval>(`/api/deals/${dealId}/documents/${documentId}/approval/cancel`, {});
}

export function getDealLineItems(dealId: number, init: RequestInit = {}) {
    return getJson<Types.DealLineItemsResponse>(`/api/deals/${dealId}/line-items`, init);
}

export function getDealLineItemsFromCookie(dealId: number, cookie: string | null) {
    return getJson<Types.DealLineItemsResponse>(
        `/api/deals/${dealId}/line-items`,
        cookie ? { headers: { cookie }, cache: 'no-store' } : {},
    );
}

export function createDealLineItem(dealId: number, payload: Types.DealLineItemPayload) {
    return postJson<Types.DealLineItemsResponse>(`/api/deals/${dealId}/line-items`, payload);
}

export function updateDealLineItem(dealId: number, itemId: number, payload: Types.DealLineItemPayload) {
    return putJson<Types.DealLineItemsResponse>(`/api/deals/${dealId}/line-items/${itemId}`, payload);
}

export function deleteDealLineItem(dealId: number, itemId: number, init: RequestInit = {}) {
    return deleteJson<Types.DealLineItemsResponse>(`/api/deals/${dealId}/line-items/${itemId}`, init);
}

export function getPeopleForTag(id: number, init: RequestInit = {}) {
    return getJson<Types.Contact[]>(`/api/tags/${id}/people`, init);
}

export function getCompaniesForTag(id: number, init: RequestInit = {}) {
    return getJson<Types.Company[]>(`/api/tags/${id}/companies`, init);
}
/*
* == Global search
*/

/**
 * Fuzzy search across every entity type.
 * @param query - The query to search for
 * @param init - The request initialization options
 * @returns A promise that resolves to the search results
 */
export function search(query: string, init: RequestInit = {}) {
    return getJson<Types.SearchResults>(`/api/search?query=${encodeURIComponent(query)}`, init);
}

const EMPTY_SEARCH_RESULTS: Types.SearchResults = {
    companies: [], people: [], deals: [], pipelines: [], tags: [], activities: [], notes: [], tasks: [], users: [], attachments: [],
};

/**
 * Server-side search using the forwarded session cookie. 
 * @param cookie - The session cookie
 * @param query - The query to search for
 * @returns A promise that resolves to the search results
 */
export async function searchFromCookie(cookie: string | null, query: string): Promise<Types.SearchResults> {
    if (!cookie || !query.trim()) return EMPTY_SEARCH_RESULTS;
    return search(query, { headers: { cookie }, cache: "no-store" });
}

/*
* == Attachments (generic file uploads for any entity)
*/

/**
 * Lists attachments for a given entity.
 * @param entityType - The owning entity type (e.g. "company", "person", "deal", "user")
 * @param entityId - The owning entity id
 */
export function getAttachments(entityType: string, entityId: number, init: RequestInit = {}) {
    return getJson<Types.Attachment[]>(`/api/attachments${buildQuery({ entityType, entityId })}`, init);
}

export function getAttachmentsFromCookie(entityType: string, entityId: number, cookie: string | null) {
    return safeReadWithCookie<Types.Attachment>((init) => getAttachments(entityType, entityId, init), cookie);
}

/**
 * Lists one bounded page of comment threads for a record, newest thread first,
 * each thread carrying its comments in chronological order. The feed is
 * workspace-local: threads authored in other workspaces of a shared record are
 * never returned. The server caps {@code limit} at 100.
 */
export function getCommentThreads(
    targetType: Types.RecordCommentTargetType,
    targetId: number,
    page: { limit?: number; offset?: number; state?: Types.RecordCommentStateFilter } = {},
    init: RequestInit = {},
) {
    return getJson<Types.RecordCommentThread[]>(
        `/api/comment-threads${buildQuery({
            targetType,
            targetId,
            state: page.state ?? 'all',
            limit: page.limit ?? 10,
            offset: page.offset ?? 0,
        })}`,
        init,
    );
}

/**
 * Resolves an open comment thread. {@code expectedVersion} must match the
 * thread's current version or the server answers 409.
 */
export function resolveCommentThread(threadId: number, expectedVersion: number, init: RequestInit = {}) {
    return postJson<Types.RecordCommentThread>(
        `/api/comment-threads/${threadId}/resolve`,
        { expectedVersion },
        init,
    );
}

/**
 * Reopens a resolved comment thread. {@code expectedVersion} must match the
 * thread's current version or the server answers 409.
 */
export function reopenCommentThread(threadId: number, expectedVersion: number, init: RequestInit = {}) {
    return postJson<Types.RecordCommentThread>(
        `/api/comment-threads/${threadId}/reopen`,
        { expectedVersion },
        init,
    );
}

/**
 * Fetches one comment thread by id for deep-link resolution when the thread
 * sits beyond the loaded page window.
 */
export function getCommentThread(threadId: number, init: RequestInit = {}) {
    return getJson<Types.RecordCommentThread>(`/api/comment-threads/${threadId}`, init);
}

/**
 * Ensures the caller's reaction is present on a comment; repeating the request
 * is a no-op. Returns the comment's updated per-reaction aggregate.
 */
export function addCommentReaction(
    commentId: number,
    reaction: Types.RecordCommentReactionKey,
    init: RequestInit = {},
) {
    return putJson<Types.RecordCommentReactionSummary[]>(
        `/api/comments/${commentId}/reactions/${reaction}`,
        {},
        init,
    );
}

/**
 * Ensures the caller's reaction is absent from a comment; removing an absent
 * reaction is a no-op. Returns the comment's updated per-reaction aggregate.
 */
export function removeCommentReaction(
    commentId: number,
    reaction: Types.RecordCommentReactionKey,
    init: RequestInit = {},
) {
    return deleteJson<Types.RecordCommentReactionSummary[]>(
        `/api/comments/${commentId}/reactions/${reaction}`,
        init,
    );
}

/**
 * Batch open-thread counts for up to 100 records of one type. Ids whose target
 * is not visible to the active workspace are silently omitted.
 */
export function getCommentIndicators(
    targetType: Types.RecordCommentTargetType,
    targetIds: number[],
    init: RequestInit = {},
) {
    return getJson<Types.RecordCommentIndicator[]>(
        `/api/comment-threads/indicators${buildQuery({ targetType, targetIds: targetIds.join(',') })}`,
        init,
    );
}

/**
 * Opens a new comment thread on a record. {@code clientToken} makes retries of
 * the same submission idempotent.
 */
export function createCommentThread(payload: Types.CreateCommentThreadPayload, init: RequestInit = {}) {
    return postJson<Types.RecordCommentThread>(`/api/comment-threads`, payload, init);
}

/**
 * Replies to an existing comment thread. {@code clientToken} makes retries of
 * the same submission idempotent.
 */
export function replyToCommentThread(
    threadId: number,
    payload: Types.CreateCommentReplyPayload,
    init: RequestInit = {},
) {
    return postJson<Types.RecordComment>(`/api/comment-threads/${threadId}/comments`, payload, init);
}

/**
 * Edits the caller's own comment within the 15-minute edit window and returns
 * the updated comment with re-synced references.
 */
export function editRecordComment(commentId: number, content: string, init: RequestInit = {}) {
    return patchJson<Types.RecordComment>(`/api/comments/${commentId}`, { content }, init);
}

/**
 * Redacts a comment. The row survives as a tombstone; authors may redact their
 * own comments, others need the moderate permission.
 */
export function deleteRecordComment(commentId: number, init: RequestInit = {}) {
    return deleteJson<void>(`/api/comments/${commentId}`, init);
}

/**
 * Lists every attachment across all entities, each carrying its resolved
 * {@code entityLabel}. Powers the Files library page.
 */
export function getAllAttachments(init: RequestInit = {}) {
    return getJson<Types.Attachment[]>(`/api/attachments`, init);
}

export function getAllAttachmentsFromCookie(cookie: string | null) {
    return safeReadWithCookie<Types.Attachment>((init) => getAllAttachments(init), cookie);
}

/**
 * Paginated, searchable, filterable slice of every attachment, for the Files library.
 */
export function getAttachmentsPage(params: Types.AttachmentsPageParams = {}, init: RequestInit = {}) {
    return getJson<Types.Page<Types.Attachment>>(`/api/attachments/page${buildQuery(params)}`, init);
}

/**
 * Filter facets (counts by source and kind, plus totals) across the whole table.
 */
export function getAttachmentFacets(init: RequestInit = {}) {
    return getJson<Types.AttachmentFacets>(`/api/attachments/facets`, init);
}

/**
 * Records legacy or externally hosted attachment metadata.
 * @param payload - The attachment metadata (entity, url, file name, etc.)
 */
export function createAttachment(payload: Types.CreateAttachmentPayload) {
    return postJson<Types.Attachment>(`/api/attachments`, payload);
}

export function uploadAttachment(entityType: string, entityId: number, file: File) {
    const formData = new FormData();
    formData.append("entityType", entityType);
    formData.append("entityId", String(entityId));
    formData.append("file", file);
    return requestMultipart<Types.Attachment>("/api/attachments/upload", "POST", formData);
}

export function getAttachment(id: number, init: RequestInit = {}) {
    return getJson<Types.Attachment>(`/api/attachments/${id}`, init);
}

export function getAttachmentTags(id: number, init: RequestInit = {}) {
    return getJson<Types.Tag[]>(`/api/attachments/${id}/tags`, init);
}

export function addAttachmentTag(id: number, tagId: number, init: RequestInit = {}) {
    return postJson<void>(`/api/attachments/${id}/tags/${tagId}`, {}, init);
}

export function removeAttachmentTag(id: number, tagId: number, init: RequestInit = {}) {
    return deleteJson<void>(`/api/attachments/${id}/tags/${tagId}`, init);
}

export function replaceAttachmentTags(id: number, tagIds: number[], init: RequestInit = {}) {
    return putJson<Types.Tag[]>(`/api/attachments/${id}/tags`, tagIds, init);
}

export function deleteAttachment(id: number, init: RequestInit = {}) {
    return deleteJson<void>(`/api/attachments/${id}`, init);
}

/*
== Admin operations
*/

export function getAuditLogs(params: Types.AuditLogParams = {}, init: RequestInit = {}) {
    return getJson<Types.AuditLogEntry[]>(`/api/audit${buildQuery(params)}`, init);
}

/*
* == Organization admin (org control plane)
*/

export function getOrgMembers(orgId: number, init: RequestInit = {}) {
    return getJson<Types.OrgMember[]>(`/api/orgs/${orgId}/members`, { cache: "no-store", ...init });
}

export function updateOrganizationIdentity(
    orgId: number,
    name: string,
    expectedName: string,
    expectedIdentityVersion: number,
) {
    return patchJson<Types.OrganizationIdentity>(`/api/orgs/${orgId}`, {
        name,
        expectedName,
        expectedIdentityVersion,
    });
}

export function getOrganizationLayout(
    orgId: number,
    params: { afterWorkspaceId?: number; afterAuthorityMemberId?: number; limit?: number } = {},
    init: RequestInit = {},
) {
    return getJson<Types.OrganizationLayout>(`/api/orgs/${orgId}/layout${buildQuery(params)}`, {
        cache: "no-store",
        ...init,
    });
}

export async function requestWorkspaceTenantExport(
    orgId: number,
    workspaceId: number,
): Promise<Types.TenantExportGrant> {
    const expectedPath = `/api/orgs/${orgId}/workspaces/${workspaceId}/export`;
    const response = await postJson<unknown>(expectedPath);
    if (typeof response !== "object"
        || response === null
        || !("downloadPath" in response)
        || response.downloadPath !== expectedPath
        || !("expiresAt" in response)
        || typeof response.expiresAt !== "string"
        || Number.isNaN(Date.parse(response.expiresAt))) {
        throw new Error("Tenant export returned an invalid download path");
    }
    return {
        downloadPath: response.downloadPath,
        expiresAt: response.expiresAt,
    };
}

export function teardownOrganizationWorkspace(
    orgId: number,
    workspaceId: number,
    confirmation: string,
) {
    return withClientRequestIdentityReset(
        () => deleteJson<void>(`/api/orgs/${orgId}/workspaces/${workspaceId}`, {
            body: JSON.stringify({ confirmation }),
        }),
        "workspace",
    );
}

export function teardownOrganization(orgId: number, confirmation: string) {
    return withClientRequestIdentityReset(
        () => deleteJson<void>(`/api/orgs/${orgId}`, {
            body: JSON.stringify({ confirmation }),
        }),
        "workspace",
    );
}

export function addOrgMemberByEmail(orgId: number, email: string, orgRole: Types.OrgRole) {
    return postJson<void>(`/api/orgs/${orgId}/members`, { email, orgRole });
}

export function setOrgMemberRole(orgId: number, userId: number, orgRole: Types.OrgRole) {
    return putJson<void>(`/api/orgs/${orgId}/members/${userId}`, { orgRole });
}

export function removeOrgMember(orgId: number, userId: number) {
    return deleteJson<void>(`/api/orgs/${orgId}/members/${userId}`);
}

export function getOrgAllowedDomains(orgId: number, init: RequestInit = {}) {
    return getJson<string[]>(`/api/orgs/${orgId}/allowed-domains`, { cache: "no-store", ...init });
}

export function addOrgAllowedDomain(orgId: number, domain: string) {
    return postJson<string[]>(`/api/orgs/${orgId}/allowed-domains`, { domain });
}

export function removeOrgAllowedDomain(orgId: number, domain: string) {
    return deleteJson<void>(`/api/orgs/${orgId}/allowed-domains?domain=${encodeURIComponent(domain)}`);
}

export function getOrgAudit(orgId: number, params: Types.AuditLogParams = {}, init: RequestInit = {}) {
    return getJson<Types.AuditLogEntry[]>(`/api/orgs/${orgId}/audit${buildQuery(params)}`, { cache: "no-store", ...init });
}

/*
* == Data-subject requests (APPI 開示等, issue #221)
*/

export function getDataSubjectRequests(
    orgId: number,
    params: { status?: Types.DataSubjectRequestStatus; limit?: number; offset?: number } = {},
    init: RequestInit = {},
) {
    return getJson<Types.DataSubjectRequest[]>(
        `/api/orgs/${orgId}/data-subject-requests${buildQuery(params)}`,
        { cache: "no-store", ...init },
    );
}

export function createDataSubjectRequest(orgId: number, body: Types.DataSubjectRequestBody) {
    return postJson<Types.DataSubjectRequest>(`/api/orgs/${orgId}/data-subject-requests`, body);
}

export function updateDataSubjectRequest(orgId: number, requestId: number, body: Types.DataSubjectRequestBody) {
    return putJson<Types.DataSubjectRequest>(`/api/orgs/${orgId}/data-subject-requests/${requestId}`, body);
}

/**
 * Assembles the subject-scoped disclosure for a verified disclosure request. The payload is the
 * operator-facing raw material (Art. 33 assembly); callers save it as a file rather than render it.
 */
export function getDataSubjectDisclosure(orgId: number, requestId: number) {
    return getJson<Record<string, unknown>>(
        `/api/orgs/${orgId}/data-subject-requests/${requestId}/disclosure`,
        { cache: "no-store" },
    );
}

/** Sets or clears a contact's APPI processing restrictions (suspend / cease provision). */
export function updateContactRestrictions(contactId: number, body: { suspended: boolean; provisionCeased: boolean }) {
    return putJson<Types.Contact>(`/api/persons/${contactId}/restrictions`, body);
}

/*
* == Workspaces (tenancy)
*/

const EMPTY_WORKSPACES: Types.MyWorkspaces = { workspaces: [], activeWorkspaceId: null };

export function getMyWorkspaces(init: RequestInit = {}) {
    return getJson<Types.MyWorkspaces>(`/api/workspaces`, { cache: "no-store", ...init });
}

function workspaceSelectionForBrowserCookie(snapshot: Types.MyWorkspaces): Types.MyWorkspaces | null {
    if (!Array.isArray(snapshot.workspaces)) return null;
    const cookieWorkspaceId = typeof document === "undefined"
        ? null
        : workspaceIdFromCookieHeader(document.cookie);
    if (cookieWorkspaceId === null) {
        return snapshot.workspaces.length === 0 && snapshot.activeWorkspaceId === null
            ? snapshot
            : null;
    }
    if (!snapshot.workspaces.some((workspace) => workspace.id === cookieWorkspaceId)) return null;
    return snapshot.activeWorkspaceId === cookieWorkspaceId
        ? snapshot
        : { ...snapshot, activeWorkspaceId: cookieWorkspaceId };
}

/**
 * Re-reads the browser's active workspace from the server after the cookie may have changed.
 * The returned memberships validate the strict current {@code connex_workspace} cookie, which is
 * then published as active. The endpoint's active ID is only a user-wide remembered fallback and
 * can legitimately differ after another browser changes it, so it cannot override this browser's
 * accessible cookie. Every attempt can invalidate browser request identity and notify peer tabs to
 * abandon their prior workspace. Callers must publish the returned snapshot explicitly; refreshed
 * provider props remain unordered.
 * @param notifyTransition - whether this read owns the workspace-transition notification
 * @param init - request options for the authoritative workspace read
 */
export async function readAuthoritativeWorkspaceSelection(
    notifyTransition = true,
    init: RequestInit = {},
): Promise<Types.MyWorkspaces> {
    try {
        const selection = workspaceSelectionForBrowserCookie(await getMyWorkspaces(init));
        if (selection) return selection;
        throw new Error("Authoritative workspace selection did not match the browser cookie");
    } finally {
        if (notifyTransition) signalClientRequestIdentityTransition("workspace");
    }
}

function activeWorkspaceFromSelection(snapshot: Types.MyWorkspaces): Types.Workspace {
    const activeWorkspace = snapshot.workspaces.find(
        (workspace) => workspace.id === snapshot.activeWorkspaceId,
    );
    if (!activeWorkspace) {
        throw new Error("Authoritative workspace selection has no active membership");
    }
    return activeWorkspace;
}

export async function getMyWorkspacesFromCookie(cookie: string | null): Promise<Types.MyWorkspaces> {
    if (!cookie) return EMPTY_WORKSPACES;
    return workspaceSnapshotForForwardedCookie(
        await getMyWorkspaces({ headers: { cookie }, cache: "no-store" }),
        cookie,
    );
}

function workspaceSnapshotForForwardedCookie(
    snapshot: Types.MyWorkspaces,
    cookie: string,
): Types.MyWorkspaces {
    const cookieWorkspaceId = workspaceIdFromCookieHeader(cookie);
    return cookieWorkspaceId !== null
        && snapshot.workspaces.some((workspace) => workspace.id === cookieWorkspaceId)
        && snapshot.activeWorkspaceId !== cookieWorkspaceId
        ? { ...snapshot, activeWorkspaceId: cookieWorkspaceId }
        : snapshot;
}

/**
 * Reads the server-rendered workspace snapshot and publishes an accessible forwarded cookie as
 * active because it defines this request's tenant. The endpoint's active ID remains the fallback
 * for a missing or revoked cookie, preserving the backend's stale-pin healing path (#1109).
 */
export async function getMyWorkspacesResultFromCookie(
    cookie: string | null,
): Promise<CookieResult<Types.MyWorkspaces>> {
    const result = await resultWithCookie<Types.MyWorkspaces>((init) => getMyWorkspaces(init), cookie);
    if (!result.ok || cookie === null) return result;
    return { ok: true, data: workspaceSnapshotForForwardedCookie(result.data, cookie) };
}

export function createWorkspace(name: string) {
    return withClientRequestIdentityReset(
        () => recoverWorkspaceSelectionResponse(
            () => postWorkspaceSelectionJson<Types.Workspace>(`/api/workspaces`, { name }),
            activeWorkspaceFromSelection,
        ),
        "workspace",
    );
}

export function updateWorkspaceIdentity(
    id: number,
    name: string,
    timezone: string | null,
    expectedName: string,
    expectedTimezone: string | null,
    expectedIdentityVersion: number,
) {
    return patchJson<Types.WorkspaceIdentity>(`/api/workspaces/${id}`, {
        name,
        timezone,
        expectedName,
        expectedTimezone,
        expectedIdentityVersion,
    });
}

export function switchWorkspace(id: number) {
    return withClientRequestIdentityReset(
        () => recoverWorkspaceSelectionResponse(
            () => postWorkspaceSelectionJson<void>(`/api/workspaces/${id}/switch`, {}, false),
            (snapshot) => {
                if (snapshot.activeWorkspaceId !== id) {
                    throw new Error("Authoritative workspace selection did not confirm the switch");
                }
            },
        ),
        "workspace",
    );
}

export function getPendingWorkspaces(init: RequestInit = {}) {
    return getJson<Types.Workspace[]>(`/api/workspaces/pending`, { cache: "no-store", ...init });
}

export function acceptWorkspace(id: number) {
    return withClientRequestIdentityReset(
        () => recoverWorkspaceSelectionResponse(
            () => postWorkspaceSelectionJson<Types.Workspace>(`/api/workspaces/${id}/accept`, {}),
            (snapshot) => {
                if (snapshot.activeWorkspaceId !== id) {
                    throw new Error("Authoritative workspace selection did not confirm acceptance");
                }
                return activeWorkspaceFromSelection(snapshot);
            },
        ),
        "workspace",
    );
}

export function declineWorkspace(id: number) {
    return postJson<void>(`/api/workspaces/${id}/decline`, {});
}

export function leaveWorkspace(id: number) {
    return withClientRequestIdentityReset(
        () => recoverWorkspaceSelectionResponse(
            () => postWorkspaceSelectionJson<Types.WorkspaceSelection>(`/api/workspaces/${id}/leave`, {}),
            (snapshot) => {
                if (snapshot.activeWorkspaceId === id) {
                    throw new Error("Authoritative workspace selection still names the left workspace");
                }
                return { activeWorkspaceId: snapshot.activeWorkspaceId };
            },
        ),
        "workspace",
    );
}

export function getWorkspaceMembers(workspaceId: number, init: RequestInit = {}) {
    return getJson<Types.WorkspaceMember[]>(`/api/workspaces/${workspaceId}/members`, { cache: "no-store", ...init });
}

/** Active members of the workspace selected by the forwarded request cookie. */
export async function getActiveWorkspaceMembersResultFromCookie(
    cookie: string | null,
): Promise<CookieResult<Types.WorkspaceMember[]>> {
    if (!cookie) return { ok: false };
    const activeWorkspaceId = workspaceIdFromCookieHeader(cookie);
    if (activeWorkspaceId == null) return { ok: false };
    const membersResult = await resultWithCookie(
        (init) => getWorkspaceMembers(activeWorkspaceId, init),
        cookie,
    );
    if (!membersResult.ok) return membersResult;
    return {
        ok: true,
        data: membersResult.data.filter((member) => member.status === "active"),
    };
}

/** Members of the active workspace (read from the connex_workspace cookie); empty when none is set. */
export function getActiveWorkspaceMembers(init: RequestInit = {}): Promise<Types.WorkspaceMember[]> {
    const workspaceId = clientWorkspaceId();
    if (!workspaceId) return Promise.resolve([]);
    return getWorkspaceMembers(Number(workspaceId), init);
}

export function addWorkspaceMember(workspaceId: number, email: string, role: Types.WorkspaceRole) {
    return postJson<Types.WorkspaceMember>(`/api/workspaces/${workspaceId}/members`, { email, role });
}

export function updateMemberRole(workspaceId: number, userId: number, role: Types.WorkspaceRole) {
    return patchJson<Types.WorkspaceMember>(`/api/workspaces/${workspaceId}/members/${userId}`, { role });
}

export function assignMemberCustomRole(workspaceId: number, userId: number, roleId: number) {
    return patchJson<Types.WorkspaceMember>(`/api/workspaces/${workspaceId}/members/${userId}`, { roleId });
}

export function getWorkspaceRoles(workspaceId: number, init: RequestInit = {}) {
    return getJson<Types.CustomRole[]>(`/api/workspaces/${workspaceId}/roles`, { cache: "no-store", ...init });
}

export function getBuiltInRoles(workspaceId: number, init: RequestInit = {}) {
    return getJson<Types.CustomRole[]>(`/api/workspaces/${workspaceId}/roles/built-in`, { cache: "no-store", ...init });
}

export function createWorkspaceRole(workspaceId: number, name: string, permissions: string[]) {
    return postJson<Types.CustomRole>(`/api/workspaces/${workspaceId}/roles`, { name, permissions });
}

export function updateWorkspaceRole(workspaceId: number, roleId: number, name: string, permissions: string[]) {
    return putJson<Types.CustomRole>(`/api/workspaces/${workspaceId}/roles/${roleId}`, { name, permissions });
}

export function deleteWorkspaceRole(workspaceId: number, roleId: number) {
    return deleteJson<void>(`/api/workspaces/${workspaceId}/roles/${roleId}`);
}

export function getRules(init: RequestInit = {}) {
    return getJson<Types.RuleListItem[]>(`/api/rules`, { cache: "no-store", ...init });
}

export function createRule(payload: Types.RuleRequest, init: RequestInit = {}) {
    return postJson<Types.Rule>(`/api/rules`, payload, init);
}

export function updateRule(id: number, payload: Types.RuleRequest) {
    return putJson<Types.Rule>(`/api/rules/${id}`, payload);
}

export function getRuleById(id: number, init: RequestInit = {}) {
    return getJson<Types.Rule>(`/api/rules/${id}`, init);
}

export function getRuleExecutions(id: number, init: RequestInit = {}) {
    return getJson<Types.RuleExecution[]>(`/api/rules/${id}/executions`, { cache: "no-store", ...init });
}

export function deleteRule(id: number) {
    return deleteJson<void>(`/api/rules/${id}`);
}

export function previewRule(recordType: Types.SavedViewRecordType, condition: Types.SegmentDefinition) {
    return postJson<Types.RulePreview>(`/api/rules/preview`, { recordType, condition });
}

export function getWorkflows(archived = false, init: RequestInit = {}) {
    return getJson<Types.WorkflowListItem[]>(`/api/workflows?archived=${archived}`, { cache: "no-store", ...init });
}

export function createWorkflow(payload: Types.WorkflowCreateRequest, init: RequestInit = {}) {
    return postJson<Types.WorkflowDto>("/api/workflows", payload, init);
}

export function getWorkflowById(id: number, init: RequestInit = {}) {
    return getJson<Types.WorkflowDto>(`/api/workflows/${id}`, { cache: "no-store", ...init });
}

export function saveWorkflowDraft(id: number, payload: Types.WorkflowDraftRequest, init: RequestInit = {}) {
    return putJson<Types.WorkflowDto>(`/api/workflows/${id}/draft`, payload, init);
}

export function validateWorkflow(id: number, init: RequestInit = {}) {
    return postJson<Types.WorkflowValidation>(`/api/workflows/${id}/validate`, {}, init);
}

export function simulateWorkflow(id: number, expectedRevision: number, recordId: number, init: RequestInit = {}) {
    return postJson<Types.WorkflowSimulation>(`/api/workflows/${id}/simulate`, { expectedRevision, recordId }, init);
}

export function publishWorkflow(id: number, expectedRevision: number, init: RequestInit = {}) {
    return postJson<Types.WorkflowDto>(`/api/workflows/${id}/publish`, { expectedRevision }, init);
}

export function enableWorkflow(id: number, init: RequestInit = {}) {
    return postJson<Types.WorkflowDto>(`/api/workflows/${id}/enable`, {}, init);
}

export function disableWorkflow(id: number, init: RequestInit = {}) {
    return postJson<Types.WorkflowDto>(`/api/workflows/${id}/disable`, {}, init);
}

export function archiveWorkflow(id: number, init: RequestInit = {}) {
    return postJson<Types.WorkflowDto>(`/api/workflows/${id}/archive`, {}, init);
}

export function restoreWorkflow(id: number, init: RequestInit = {}) {
    return postJson<Types.WorkflowDto>(`/api/workflows/${id}/restore`, {}, init);
}

export function setWorkflowRuntimeOwner(
    id: number,
    owner: Types.WorkflowRuntimeOwner,
    expectedActiveVersionId: number,
    init: RequestInit = {},
) {
    return postJson<Types.WorkflowDto>(
        `/api/workflows/${id}/runtime/${owner}`,
        { expectedActiveVersionId },
        init,
    );
}

export function getWorkflowVersions(id: number, init: RequestInit = {}) {
    return getJson<Types.WorkflowVersion[]>(`/api/workflows/${id}/versions`, { cache: "no-store", ...init });
}

export function resolveLegacyWorkflow(legacyRuleId: number, init: RequestInit = {}) {
    return getJson<{ workflowId: number }>(`/api/workflows/legacy-rules/${legacyRuleId}`, { cache: "no-store", ...init });
}

export function getWorkflowRuns(id: number, limit = 50, cursor?: string, init: RequestInit = {}) {
    const query = new URLSearchParams({ limit: String(limit) });
    if (cursor) query.set("cursor", cursor);
    return getJson<Types.WorkflowRunPage>(`/api/workflows/${id}/runs?${query}`, { cache: "no-store", ...init });
}

export function getWorkflowRun(id: number, runKey: string, init: RequestInit = {}) {
    return getJson<Types.WorkflowRunDetail>(
        `/api/workflows/${id}/runs/${encodeURIComponent(runKey)}`,
        { cache: "no-store", ...init },
    );
}

export function cancelWorkflowRun(id: number, runKey: string, init: RequestInit = {}) {
    return postJson<Types.WorkflowRunOperation>(
        `/api/workflows/${id}/runs/${encodeURIComponent(runKey)}/cancel`,
        {},
        init,
    );
}

export function retryWorkflowRun(id: number, runKey: string, init: RequestInit = {}) {
    return postJson<Types.WorkflowRunOperation>(
        `/api/workflows/${id}/runs/${encodeURIComponent(runKey)}/retry`,
        {},
        init,
    );
}

export function getWorkflowOperationsSummary(init: RequestInit = {}) {
    return getJson<Types.WorkflowOperationsSummary>("/api/workflow-operations/summary", {
        cache: "no-store",
        ...init,
    });
}

export function getWorkflowOperationsRuns(
    filters: { status?: string; failureCategory?: string; ownerId?: number; limit?: number; cursor?: string },
    init: RequestInit = {},
) {
    const query = new URLSearchParams();
    if (filters.status) query.set("status", filters.status);
    if (filters.failureCategory) query.set("failureCategory", filters.failureCategory);
    if (filters.ownerId != null) query.set("ownerId", String(filters.ownerId));
    query.set("limit", String(filters.limit ?? 50));
    if (filters.cursor) query.set("cursor", filters.cursor);
    return getJson<Types.WorkflowOperationsRunPage>(`/api/workflow-operations/runs?${query}`, {
        cache: "no-store",
        ...init,
    });
}

export function getWorkflowOperations(id: number, init: RequestInit = {}) {
    return getJson<Types.WorkflowOperationsDetail>(`/api/workflows/${id}/operations`, {
        cache: "no-store",
        ...init,
    });
}

export function pauseWorkflow(id: number, init: RequestInit = {}) {
    return postJson<Types.WorkflowDto>(`/api/workflows/${id}/pause`, {}, init);
}

export function resumeWorkflow(id: number, init: RequestInit = {}) {
    return postJson<Types.WorkflowDto>(`/api/workflows/${id}/resume`, {}, init);
}

export function assignWorkflowIntervention(
    interventionId: number,
    ownerUserId: number | null,
    expectedSourceVersion: number,
    init: RequestInit = {},
) {
    return putJson<Types.WorkflowIntervention>(
        `/api/workflow-interventions/${interventionId}/owner`,
        { ownerUserId, expectedSourceVersion },
        init,
    );
}

export function resolveWorkflowIntervention(
    interventionId: number,
    expectedSourceVersion: number,
    init: RequestInit = {},
) {
    return postJson<Types.WorkflowIntervention>(
        `/api/workflow-interventions/${interventionId}/resolve`,
        { expectedSourceVersion },
        init,
    );
}

export function prepareWorkflowManualRun(
    workflowId: number,
    payload: Types.WorkflowManualPrepareRequest,
    init: RequestInit = {},
) {
    return postJson<Types.WorkflowManualPreparation>(
        `/api/workflows/${workflowId}/manual-runs/prepare`,
        payload,
        init,
    );
}

export function confirmWorkflowManualRun(
    workflowId: number,
    scopeToken: string,
    scopeHash: string,
    idempotencyKey: string,
    init: RequestInit = {},
) {
    const headers = new Headers(init.headers);
    headers.set("Idempotency-Key", idempotencyKey);
    return postJson<Types.WorkflowInvocationResult>(
        `/api/workflows/${workflowId}/manual-runs`,
        { scopeToken, scopeHash },
        { ...init, headers },
    );
}

export function getWorkflowManualRun(workflowId: number, invocationId: number, init: RequestInit = {}) {
    return getJson<Types.WorkflowInvocationResult>(
        `/api/workflows/${workflowId}/manual-runs/${invocationId}`,
        { cache: "no-store", ...init },
    );
}

export function cancelWorkflowManualRun(workflowId: number, invocationId: number, init: RequestInit = {}) {
    return postJson<Types.WorkflowInvocationResult>(
        `/api/workflows/${workflowId}/manual-runs/${invocationId}/cancel`,
        {},
        init,
    );
}

export function getWorkflowRecipes(init: RequestInit = {}) {
    return getJson<Types.WorkflowRecipe[]>("/api/workflow-recipes", { cache: "no-store", ...init });
}

export function getWorkflowRecipe(recipeKey: string, init: RequestInit = {}) {
    return getJson<Types.WorkflowRecipe>(`/api/workflow-recipes/${encodeURIComponent(recipeKey)}`, {
        cache: "no-store",
        ...init,
    });
}

export function previewWorkflowRecipe(
    recipeKey: string,
    payload: {
        name?: string;
        description?: string;
        parameters: Types.WorkflowRecipeParameters;
        exampleRecordId?: number;
    },
    init: RequestInit = {},
) {
    return postJson<Types.WorkflowRecipePreview>(
        `/api/workflow-recipes/${encodeURIComponent(recipeKey)}/preview`,
        payload,
        init,
    );
}

export function installWorkflowRecipe(
    recipeKey: string,
    payload: {
        previewHash: string;
        name?: string;
        description?: string;
        parameters: Types.WorkflowRecipeParameters;
    },
    init: RequestInit = {},
) {
    return postJson<Types.WorkflowRecipeInstallResult>(
        `/api/workflow-recipes/${encodeURIComponent(recipeKey)}/install`,
        payload,
        init,
    );
}

export function getPermissionCatalog(init: RequestInit = {}) {
    return getJson<string[]>(`/api/permissions`, { cache: "no-store", ...init });
}

export function getEffectivePermissions(init: RequestInit = {}) {
    return getJson<string[]>(`/api/permissions/effective`, { cache: "no-store", ...init });
}

export function getEffectivePermissionsFromCookie(cookie: string | null) {
    return safeReadWithCookie<string>((init) => getEffectivePermissions(init), cookie);
}

export function getEffectivePermissionsResultFromCookie(cookie: string | null) {
    return resultWithCookie<string[]>((init) => getEffectivePermissions(init), cookie);
}

/*
* == Custom field management
*/

export function getCustomFields(entityType?: Types.CustomFieldEntityType, init: RequestInit = {}) {
    const query = entityType ? `?entityType=${entityType}` : "";
    return getJson<Types.CustomFieldDefinition[]>(`/api/custom-fields${query}`, { cache: "no-store", ...init });
}

export function getCustomFieldSchema(entityType: Types.CustomFieldEntityType, init: RequestInit = {}) {
    return getJson<Types.CustomFieldSchemaEntry[]>(
        `/api/custom-fields/schema?entityType=${entityType}`,
        { cache: "no-store", ...init },
    );
}

export function createCustomField(payload: Types.CustomFieldInput) {
    return postJson<Types.CustomFieldDefinition>(`/api/custom-fields`, payload);
}

export function updateCustomField(id: number, payload: Types.CustomFieldInput) {
    return putJson<Types.CustomFieldDefinition>(`/api/custom-fields/${id}`, payload);
}

export function deleteCustomField(id: number, init: RequestInit = {}) {
    return deleteJson<void>(`/api/custom-fields/${id}`, init);
}

function customFieldEntitySegment(entityType: Types.CustomFieldEntityType): string {
    switch (entityType) {
        case "person":
            return "persons";
        case "company":
            return "companies";
        case "deal":
            return "deals";
    }
}

function customFieldEntityPath(entityType: Types.CustomFieldEntityType, id: number) {
    return `/api/${customFieldEntitySegment(entityType)}/${id}/custom-fields`;
}

export function getEntityCustomFields(entityType: Types.CustomFieldEntityType, id: number, init: RequestInit = {}) {
    return getJson<Types.CustomFieldEntry[]>(customFieldEntityPath(entityType, id), { cache: "no-store", ...init });
}

export function getEntityCustomFieldsFromCookie(
    entityType: Types.CustomFieldEntityType,
    id: number,
    cookie: string | null,
) {
    return safeReadWithCookie<Types.CustomFieldEntry>((init) => getEntityCustomFields(entityType, id, init), cookie);
}

export function updateEntityCustomFields(
    entityType: Types.CustomFieldEntityType,
    id: number,
    values: Record<number, unknown>,
) {
    return putJson<Types.CustomFieldEntry[]>(customFieldEntityPath(entityType, id), { values });
}

export function updateEntityCustomField(
    entityType: Types.CustomFieldEntityType,
    id: number,
    definitionId: number,
    value: unknown,
) {
    return putJson<Types.CustomFieldEntry[]>(`${customFieldEntityPath(entityType, id)}/${definitionId}`, { value });
}

const CUSTOM_FIELD_VALUE_ID_BATCH = 150;

export async function getEntityCustomFieldValues(
    entityType: Types.CustomFieldEntityType,
    ids: number[],
    init: RequestInit = {},
): Promise<Types.EntityCustomFieldValues> {
    if (ids.length === 0) {
        return {};
    }
    const segment = customFieldEntitySegment(entityType);
    const batches: number[][] = [];
    for (let i = 0; i < ids.length; i += CUSTOM_FIELD_VALUE_ID_BATCH) {
        batches.push(ids.slice(i, i + CUSTOM_FIELD_VALUE_ID_BATCH));
    }
    const parts = await Promise.all(
        batches.map((batch) =>
            getJson<Types.EntityCustomFieldValues>(
                `/api/${segment}/custom-field-values?ids=${batch.join(",")}`,
                { cache: "no-store", ...init },
            ),
        ),
    );
    return Object.assign({}, ...parts);
}

export async function getEntityCustomFieldValuesFromCookie(
    entityType: Types.CustomFieldEntityType,
    ids: number[],
    cookie: string | null,
): Promise<Types.EntityCustomFieldValues> {
    if (!cookie || ids.length === 0) {
        return {};
    }
    try {
        return await getEntityCustomFieldValues(entityType, ids, { headers: { cookie }, cache: "no-store" });
    } catch {
        return {};
    }
}

/*
* == Saved views
*/

/**
 * The wire shape of a persisted saved-view config: versioned, with a nested {@code sort} object and
 * the deferred column/paging fields. Kept distinct from the browser's flat {@link Types.SavedViewConfig}
 * so the two can be mapped without leaking the persisted layout into the UI.
 */
type SavedViewConfigDto = {
    version?: number;
    filters?: Record<string, string[]>;
    query?: string;
    sort?: { key: string | null; direction: "asc" | "desc" } | null;
    segments?: Types.SegmentDefinition;
    visibleColumns?: string[] | null;
    columnOrder?: string[] | null;
    pageSize?: number | null;
};

type SavedViewWire = Omit<Types.SavedView, "config"> & { config: SavedViewConfigDto };

/**
 * Flattens a persisted saved-view config into the browser's working shape. Deferred column/paging
 * fields are preserved so a later {@link toSavedViewConfigDto} never drops them on write.
 */
export function fromSavedViewConfigDto(dto: SavedViewConfigDto): Types.SavedViewConfig {
    return {
        filters: dto.filters ?? {},
        query: dto.query ?? "",
        sortKey: dto.sort?.key ?? null,
        sortDirection: dto.sort?.direction ?? "asc",
        segments: dto.segments,
        visibleColumns: dto.visibleColumns ?? null,
        columnOrder: dto.columnOrder ?? null,
        pageSize: dto.pageSize ?? null,
    };
}

/** Expands the browser's flat config into the persisted DTO (version 1, nested sort), preserving deferred fields. */
export function toSavedViewConfigDto(config: Types.SavedViewConfig): SavedViewConfigDto {
    return {
        version: 1,
        filters: config.filters ?? {},
        query: config.query ?? "",
        sort: { key: config.sortKey ?? null, direction: config.sortDirection ?? "asc" },
        segments: config.segments,
        visibleColumns: config.visibleColumns ?? null,
        columnOrder: config.columnOrder ?? null,
        pageSize: config.pageSize ?? null,
    };
}

function fromSavedViewWire(wire: SavedViewWire): Types.SavedView {
    return { ...wire, config: fromSavedViewConfigDto(wire.config) };
}

function toSavedViewBody(payload: Types.SavedViewInput) {
    return {
        recordType: payload.recordType,
        name: payload.name,
        ...(payload.visibility !== undefined ? { visibility: payload.visibility } : {}),
        config: toSavedViewConfigDto(payload.config),
        ...(payload.position !== undefined ? { position: payload.position } : {}),
    };
}

export async function getSavedViews(recordType: Types.SavedViewRecordType, init: RequestInit = {}) {
    const views = await getJson<SavedViewWire[]>(`/api/saved-views?recordType=${recordType}`, { cache: "no-store", ...init });
    return views.map(fromSavedViewWire);
}

export function getSavedViewsFromCookie(recordType: Types.SavedViewRecordType, cookie: string | null) {
    return safeReadWithCookie<Types.SavedView>((init) => getSavedViews(recordType, init), cookie);
}

/**
 * Failure-aware saved-view fetch (see {@link resultWithCookie}), so an unreadable view list is not
 * presented as a workspace with no saved views.
 */
export function getSavedViewsResultFromCookie(recordType: Types.SavedViewRecordType, cookie: string | null) {
    return resultWithCookie<Types.SavedView[]>((init) => getSavedViews(recordType, init), cookie);
}

export async function getSavedView(id: number, init: RequestInit = {}) {
    return fromSavedViewWire(await getJson<SavedViewWire>(`/api/saved-views/${id}`, { cache: "no-store", ...init }));
}

export async function createSavedView(payload: Types.SavedViewInput) {
    return fromSavedViewWire(await postJson<SavedViewWire>(`/api/saved-views`, toSavedViewBody(payload)));
}

export async function updateSavedView(id: number, payload: Types.SavedViewInput) {
    return fromSavedViewWire(await putJson<SavedViewWire>(`/api/saved-views/${id}`, toSavedViewBody(payload)));
}

export function deleteSavedView(id: number, init: RequestInit = {}) {
    return deleteJson<void>(`/api/saved-views/${id}`, init);
}

export async function getSavedViewPins(init: RequestInit = {}) {
    const views = await getJson<SavedViewWire[]>(`/api/saved-views/pins`, { cache: "no-store", ...init });
    return views.map(fromSavedViewWire);
}

export async function pinSavedView(id: number, position?: number): Promise<void> {
    await putJson<void>(`/api/saved-views/${id}/pin`, position !== undefined ? { position } : {});
}

export function unpinSavedView(id: number, init: RequestInit = {}): Promise<void> {
    return deleteJson<void>(`/api/saved-views/${id}/pin`, init);
}

export async function getDefaultSavedView(recordType: Types.SavedViewRecordType, init: RequestInit = {}): Promise<Types.SavedView | null> {
    const result = await getJson<{ view: SavedViewWire | null }>(`/api/saved-views/defaults/${recordType}`, { cache: "no-store", ...init });
    return result.view ? fromSavedViewWire(result.view) : null;
}

export async function getDefaultSavedViewFromCookie(recordType: Types.SavedViewRecordType, cookie: string | null): Promise<Types.SavedView | null> {
    if (!cookie) return null;
    try {
        return await getDefaultSavedView(recordType, { headers: { cookie }, cache: "no-store" });
    } catch {
        return null;
    }
}

export async function setDefaultSavedView(recordType: Types.SavedViewRecordType, savedViewId: number): Promise<void> {
    await putJson<void>(`/api/saved-views/defaults/${recordType}`, { savedViewId });
}

export function clearDefaultSavedView(recordType: Types.SavedViewRecordType, init: RequestInit = {}): Promise<void> {
    return deleteJson<void>(`/api/saved-views/defaults/${recordType}`, init);
}

/*
* == Dashboard layout (per-user, per-workspace)
*/

export function getDashboardLayout(init: RequestInit = {}) {
    return getJson<Types.DashboardLayoutResponse>(`/api/dashboard-layout`, { cache: "no-store", ...init });
}

export function getAiChatSessions(
    params: Pick<Types.PageParams, 'page' | 'size'> = {},
    init: RequestInit = {},
) {
    const query = new URLSearchParams();
    if (params.page != null) query.set('page', String(params.page));
    if (params.size != null) query.set('size', String(params.size));
    const suffix = query.size > 0 ? `?${query}` : '';
    return getJson<Types.Page<Types.AiChatSession>>(
        `/api/ai/assistant/sessions${suffix}`,
        { cache: 'no-store', ...init },
    );
}

export function createAiChatSession(title: string, init: RequestInit = {}) {
    return postJson<Types.AiChatSession>('/api/ai/assistant/sessions', { title }, init);
}

export function getAiChatSession(
    id: number,
    params: Pick<Types.PageParams, 'page' | 'size'> = {},
    init: RequestInit = {},
) {
    const query = new URLSearchParams();
    if (params.page != null) query.set('page', String(params.page));
    if (params.size != null) query.set('size', String(params.size));
    const suffix = query.size > 0 ? `?${query}` : '';
    return getJson<Types.AiChatSessionDetail>(
        `/api/ai/assistant/sessions/${id}${suffix}`,
        { cache: 'no-store', ...init },
    );
}

export function updateAiChatSession(
    id: number,
    payload: { title?: string; archived?: boolean },
    init: RequestInit = {},
) {
    return patchJson<Types.AiChatSession>(`/api/ai/assistant/sessions/${id}`, payload, init);
}

export function archiveAiChatSession(id: number, init: RequestInit = {}) {
    return deleteJson<void>(`/api/ai/assistant/sessions/${id}`, init);
}

export async function startAiChatTurn(
    sessionId: number,
    payload: Types.AiChatTurnCreateRequest,
): Promise<Types.AiChatTurnAccepted> {
    const path = `/api/ai/assistant/sessions/${sessionId}/turns`;
    if (typeof window === 'undefined') {
        return postJson<Types.AiChatTurnAccepted>(path, payload);
    }
    const identity = await currentClientRequestIdentity();
    if (identity == null) {
        return postJson<Types.AiChatTurnAccepted>(path, payload);
    }
    const key = `${identity}\u0000${path}\u0000${JSON.stringify(payload)}`;
    const existing = inFlightAssistantTurns.get(key);
    if (existing) return existing.request;
    const controller = new AbortController();
    const request = (async () => {
        const response = await postJson<Types.AiChatTurnAccepted>(path, payload, {
            signal: controller.signal,
        });
        if (await currentClientRequestIdentity() !== identity) {
            throw new Error('AI request identity changed before turn acceptance');
        }
        return response;
    })().finally(() => {
        if (inFlightAssistantTurns.get(key)?.request === request) {
            inFlightAssistantTurns.delete(key);
        }
    });
    inFlightAssistantTurns.set(key, { controller, request });
    return request;
}

export function getAiChatTurn(
    sessionId: number,
    turnId: number,
    init: RequestInit = {},
) {
    return getJson<Types.AiChatTurn>(
        `/api/ai/assistant/sessions/${sessionId}/turns/${turnId}`,
        { cache: 'no-store', ...init },
    );
}

export function getAiGenerationStatus<T>(handle: string, init: RequestInit = {}) {
    return getJson<Types.AiGenerationStatus<T>>(
        `/api/ai/generations/${encodeURIComponent(handle)}`,
        { cache: 'no-store', ...init },
    );
}

/**
 * SSR-safe fetch of the current user's dashboard layout, forwarding the request cookie.
 * Returns `null` on any failure or missing cookie so the caller falls back to the default layout.
 */
export async function getDashboardLayoutFromCookie(
    cookie: string | null,
): Promise<{ response: Types.DashboardLayoutResponse | null; errored: boolean }> {
    if (!cookie) return { response: null, errored: false };
    try {
        return { response: await getDashboardLayout({ headers: { cookie }, cache: "no-store" }), errored: false };
    } catch {
        return { response: null, errored: true };
    }
}

export function saveDashboardLayout(layout: Types.DashboardLayout) {
    return putJson<Types.DashboardLayoutResponse>(`/api/dashboard-layout`, { layout });
}

export function resetDashboardLayout(init: RequestInit = {}) {
    return deleteJson<void>(`/api/dashboard-layout`, init);
}

export function getReportTemplates(init: RequestInit = {}) {
    return getJson<Types.ReportTemplate[]>(`/api/reports/templates`, { cache: "no-store", ...init });
}

export function getReports(init: RequestInit = {}) {
    return getJson<Types.ReportDefinition[]>(`/api/reports`, { cache: "no-store", ...init });
}

export function getReportsFromCookie(cookie: string | null) {
    return safeReadWithCookie<Types.ReportDefinition>((init) => getReports(init), cookie);
}

export function getReportTemplatesFromCookie(cookie: string | null) {
    return safeReadWithCookie<Types.ReportTemplate>((init) => getReportTemplates(init), cookie);
}

export function getReport(id: number, init: RequestInit = {}) {
    return getJson<Types.ReportDefinition>(`/api/reports/${id}`, { cache: "no-store", ...init });
}

export function getReportComposerAvailability(init: RequestInit = {}) {
    return getJson<Types.ReportComposerAvailability>(`/api/reports/composer/availability`, {
        cache: "no-store",
        ...init,
    });
}

export function getReportComposerAvailabilityResultFromCookie(cookie: string | null) {
    return resultWithCookie<Types.ReportComposerAvailability>(
        (init) => getReportComposerAvailability(init),
        cookie,
    );
}

export function previewReportComposer(prompt: string) {
    return withReportRequestIdentity((signal) =>
        postJson<Types.ReportComposerPreview>(`/api/reports/composer/preview`, { prompt }, { signal }));
}

export function createReport(payload: Types.ReportDefinitionInput) {
    return postJson<Types.ReportDefinition>(`/api/reports`, payload);
}

export function updateReport(id: number, payload: Types.ReportDefinitionInput) {
    return putJson<Types.ReportDefinition>(`/api/reports/${id}`, payload);
}

export function deleteReport(id: number) {
    return deleteJson<void>(`/api/reports/${id}`);
}

async function requestReportSchedule(
    reportId: number,
    init: RequestInit = {},
): Promise<Types.ReportSchedule | null> {
    try {
        return await getJson<Types.ReportSchedule>(`/api/reports/${reportId}/schedule`, {
            cache: "no-store",
            ...init,
        });
    } catch (error) {
        if (error instanceof ApiError && error.status === 404) {
            await getReport(reportId, init);
            return null;
        }
        throw error;
    }
}

export function getReportSchedule(
    reportId: number,
    init: RequestInit = {},
): Promise<Types.ReportSchedule | null> {
    if (Object.keys(init).length > 0) return requestReportSchedule(reportId, init);
    return withReportRequestIdentity((signal) => requestReportSchedule(reportId, { signal }));
}

export function getReportScheduleFromCookie(reportId: number, cookie: string | null) {
    if (!cookie) return Promise.resolve(null);
    return getReportSchedule(reportId, { headers: { cookie }, cache: "no-store" });
}

export function saveReportSchedule(
    reportId: number,
    body: Types.ReportScheduleRequest,
    scheduleExists: boolean,
) {
    return withReportRequestIdentity((signal) => {
        const path = `/api/reports/${reportId}/schedule`;
        return scheduleExists
            ? putJson<Types.ReportSchedule>(path, body, { signal })
            : postJson<Types.ReportSchedule>(path, body, { signal });
    });
}

export function deleteReportSchedule(reportId: number) {
    return withReportRequestIdentity((signal) =>
        deleteJson<void>(`/api/reports/${reportId}/schedule`, { signal }));
}

export function getGoals(init: RequestInit = {}) {
    return getJson<Types.ReportGoal[]>(`/api/goals`, { cache: "no-store", ...init });
}

export function getGoalsFromCookie(cookie: string | null) {
    return safeReadWithCookie<Types.ReportGoal>((init) => getGoals(init), cookie);
}

export function getGoalsResultFromCookie(cookie: string | null) {
    return resultWithCookie<Types.ReportGoal[]>((init) => getGoals(init), cookie);
}

export function createGoal(payload: Types.ReportGoalInput) {
    return postJson<Types.ReportGoal>(`/api/goals`, payload);
}

export function updateGoal(id: number, payload: Types.ReportGoalInput) {
    return putJson<Types.ReportGoal>(`/api/goals/${id}`, payload);
}

export function deleteGoal(id: number) {
    return deleteJson<void>(`/api/goals/${id}`);
}

export async function generateReport(
    id: number,
    payload: Types.ReportGenerateInput = {},
    mode: Types.ReportNarrativeMode = "cached",
) {
    if (typeof window === "undefined") {
        return postJson<Types.ReportDocument>(`/api/reports/${id}/generate?narrative=${mode}`, payload);
    }
    const identity = await currentClientRequestIdentity();
    if (identity == null) {
        throw new Error("Unable to establish the authenticated report request identity");
    }
    const path = `/api/reports/${id}/generate?narrative=${mode}`;
    const key = `${identity}\u0000${path}\u0000${JSON.stringify(payload)}`;
    const existing = inFlightReportGenerations.get(key);
    if (existing) {
        return existing.request;
    }
    const controller = new AbortController();
    const request = (async () => {
        const response = await postJson<Types.ReportDocument>(path, payload, { signal: controller.signal });
        if (await currentClientRequestIdentity() !== identity) {
            throw new Error("AI request identity changed before completion");
        }
        return response;
    })().finally(() => {
        if (inFlightReportGenerations.get(key)?.request === request) {
            inFlightReportGenerations.delete(key);
        }
    });
    inFlightReportGenerations.set(key, { controller, request });
    return request;
}

export function getReportSnapshots(id: number, init: RequestInit = {}) {
    return getJson<Types.ReportSnapshotSummary[]>(`/api/reports/${id}/snapshots`, { cache: "no-store", ...init });
}

export function createReportSnapshot(id: number, payload: Types.ReportGenerateInput = {}) {
    return withReportRequestIdentity((signal) =>
        postJson<Types.ReportSnapshot>(`/api/reports/${id}/snapshots`, payload, { signal }));
}

export function getReportSnapshot(id: number, snapshotId: number, init: RequestInit = {}) {
    if (Object.keys(init).length > 0) {
        return getJson<Types.ReportSnapshot>(`/api/reports/${id}/snapshots/${snapshotId}`, {
            cache: "no-store",
            ...init,
        });
    }
    return withReportRequestIdentity((signal) =>
        getJson<Types.ReportSnapshot>(`/api/reports/${id}/snapshots/${snapshotId}`, {
            cache: "no-store",
            signal,
        }));
}

export function deleteReportSnapshot(id: number, snapshotId: number) {
    return withReportRequestIdentity((signal) =>
        deleteJson<void>(`/api/reports/${id}/snapshots/${snapshotId}`, { signal }));
}

async function withReportRequestIdentity<T>(request: (signal?: AbortSignal) => Promise<T>): Promise<T> {
    if (typeof window === "undefined") {
        return request();
    }
    const identity = await currentClientRequestIdentity();
    if (identity == null) {
        throw new Error("Unable to establish the authenticated report request identity");
    }
    const controller = new AbortController();
    inFlightReportRequests.add(controller);
    try {
        const result = await request(controller.signal);
        if (await currentClientRequestIdentity() !== identity) {
            throw new Error("Report request identity changed before completion");
        }
        return result;
    } finally {
        inFlightReportRequests.delete(controller);
    }
}

function downloadBlob(blob: Blob, filename: string): void {
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = filename;
    document.body.appendChild(anchor);
    anchor.click();
    anchor.remove();
    URL.revokeObjectURL(url);
}

async function fetchReportCsv(path: string, init: RequestInit): Promise<Blob> {
    return withReportRequestIdentity(async (signal) => {
        const locale = localeFromCookieHeader(document.cookie);
        const workspaceId = clientWorkspaceId();
        const mutating = isMutating(init.method);
        const send = (csrf: Record<string, string>) => fetch(`${API_BASE}${path}`, {
                ...init,
                signal,
                credentials: "include",
                headers: {
                    "Accept-Language": locale,
                    ...(workspaceId ? { "X-Workspace-Id": workspaceId } : {}),
                    ...csrf,
                    ...init.headers,
                },
            });
        let res = await send(mutating ? await csrfHeader() : {});
        if (await shouldRetryWithFreshCsrf(path, res, mutating)) {
            res = await send(await csrfHeader(true));
        }
        if (!res.ok) {
            throw await getApiError(res);
        }
        return res.blob();
    });
}

export async function exportReportCsv(
    id: number,
    payload: Types.ReportGenerateInput = {},
    filename = `report-${id}.csv`,
): Promise<void> {
    const blob = await fetchReportCsv(`/api/reports/${id}/export.csv`, {
        method: "POST",
        headers: {
            "Content-Type": "application/json",
        },
        body: JSON.stringify(payload),
    });
    downloadBlob(blob, filename);
}

export async function exportReportSnapshotCsv(id: number, snapshotId: number): Promise<void> {
    const blob = await fetchReportCsv(`/api/reports/${id}/snapshots/${snapshotId}/export.csv`, {});
    downloadBlob(blob, `report-${id}-snapshot-${snapshotId}.csv`);
}

/*
 * == Smart segments
 */

export function evaluateSegments(recordType: Types.SavedViewRecordType, definition: Types.SegmentDefinition) {
    return postJson<Types.SegmentResult>(`/api/segments/evaluate`, { recordType, definition });
}

export function getSegmentFields(recordType: Types.SavedViewRecordType, init: RequestInit = {}) {
    return getJson<Types.SegmentFields>(`/api/segments/fields?recordType=${recordType}`, { cache: "no-store", ...init });
}

export function getSegmentCatalog(recordType: Types.SavedViewRecordType) {
    return getJson<Types.SegmentCatalog>(`/api/segments/catalog?recordType=${recordType}`);
}

export function getShares(type: string, id: number, init: RequestInit = {}) {
    return getJson<Types.Share[]>(`/api/shares/${type}/${id}`, { cache: "no-store", ...init });
}

export function shareRecord(type: string, id: number, workspaceId: number, canEdit = false) {
    return postJson<void>(`/api/shares/${type}/${id}`, { workspaceId, canEdit });
}

export function unshareRecord(type: string, id: number, workspaceId: number) {
    return deleteJson<void>(`/api/shares/${type}/${id}/${workspaceId}`);
}

export function removeWorkspaceMember(workspaceId: number, userId: number) {
    return deleteJson<void>(`/api/workspaces/${workspaceId}/members/${userId}`);
}

export function getWorkspaceInvites(workspaceId: number, init: RequestInit = {}) {
    return getJson<Types.WorkspaceInvite[]>(`/api/workspaces/${workspaceId}/invites`, { cache: "no-store", ...init });
}

export function createWorkspaceInvite(workspaceId: number, email: string, role: Types.WorkspaceRole) {
    return postJson<Types.InviteResult>(`/api/workspaces/${workspaceId}/invites`, { email, role });
}

export function revokeWorkspaceInvite(workspaceId: number, inviteId: number) {
    return deleteJson<void>(`/api/workspaces/${workspaceId}/invites/${inviteId}`);
}

export function getInvitePreview(token: string, init: RequestInit = {}) {
    return getJson<Types.InvitePreview>(`/api/invites/${token}`, { cache: "no-store", ...init });
}

export function acceptInvite(token: string) {
    return withClientRequestIdentityReset(
        () => recoverWorkspaceSelectionResponse(
            () => postWorkspaceSelectionJson<Types.Workspace>(`/api/invites/${token}/accept`, {}),
            activeWorkspaceFromSelection,
        ),
        "workspace",
    );
}

export function getWorkspaceInviteLinks(workspaceId: number, init: RequestInit = {}) {
    return getJson<Types.WorkspaceInviteLink[]>(`/api/workspaces/${workspaceId}/invite-links`, { cache: "no-store", ...init });
}

export function createWorkspaceInviteLink(
    workspaceId: number,
    payload: { role?: Types.WorkspaceRole; expiresInDays?: number; maxUses?: number },
) {
    return postJson<Types.WorkspaceInviteLink>(`/api/workspaces/${workspaceId}/invite-links`, payload);
}

export function revokeWorkspaceInviteLink(workspaceId: number, linkId: number) {
    return deleteJson<void>(`/api/workspaces/${workspaceId}/invite-links/${linkId}`);
}

export function getInviteLinkPreview(token: string, init: RequestInit = {}) {
    return getJson<Types.InviteLinkPreview>(`/api/invite-links/${token}`, { cache: "no-store", ...init });
}

export function acceptInviteLink(token: string) {
    return withClientRequestIdentityReset(
        () => recoverWorkspaceSelectionResponse(
            () => postWorkspaceSelectionJson<Types.Workspace>(`/api/invite-links/${token}/accept`, {}),
            activeWorkspaceFromSelection,
        ),
        "workspace",
    );
}

export function getWorkspaceAllowedDomains(workspaceId: number, init: RequestInit = {}) {
    return getJson<string[]>(`/api/workspaces/${workspaceId}/allowed-domains`, { cache: "no-store", ...init });
}

export function addWorkspaceAllowedDomain(workspaceId: number, domain: string) {
    return postJson<string[]>(`/api/workspaces/${workspaceId}/allowed-domains`, { domain });
}

export function removeWorkspaceAllowedDomain(workspaceId: number, domain: string) {
    return deleteJson<void>(`/api/workspaces/${workspaceId}/allowed-domains?domain=${encodeURIComponent(domain)}`);
}
export function getWorkspaceMailConfig(workspaceId: number, init: RequestInit = {}) {
    return getJson<Types.MailConfig>(`/api/workspaces/${workspaceId}/mail-config`, { cache: "no-store", ...init });
}

export function saveWorkspaceMailConfig(workspaceId: number, request: Types.MailConfigRequest) {
    return putJson<Types.MailConfig>(`/api/workspaces/${workspaceId}/mail-config`, request);
}

export function deleteWorkspaceMailConfig(workspaceId: number) {
    return deleteJson<void>(`/api/workspaces/${workspaceId}/mail-config`);
}

export function sendWorkspaceMailTest(workspaceId: number) {
    return postJson<Types.MailTestResult>(`/api/workspaces/${workspaceId}/mail-config/test`, {});
}

export function getWorkspaceDiagnostics(workspaceId: number, init: RequestInit = {}) {
    return getJson<Types.TenantDiagnostics>(`/api/workspaces/${workspaceId}/diagnostics`, {
        cache: "no-store",
        ...init,
    });
}

export function getOrgDiagnostics(orgId: number, init: RequestInit = {}) {
    return getJson<Types.TenantDiagnostics>(`/api/orgs/${orgId}/diagnostics`, {
        cache: "no-store",
        ...init,
    });
}

export function sendWorkspaceMailDiagnosticTest(workspaceId: number) {
    return postJson<Types.MailDiagnosticTest>(
        `/api/workspaces/${workspaceId}/mail/diagnostics/test-send`,
        {},
    );
}

export function getDeliveryProviders(init: RequestInit = {}) {
    return getJson<Types.DeliveryProviderConfig[]>(`/api/delivery/providers`, { cache: "no-store", ...init });
}

export function getDeliveryProvidersFromCookie(cookie: string | null) {
    return safeReadWithCookie<Types.DeliveryProviderConfig>((init) => getDeliveryProviders(init), cookie);
}

export function saveDeliveryProvider(payload: Types.DeliveryProviderConfigPayload) {
    return putJson<Types.DeliveryProviderConfig>(`/api/delivery/providers`, payload);
}

export function issueDeliveryWebhookToken(channel: string) {
    return postJson<Types.DeliveryWebhookToken>(
        `/api/delivery/providers/${encodeURIComponent(channel)}/webhook-token`,
        {},
    );
}

export function deleteDeliveryProvider(channel: string) {
    return deleteJson<void>(`/api/delivery/providers/${encodeURIComponent(channel)}`);
}

export function getConnectors(init: RequestInit = {}) {
    return getJson<Types.ConnectorConfig[]>(`/api/delivery/connectors`, { cache: "no-store", ...init });
}

export function getConnectorsFromCookie(cookie: string | null) {
    return safeReadWithCookie<Types.ConnectorConfig>((init) => getConnectors(init), cookie);
}

export function saveConnector(payload: Types.ConnectorConfigPayload) {
    return putJson<Types.ConnectorConfig>(`/api/delivery/connectors`, payload);
}

export function deleteConnector(connector: string) {
    return deleteJson<void>(`/api/delivery/connectors/${encodeURIComponent(connector)}`);
}

export function getCampaigns(init: RequestInit = {}) {
    return getJson<Types.Campaign[]>(`/api/campaigns`, init);
}

export function getCampaign(id: number, init: RequestInit = {}) {
    return getJson<Types.Campaign>(`/api/campaigns/${id}`, init);
}

export function getCampaignFromCookie(id: number, cookie: string | null) {
    return resultWithCookie<Types.Campaign>((init) => getCampaign(id, init), cookie);
}

export function createCampaign(payload: Types.CampaignPayload) {
    return postJson<Types.Campaign>(`/api/campaigns`, payload);
}

export function updateCampaign(id: number, payload: Types.CampaignPayload) {
    return putJson<Types.Campaign>(`/api/campaigns/${id}`, payload);
}

export function deleteCampaign(id: number) {
    return deleteJson<void>(`/api/campaigns/${id}`);
}

export function getCampaignAudience(id: number, init: RequestInit = {}) {
    return getJson<Types.CampaignAudience | undefined>(`/api/campaigns/${id}/audience`, init);
}

export function getCampaignAudienceFromCookie(id: number, cookie: string | null) {
    return resultWithCookie<Types.CampaignAudience | undefined>(
        (init) => getCampaignAudience(id, init),
        cookie,
    );
}

export function setCampaignAudience(id: number, payload: Types.CampaignAudiencePayload) {
    return putJson<Types.CampaignAudience>(`/api/campaigns/${id}/audience`, payload);
}

export function estimateCampaignAudience(id: number) {
    return postJson<Types.CampaignAudienceEstimate>(`/api/campaigns/${id}/audience/estimate`, {});
}

export function snapshotCampaignAudience(id: number) {
    return postJson<Types.CampaignAudienceSnapshot>(`/api/campaigns/${id}/audience/snapshot`, {});
}

export function getCampaignSnapshots(id: number, init: RequestInit = {}) {
    return getJson<Types.CampaignAudienceSnapshotSummary[]>(
        `/api/campaigns/${id}/audience/snapshots`,
        init,
    );
}

export function getCampaignSnapshot(id: number, version: number, init: RequestInit = {}) {
    return getJson<Types.CampaignAudienceSnapshot>(
        `/api/campaigns/${id}/audience/snapshots/${version}`,
        init,
    );
}

export function getCampaignMessages(id: number, init: RequestInit = {}) {
    return getJson<Types.CampaignMessage[]>(`/api/campaigns/${id}/messages`, init);
}

export function createCampaignMessage(id: number, payload: Types.CampaignMessagePayload) {
    return postJson<Types.CampaignMessage>(`/api/campaigns/${id}/messages`, payload);
}

export function getCampaignMessage(id: number, messageId: number, init: RequestInit = {}) {
    return getJson<Types.CampaignMessage>(`/api/campaigns/${id}/messages/${messageId}`, init);
}

export function addCampaignMessageRevision(
    id: number,
    messageId: number,
    payload: Types.CampaignMessageRevisionPayload,
) {
    return postJson<Types.CampaignMessage>(
        `/api/campaigns/${id}/messages/${messageId}/revisions`,
        payload,
    );
}

export function getCampaignSends(id: number, init: RequestInit = {}) {
    return getJson<Types.CampaignSend[]>(`/api/campaigns/${id}/sends`, init);
}

export function createCampaignSend(id: number, payload: Types.CampaignSendPayload) {
    return postJson<Types.CampaignSend>(`/api/campaigns/${id}/sends`, payload);
}

export function getCampaignSend(id: number, sendId: number, init: RequestInit = {}) {
    return getJson<Types.CampaignSend>(`/api/campaigns/${id}/sends/${sendId}`, init);
}

export function queueCampaignSend(id: number, sendId: number) {
    return postJson<Types.CampaignSend>(`/api/campaigns/${id}/sends/${sendId}/queue`, {});
}

export function pauseCampaignSend(id: number, sendId: number) {
    return postJson<Types.CampaignSend>(`/api/campaigns/${id}/sends/${sendId}/pause`, {});
}

export function cancelCampaignSend(id: number, sendId: number) {
    return postJson<Types.CampaignSend>(`/api/campaigns/${id}/sends/${sendId}/cancel`, {});
}

export function getCampaignExports(id: number, init: RequestInit = {}) {
    return getJson<Types.CampaignAudienceExport[]>(`/api/campaigns/${id}/exports`, init);
}

export function createCampaignExport(id: number, payload: Types.CampaignAudienceExportPayload) {
    return postJson<Types.CampaignAudienceExport>(`/api/campaigns/${id}/exports`, payload);
}

export function getCampaignExport(id: number, exportId: number, init: RequestInit = {}) {
    return getJson<Types.CampaignAudienceExport>(`/api/campaigns/${id}/exports/${exportId}`, init);
}

export function getCampaignEngagement(id: number, init: RequestInit = {}) {
    return getJson<Types.CampaignEngagement>(`/api/campaigns/${id}/engagement`, init);
}

/**
 * Fetches the public unsubscribe preview for a delivery token. Deliberately bypasses the workspace
 * and CSRF machinery: the route is unauthenticated and resolves the tenant from the token alone.
 * @param token the 64-character hex delivery token from the unsubscribe link
 * @param init optional fetch overrides (used by SSR to disable caching)
 * @returns the masked address, channel, and current suppression state
 */
export function getUnsubscribeInfo(token: string, init: RequestInit = {}) {
    return publicJson<Types.DeliveryUnsubscribeInfo>(
        `/api/delivery/unsubscribe/${token}`,
        "GET",
        init,
    );
}

/**
 * Confirms an unsubscribe for a delivery token, suppressing the resolved address. Public and
 * idempotent: repeat confirmations return the already-unsubscribed state without error.
 * @param token the 64-character hex delivery token from the unsubscribe link
 * @returns the masked address, channel, and resulting suppression state
 */
export function confirmUnsubscribe(token: string) {
    return publicJson<Types.DeliveryUnsubscribeInfo>(`/api/delivery/unsubscribe/${token}`, "POST");
}

export function getPersonConsent(personId: number, init: RequestInit = {}) {
    return getJson<Types.ContactChannelConsent[]>(`/api/persons/${personId}/consent`, init);
}

export function setPersonConsent(personId: number, payload: Types.ContactChannelConsentPayload) {
    return putJson<Types.ContactChannelConsent>(`/api/persons/${personId}/consent`, payload);
}

export function getSuppressions(init: RequestInit = {}) {
    return getJson<Types.SuppressionEntry[]>(`/api/suppressions`, init);
}

export function getSuppressionsFromCookie(cookie: string | null) {
    return safeReadWithCookie<Types.SuppressionEntry>((init) => getSuppressions(init), cookie);
}

export function createSuppression(payload: Types.SuppressionEntryPayload) {
    return postJson<Types.SuppressionEntry>(`/api/suppressions`, payload);
}

export function deleteSuppression(id: number) {
    return deleteJson<void>(`/api/suppressions/${id}`);
}

/**
 * Reports a client-side error boundary hit to the backend error sink. Authenticated,
 * workspace-scoped, and rate-limited server-side; callers must treat delivery as
 * best-effort (see `reportBoundaryError` for the guarded entry point).
 * @param payload the size-capped error report
 */
export function reportClientError(payload: Types.ClientErrorReportPayload) {
    return postJson<void>(`/api/client-errors`, payload);
}
