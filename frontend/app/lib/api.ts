const API_BASE =
    typeof window === "undefined"
        ? process.env.API_URL ?? process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080"
        : "";

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
// header on every client request. SSR callers forward the cookie itself (see safeWithCookie).
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

const CLIENT_IDENTITY_EVENT_KEY = "connex:client-request-identity";
let csrfTokenCache: CsrfBootstrap | null = null;
let clientRequestIdentityEpoch = 0;
const inFlightAiMutations = new Map<string, InFlightAiMutation>();
const inFlightReportGenerations = new Map<string, {
    controller: AbortController;
    request: Promise<Types.ReportDocument>;
}>();
const inFlightReportRequests = new Set<AbortController>();

if (typeof window !== "undefined") {
    window.addEventListener("storage", (event) => {
        if (event.key === CLIENT_IDENTITY_EVENT_KEY) {
            invalidateClientRequestIdentity();
            if (event.newValue?.startsWith("refresh:")) {
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
}

function broadcastClientRequestIdentityTransition(refreshTabs: boolean) {
    if (typeof window === "undefined") return;
    try {
        const eventId = typeof window.crypto.randomUUID === "function"
            ? window.crypto.randomUUID()
            : `${Date.now()}:${clientRequestIdentityEpoch}`;
        const action = refreshTabs ? "refresh" : "invalidate";
        window.localStorage.setItem(CLIENT_IDENTITY_EVENT_KEY, `${action}:${eventId}`);
    } catch {
        return;
    }
}

function signalClientRequestIdentityTransition(refreshTabs: boolean) {
    invalidateClientRequestIdentity();
    broadcastClientRequestIdentityTransition(refreshTabs);
}

async function currentClientRequestIdentity(): Promise<string | null> {
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
            broadcastClientRequestIdentityTransition(true);
        }
    }
    csrfTokenCache = currentCsrf;
    const locale = localeFromCookieHeader(document.cookie);
    return [
        clientRequestIdentityEpoch,
        workspaceId,
        locale,
        currentCsrf.requestIdentity,
        currentCsrf.headerName,
        currentCsrf.token,
    ]
        .join("\u0000");
}

async function withClientRequestIdentityReset<T>(request: () => Promise<T>): Promise<T> {
    signalClientRequestIdentityTransition(false);
    try {
        const result = await request();
        signalClientRequestIdentityTransition(true);
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
): Promise<T> {
    const locale = requestLocale(init);
    const workspaceId = clientWorkspaceId();
    const mutating = isMutating(init.method);
    const stepUpGeneration = passkeyStepUpGeneration;
    const hasMultipartBody = typeof FormData !== "undefined" && init.body instanceof FormData;

    const send = (csrf: Record<string, string>) =>
        fetch(`${API_BASE}${path}`, {
            ...init,
            credentials: "include",
            headers: {
                ...(init.body && !hasMultipartBody ? { "Content-Type": "application/json" } : {}),
                "Accept-Language": locale,
                ...(workspaceId ? { "X-Workspace-Id": workspaceId } : {}),
                ...csrf,
                ...init.headers,
            },
        });

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

    const text = await res.text();

    if (!text) {
        return undefined as T;
    }

    return JSON.parse(text) as T;
}

async function requestMultipart<T>(
    path: string,
    method: "POST" | "PUT",
    body: FormData,
): Promise<T> {
    const locale = requestLocale({});
    const workspaceId = clientWorkspaceId();
    const stepUpGeneration = passkeyStepUpGeneration;
    const send = (csrf: Record<string, string>) => fetch(`${API_BASE}${path}`, {
        method,
        body,
        credentials: "include",
        headers: {
            "Accept-Language": locale,
            ...(workspaceId ? { "X-Workspace-Id": workspaceId } : {}),
            ...csrf,
        },
    });
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

/**
 * De-duplicates concurrent AI mutations only within one opaque server-issued authenticated-session
 * generation, active workspace, and locale. Requests without a provable identity bypass de-duplication.
 */
async function dedupedAiPost<T>(path: string, init: RequestInit = {}): Promise<T> {
    if (typeof window === "undefined" || Object.keys(init).length > 0) {
        return postJson<T>(path, {}, init);
    }
    const identity = await currentClientRequestIdentity();
    if (identity == null) {
        return postJson<T>(path, {}, init);
    }
    const key = `${identity}\u0000${path}`;
    const existing = inFlightAiMutations.get(key);
    if (existing) {
        return existing.request as Promise<T>;
    }
    const controller = new AbortController();
    const request = (async () => {
        const response = await postJson<T>(path, {}, { signal: controller.signal });
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

async function safeWithCookie<T>(
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
 * As {@link safeWithCookie}, but reports a fetch failure distinctly from an empty result so the
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

    constructor(message: string, status: number, code?: string, fieldErrors?: ApiFieldErrors) {
        super(message);
        this.name = "ApiError";
        this.status = status;
        this.code = code;
        this.fieldErrors = fieldErrors;
    }
}

export function isFieldError(err: unknown): err is ApiError & { fieldErrors: ApiFieldErrors } {
    return err instanceof ApiError && !!err.fieldErrors && Object.keys(err.fieldErrors).length > 0;
}

/** Classifies card scan/import failures into stable UI states without exposing backend messages. */
export function businessCardRequestErrorKind(error: unknown): Types.BusinessCardRequestErrorKind {
    if (error instanceof Error && error.name === "AbortError") return "aborted";
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
            return "failed";
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
        return new ApiError(`Request failed (${res.status})`, res.status);
    }

    try {
        const data = JSON.parse(text) as unknown;

        if (isStringRecord(data)) {
            const { message, error, code, ...fieldErrors } = data;
            const fields = Object.keys(fieldErrors).length > 0 ? fieldErrors : undefined;

            return new ApiError(
                message ?? error ?? "Please fix the highlighted fields.",
                res.status,
                code,
                fields,
            );
        }

        return new ApiError(text, res.status);
    } catch {
        return new ApiError(text, res.status);
    }
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

export async function getCurrentUserFromCookie(cookie: string | null) {
    if (!cookie) {
        return null;
    }

    try {
        return await me({
            headers: { cookie },
            cache: "no-store",
        });
    } catch {
        return null;
    }
}

export function logout() {
    return withClientRequestIdentityReset(() => postJson<void>("/api/auth/logout"));
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
 * login, instance-managed mail, and business-card scanning. Consolidates the former per-feature
 * `/enabled` endpoints.
 * @param init optional fetch overrides
 * @returns the resolved instance capabilities
 */
export function getCapabilities(init: RequestInit = {}) {
    return getJson<Types.InstanceCapabilities>("/api/capabilities", { cache: "no-store", ...init });
}

/**
 * The fail-safe capabilities used when {@link getCapabilities} cannot be reached: everything off,
 * so optional UI stays hidden rather than rendering a feature the backend will reject.
 */
export const DEFAULT_CAPABILITIES: Types.InstanceCapabilities = {
    sso: false,
    socialLogin: { google: false, microsoft: false },
    mailManaged: false,
    businessCardScanning: false,
};

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

export function getUsers(init: RequestInit = {}) {
    return getJson<Types.User[]>(`/api/users`, init);
}

export function getUsersFromCookie(cookie: string | null) {
    return safeWithCookie<Types.User>((init) => getUsers(init), cookie);
}

export function getUsersResultFromCookie(cookie: string | null) {
    return resultWithCookie<Types.User[]>((init) => getUsers(init), cookie);
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
    return safeWithCookie<Types.Task>((init) => getUserTasks(id, init), cookie);
}

export function getUserActivitiesFromCookie(id: number, cookie: string | null) {
    return safeWithCookie<Types.Activity>((init) => getUserActivities(id, init), cookie);
}

export function getUserNotesFromCookie(id: number, cookie: string | null) {
    return safeWithCookie<Types.Note>((init) => getUserNotes(id, init), cookie);
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
    return safeWithCookie<Types.Task>((init) => getTasks(init), cookie);
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
    return safeWithCookie<Types.Activity>((init) => getActivities(init), cookie);
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
    return safeWithCookie<Types.Note>((init) => getNotes(init), cookie);
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
    const query = buildQuery({ q: params.q, industry: params.industry, noIndustry: params.noIndustry, ids: params.ids });
    return getJson<number[]>(`/api/companies/ids${query}`, init);
}

export function getCompanySegmentIds(params: Types.CompanySegmentPageParams, init: RequestInit = {}) {
    return postJson<number[]>(`/api/companies/segment/ids`, params, init);
}

export function getCompaniesFromCookie(cookie: string | null) {
    return safeWithCookie<Types.Company>((init) => getCompanies(init), cookie);
}

export function getCompanyById(id: number, init: RequestInit = {}) {
    return getJson<Types.Company>(`/api/companies/${id}`, init);
}

export function createCompany(payload: Types.CreateCompanyPayload) {
    return postJson<Types.Company>(`/api/companies`, payload);
}

export function updateCompany(id: number, payload: Types.UpdateCompanyPayload) {
    return putJson<Types.Company>(`/api/companies/${id}`, payload);
}

export async function uploadCompanyLogo(companyId: number, file: File) {
    const formData = new FormData();
    formData.append("file", file);
    const company = await requestMultipart<Types.Company>(`/api/companies/${companyId}/logo`, "PUT", formData);
    return company.logoUrl;
}

export function deleteCompany(id: number, init: RequestInit = {}) {
    return deleteJson<void[]>(`/api/companies/${id}`, init);
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
    return postJson<void[]>(`/api/companies/${id}/tags/${tagId}`, {}, init);
}

export function removeCompanyTag(id: number, tagId: number, init: RequestInit = {}) {
    return deleteJson<void[]>(`/api/companies/${id}/tags/${tagId}`, init);
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
    return safeWithCookie<Types.Contact>((init) => getContacts(filters, init), cookie);
}

export function getContactsPage(params: Types.ContactsPageParams = {}, init: RequestInit = {}) {
    return getJson<Types.Page<Types.Contact>>(`/api/persons/page${buildQuery(params)}`, init);
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

/**
 * Streams a CSV from the backend with the active workspace + locale headers (a plain anchor would
 * not carry the workspace context) and triggers a browser download.
 */
export async function downloadCsv(path: string, filename: string): Promise<void> {
    const locale = localeFromCookieHeader(document.cookie);
    const workspaceId = clientWorkspaceId();
    const res = await fetch(`${API_BASE}${path}`, {
        credentials: "include",
        headers: {
            "Accept-Language": locale,
            ...(workspaceId ? { "X-Workspace-Id": workspaceId } : {}),
        },
    });
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

export function exportContactsCsv(params: Types.ContactsPageParams = {}) {
    const query = buildQuery({ q: params.q, companies: params.companies, titles: params.titles, noCompany: params.noCompany });
    return downloadCsv(`/api/exports/persons${query}`, "contacts.csv");
}

export function exportCompaniesCsv(ids?: number[]) {
    const query = buildQuery({ ids: ids && ids.length <= 1000 ? ids : undefined });
    return downloadCsv(`/api/exports/companies${query}`, "companies.csv");
}

export function exportDealsCsv(ids?: number[]) {
    const query = buildQuery({ ids: ids && ids.length <= 1000 ? ids : undefined });
    return downloadCsv(`/api/exports/deals${query}`, "deals.csv");
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

export function bulkDeleteContacts(ids: number[]) {
    return runBulk(ids, (chunk) => postJson<Types.BulkOperationResult>(`/api/persons/bulk/delete`, { ids: chunk }));
}

export function bulkAddTagToCompanies(ids: number[], tagId: number) {
    return runBulk(ids, (chunk) => postJson<Types.BulkOperationResult>(`/api/companies/bulk/tags/add`, { ids: chunk, tagId }));
}

export function bulkRemoveTagFromCompanies(ids: number[], tagId: number) {
    return runBulk(ids, (chunk) => postJson<Types.BulkOperationResult>(`/api/companies/bulk/tags/remove`, { ids: chunk, tagId }));
}

export function bulkDeleteCompanies(ids: number[]) {
    return runBulk(ids, (chunk) => postJson<Types.BulkOperationResult>(`/api/companies/bulk/delete`, { ids: chunk }));
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
    const query = buildQuery({ q: params.q, companies: params.companies, titles: params.titles, noCompany: params.noCompany });
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
    return safeWithCookie<Types.JobMove>((init) => getRecentMoves(init), cookie);
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

export function getContactTemperaturesFromCookie(cookie: string | null, ids: number[]) {
    if (ids.length === 0) return Promise.resolve([] as Types.RelationshipTemperature[]);
    return safeWithCookie<Types.RelationshipTemperature>((init) => getContactTemperatures(ids, init), cookie);
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
 * AI-generated "why introduce them" rationale for a suggested reverse introduction. Returns a graceful
 * unavailability result (never an error) when AI is not configured or the pair is not a current
 * suggestion; generation is slow (an LLM call), so invoke client-side. Mirrors {@link generateDealRationale}.
 */
export function generateIntroRationale(personAId: number, personBId: number, init: RequestInit = {}) {
    return dedupedAiPost<Types.IntroRationale>(
        `/api/introductions/suggestions/rationale${buildQuery({ personA: personAId, personB: personBId })}`,
        init,
    );
}

export function getIntroSuggestionsFromCookie(cookie: string | null, limit?: number) {
    return safeWithCookie<Types.IntroSuggestion>((init) => getIntroSuggestions(init, limit), cookie);
}

/**
 * As {@link getIntroSuggestionsFromCookie}, but failure-aware (see {@link resultWithCookie}), so
 * the introductions page can distinguish a backend fault from a genuinely empty workspace.
 */
export function getIntroSuggestionsResultFromCookie(cookie: string | null, limit?: number) {
    return resultWithCookie<Types.IntroSuggestion[]>((init) => getIntroSuggestions(init, limit), cookie);
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

export function createContact(payload: Types.CreateContactPayload) {
    return postJson<Types.Contact>(`/api/persons`, payload);
}

/** Reads contact candidates from one business-card image without mutating workspace data. */
export function scanBusinessCard(image: File, init: RequestInit = {}) {
    const body = new FormData();
    body.append("image", image, image.name);
    return postFormData<Types.BusinessCardScanResult>("/api/business-cards/scan", body, init);
}

/** Creates the reviewed contact and stores its source card in one backend transaction. */
export function importBusinessCard(draft: Types.BusinessCardImportDraft, init: RequestInit = {}) {
    const body = new FormData();
    body.append("image", draft.image, draft.image.name);
    body.append("contact", new Blob([JSON.stringify(draft.contact)], { type: "application/json" }));
    body.append("companyAction", new Blob([JSON.stringify(draft.companyAction)], { type: "application/json" }));
    const headers: Record<string, string> = {};
    new Headers(init.headers).forEach((value, key) => {
        headers[key] = value;
    });
    headers["Idempotency-Key"] = draft.requestId;
    return postFormData<Types.BusinessCardImportResult>("/api/business-cards/import", body, { ...init, headers });
}

export function deleteContact(id: number, init: RequestInit = {}) {
    return deleteJson<void[]>(`/api/persons/${id}`, init);
}

export function updateContact(id: number, payload: Types.UpdateContactPayload) {
    return putJson<Types.Contact>(`/api/persons/${id}`, payload);
}

export async function uploadContactPicture(contactId: number, file: File) {
    const formData = new FormData();
    formData.append("file", file);
    const contact = await requestMultipart<Types.Contact>(`/api/persons/${contactId}/profile-picture`, "PUT", formData);
    return contact.imageUrl;
}

export function updateContactEvaluation(id: number, payload: Types.UpdateContactEvaluationPayload) {
    return putJson<Types.Contact>(`/api/persons/${id}/evaluation`, payload);
}

export function deleteContactFromCookie(id: number, cookie: string | null) {
    return safeWithCookie<void>((init) => deleteContact(id, init), cookie);
}

export function getContactTags(id: number, init: RequestInit = {}) {
    return getJson<Types.Tag[]>(`/api/persons/${id}/tags`, init);
}
export function getContactTagsFromCookie(id: number, cookie: string | null) {
    return safeWithCookie<Types.Tag>((init) => getContactTags(id, init), cookie);
}

export function addContactTag(id: number, tagId: number, init: RequestInit = {}) {
    return postJson<void[]>(`/api/persons/${id}/tags/${tagId}`, {}, init);
}
export function addContactTagFromCookie(id: number, tagId: number, cookie: string | null) {
    return safeWithCookie<void>((init) => addContactTag(id, tagId, init), cookie);
}

export function removeContactTag(id: number, tagId: number, init: RequestInit = {}) {
    return deleteJson<void[]>(`/api/persons/${id}/tags/${tagId}`, init);
}
export function removeContactTagFromCookie(id: number, tagId: number, cookie: string | null) {
    return safeWithCookie<void>((init) => removeContactTag(id, tagId, init), cookie);
}

export function replaceContactTags(id: number, tagIds: number[], init: RequestInit = {}) {
    return putJson<Types.Tag[]>(`/api/persons/${id}/tags`, tagIds, init);
}
export function replaceContactTagsFromCookie(id: number, tagIds: number[], cookie: string | null) {
    return safeWithCookie<Types.Tag>((init) => replaceContactTags(id, tagIds, init), cookie);
}

export function getContactDeals(id: number, init: RequestInit = {}) {
    return getJson<Types.Deal[]>(`/api/persons/${id}/deals`, init);
}
export function getContactDealsFromCookie(id: number, cookie: string | null) {
    return safeWithCookie<Types.Deal>((init) => getContactDeals(id, init), cookie);
}

export function getContactActivities(id: number, init: RequestInit = {}) {
    return getJson<Types.Activity[]>(`/api/persons/${id}/activities`, init);
}
export function getContactActivitiesFromCookie(id: number, cookie: string | null) {
    return safeWithCookie<Types.Activity>((init) => getContactActivities(id, init), cookie);
}

export function getContactNotes(id: number, init: RequestInit = {}) {
    return getJson<Types.Note[]>(`/api/persons/${id}/notes`, init);
}
export function getContactNotesFromCookie(id: number, cookie: string | null) {
    return safeWithCookie<Types.Note>((init) => getContactNotes(id, init), cookie);
}

export function getContactTasks(id: number, init: RequestInit = {}) {
    return getJson<Types.Task[]>(`/api/persons/${id}/tasks`, init);
}
export function getContactTasksFromCookie(id: number, cookie: string | null) {
    return safeWithCookie<Types.Task>((init) => getContactTasks(id, init), cookie);
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

/** Returns one complete, server-bounded pipeline board for absolute Kanban ordering. */
export function getDealBoard(pipelineId: number, init: RequestInit = {}) {
    return getJson<Types.Deal[]>(`/api/deals/board${buildQuery({ pipelineId })}`, init);
}

export function getDealsFromCookie(cookie: string | null) {
    return safeWithCookie<Types.Deal>((init) => getDeals(init), cookie);
}

/**
 * Workspace-wide deal totals (open pipeline, closed forecast, realized revenue, counts),
 * grouped per currency, computed server-side over ALL matching deals — not just a page.
 * Optional filter params narrow the aggregation to match a filtered table view.
 */
export function getDealMetrics(params: Types.DealFilterParams = {}, init: RequestInit = {}) {
    return getJson<Types.DealMetrics>(`/api/deals/metrics${buildQuery(params)}`, init);
}

export function getDealMetricsFromCookie(cookie: string | null, params: Types.DealFilterParams = {}) {
    return getJson<Types.DealMetrics>(
        `/api/deals/metrics${buildQuery(params)}`,
        cookie ? { headers: { cookie }, cache: "no-store" } : {},
    );
}

/**
 * Stable filter-facet vocabulary (status, stage, pipeline, company, currency) with counts,
 * computed server-side over the whole workspace so options never vanish when the visible page
 * lacks them (e.g. the "Closed" status option).
 */
export function getDealFacets(init: RequestInit = {}) {
    return getJson<Types.DealFacets>(`/api/deals/facets`, init);
}

/**
 * Server-computed monthly revenue trend (realized won revenue by scheduled close month, projected
 * by expected-close month) over ALL deals, optionally scoped to a currency. The IANA timezone
 * applies the viewer's historical offset rules when bucketing realized revenue.
 */
export function getDealRevenueTimeseries(currency?: string, timezone?: string, init: RequestInit = {}) {
    return getJson<Types.DealRevenueSeries>(`/api/deals/revenue-timeseries${buildQuery({ currency, timezone })}`, init);
}

const withCookie = (cookie: string | null): RequestInit => (cookie ? { headers: { cookie }, cache: "no-store" } : {});

/**
 * Server-computed deal KPIs over ALL deals in {@code range} (30d/90d/12m), optionally scoped to a currency.
 * Replaces the client-side KPI/win-rate math over a bounded page slice.
 */
export function getDealKpis(currency?: string, range?: string, init: RequestInit = {}) {
    return getJson<Types.DealKpis>(`/api/deals/kpis${buildQuery({ currency, range })}`, init);
}

export function getDealKpisFromCookie(cookie: string | null, currency?: string, range?: string) {
    return getJson<Types.DealKpis>(`/api/deals/kpis${buildQuery({ currency, range })}`, withCookie(cookie));
}

/** Server-computed per-pipeline won-in-range + open rollup. */
export function getDealPipelineValue(currency?: string, range?: string, init: RequestInit = {}) {
    return getJson<Types.DealPipelineValue[]>(`/api/deals/pipeline-value${buildQuery({ currency, range })}`, init);
}

export function getDealPipelineValueFromCookie(cookie: string | null, currency?: string, range?: string) {
    return getJson<Types.DealPipelineValue[]>(`/api/deals/pipeline-value${buildQuery({ currency, range })}`, withCookie(cookie));
}

/** Server-computed per-stage open-deal age buckets. */
export function getDealAging(currency?: string, init: RequestInit = {}) {
    return getJson<Types.DealAging[]>(`/api/deals/aging${buildQuery({ currency })}`, init);
}

export function getDealAgingFromCookie(cookie: string | null, currency?: string) {
    return getJson<Types.DealAging[]>(`/api/deals/aging${buildQuery({ currency })}`, withCookie(cookie));
}

/** Server-computed top open/won deals, optionally scoped to a currency. */
export function getDealTop(currency?: string, init: RequestInit = {}) {
    return getJson<Types.DealTop>(`/api/deals/top${buildQuery({ currency })}`, init);
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

/** Server-computed activity counts by type per time bucket over {@code range} (30d/90d/12m). */
export function getActivityVolume(range?: string, init: RequestInit = {}) {
    return getJson<Types.ActivityVolumeBucket[]>(`/api/activities/volume${buildQuery({ range })}`, init);
}

export function getActivityVolumeFromCookie(cookie: string | null, range?: string) {
    return getJson<Types.ActivityVolumeBucket[]>(`/api/activities/volume${buildQuery({ range })}`, withCookie(cookie));
}

/** Server-computed per-user touch counts (activities + completed tasks + notes) over {@code range}. */
export function getTeamLeaderboard(range?: string, init: RequestInit = {}) {
    return getJson<Types.TeamLeaderboardEntry[]>(`/api/activities/leaderboard${buildQuery({ range })}`, init);
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
export function getTaskSummary(init: RequestInit = {}) {
    return getJson<Types.TaskSummary>(`/api/tasks/summary`, init);
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

/**
 * Server-computed per-stage open/closed rollup over ALL deals, optionally scoped to a currency.
 * Feeds the deals page stage-distribution chart.
 */
export function getDealStageDistribution(currency?: string, init: RequestInit = {}) {
    return getJson<Types.DealStageDistribution[]>(`/api/deals/stage-distribution${buildQuery({ currency })}`, init);
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
    return safeWithCookie<Types.DealRisk>((init) => getDealRisks(ids, init), cookie);
}

export function getDealRiskAnalyticsFromCookie(cookie: string | null) {
    return getJson<Types.DealRiskAnalytics>(`/api/deals/risk/analytics`, withCookie(cookie));
}

/**
 * AI-generated brief for a deal. Returns a graceful unavailability result (never an error) when AI
 * is not configured for the organization; generation is slow (an LLM call), so invoke client-side.
 */
export function generateDealBrief(id: number, refresh = false, init: RequestInit = {}) {
    return dedupedAiPost<Types.DealBrief>(
        `/api/deals/${id}/brief${refresh ? buildQuery({ refresh: true }) : ''}`,
        init,
    );
}

/**
 * AI-generated risk rationale for a deal. Returns a graceful unavailability result (never an error)
 * when the deal is not at risk or AI is not configured for the organization; generation is slow (an
 * LLM call), so invoke client-side.
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

export function createDeal(payload: Types.CreateDealPayload) {
    return postJson<Types.Deal>(`/api/deals`, payload);
}

export function updateDeal(id: number, payload: Types.UpdateDealPayload) {
    return putJson<Types.Deal>(`/api/deals/${id}`, payload);
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
    return getJson<Types.Contact[]>(`/api/deals/${id}/people`, init);
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
        { contextType, contextId, state: "unread", page: 1, size: 50 },
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

export function snoozeNotification(id: number, hours: number) {
    return postJson<Types.Notification>(`/api/notifications/${id}/snooze`, { hours });
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
    return safeWithCookie<Types.Pipeline>((init) => getPipelines(init), cookie);
}

/** Returns every pipeline stage visible in the active workspace in one request. */
export function getAllStages(init: RequestInit = {}) {
    return getJson<Types.Stage[]>(`/api/pipelines/stages`, init);
}

export function getAllStagesFromCookie(cookie: string | null) {
    return safeWithCookie<Types.Stage>((init) => getAllStages(init), cookie);
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
    return safeWithCookie<Types.Tag>((init) => getTags(init), cookie);
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
    try {
        return await search(query, { headers: { cookie }, cache: "no-store" });
    } catch {
        return EMPTY_SEARCH_RESULTS;
    }
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
    return safeWithCookie<Types.Attachment>((init) => getAttachments(entityType, entityId, init), cookie);
}

/**
 * Lists every attachment across all entities, each carrying its resolved
 * {@code entityLabel}. Powers the Files library page.
 */
export function getAllAttachments(init: RequestInit = {}) {
    return getJson<Types.Attachment[]>(`/api/attachments`, init);
}

export function getAllAttachmentsFromCookie(cookie: string | null) {
    return safeWithCookie<Types.Attachment>((init) => getAllAttachments(init), cookie);
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
* == Workspaces (tenancy)
*/

const EMPTY_WORKSPACES: Types.MyWorkspaces = { workspaces: [], activeWorkspaceId: null };

export function getMyWorkspaces(init: RequestInit = {}) {
    return getJson<Types.MyWorkspaces>(`/api/workspaces`, { cache: "no-store", ...init });
}

export async function getMyWorkspacesFromCookie(cookie: string | null): Promise<Types.MyWorkspaces> {
    if (!cookie) return EMPTY_WORKSPACES;
    try {
        return await getMyWorkspaces({ headers: { cookie }, cache: "no-store" });
    } catch {
        return EMPTY_WORKSPACES;
    }
}

export function createWorkspace(name: string) {
    return withClientRequestIdentityReset(() => postJson<Types.Workspace>(`/api/workspaces`, { name }));
}

export function switchWorkspace(id: number) {
    return withClientRequestIdentityReset(() => postJson<void>(`/api/workspaces/${id}/switch`, {}));
}

export function getPendingWorkspaces(init: RequestInit = {}) {
    return getJson<Types.Workspace[]>(`/api/workspaces/pending`, { cache: "no-store", ...init });
}

export function acceptWorkspace(id: number) {
    return withClientRequestIdentityReset(() => postJson<Types.Workspace>(`/api/workspaces/${id}/accept`, {}));
}

export function declineWorkspace(id: number) {
    return postJson<void>(`/api/workspaces/${id}/decline`, {});
}

export function leaveWorkspace(id: number) {
    return withClientRequestIdentityReset(() => postJson<void>(`/api/workspaces/${id}/leave`, {}));
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
    return getJson<Types.Rule[]>(`/api/rules`, { cache: "no-store", ...init });
}

export function createRule(payload: Types.RuleRequest) {
    return postJson<Types.Rule>(`/api/rules`, payload);
}

export function updateRule(id: number, payload: Types.RuleRequest) {
    return putJson<Types.Rule>(`/api/rules/${id}`, payload);
}

export function deleteRule(id: number) {
    return deleteJson<void>(`/api/rules/${id}`);
}

export function previewRule(recordType: Types.SavedViewRecordType, condition: Types.SegmentDefinition) {
    return postJson<Types.RulePreview>(`/api/rules/preview`, { recordType, condition });
}

export function getPermissionCatalog(init: RequestInit = {}) {
    return getJson<string[]>(`/api/permissions`, { cache: "no-store", ...init });
}

export function getEffectivePermissions(init: RequestInit = {}) {
    return getJson<string[]>(`/api/permissions/effective`, { cache: "no-store", ...init });
}

export function getEffectivePermissionsFromCookie(cookie: string | null) {
    return safeWithCookie<string>((init) => getEffectivePermissions(init), cookie);
}

/*
* == Custom field management
*/

export function getCustomFields(entityType?: Types.CustomFieldEntityType, init: RequestInit = {}) {
    const query = entityType ? `?entityType=${entityType}` : "";
    return getJson<Types.CustomFieldDefinition[]>(`/api/custom-fields${query}`, { cache: "no-store", ...init });
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
    return safeWithCookie<Types.CustomFieldEntry>((init) => getEntityCustomFields(entityType, id, init), cookie);
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

export function getSavedViews(recordType: Types.SavedViewRecordType, init: RequestInit = {}) {
    return getJson<Types.SavedView[]>(`/api/saved-views?recordType=${recordType}`, { cache: "no-store", ...init });
}

export function getSavedViewsFromCookie(recordType: Types.SavedViewRecordType, cookie: string | null) {
    return safeWithCookie<Types.SavedView>((init) => getSavedViews(recordType, init), cookie);
}

export function createSavedView(payload: Types.SavedViewInput) {
    return postJson<Types.SavedView>(`/api/saved-views`, payload);
}

export function updateSavedView(id: number, payload: Types.SavedViewInput) {
    return putJson<Types.SavedView>(`/api/saved-views/${id}`, payload);
}

export function deleteSavedView(id: number, init: RequestInit = {}) {
    return deleteJson<void>(`/api/saved-views/${id}`, init);
}

/*
* == Dashboard layout (per-user, per-workspace)
*/

export function getDashboardLayout(init: RequestInit = {}) {
    return getJson<Types.DashboardLayoutResponse>(`/api/dashboard-layout`, { cache: "no-store", ...init });
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
    return safeWithCookie<Types.ReportDefinition>((init) => getReports(init), cookie);
}

export function getReportTemplatesFromCookie(cookie: string | null) {
    return safeWithCookie<Types.ReportTemplate>((init) => getReportTemplates(init), cookie);
}

export function getReport(id: number, init: RequestInit = {}) {
    return getJson<Types.ReportDefinition>(`/api/reports/${id}`, { cache: "no-store", ...init });
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
    return safeWithCookie<Types.ReportGoal>((init) => getGoals(init), cookie);
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

export async function generateReport(id: number, payload: Types.ReportGenerateInput = {}) {
    if (typeof window === "undefined") {
        return postJson<Types.ReportDocument>(`/api/reports/${id}/generate`, payload);
    }
    const identity = await currentClientRequestIdentity();
    if (identity == null) {
        throw new Error("Unable to establish the authenticated report request identity");
    }
    const path = `/api/reports/${id}/generate`;
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

export function getSegmentFields(recordType: Types.SavedViewRecordType) {
    return getJson<Types.SegmentFields>(`/api/segments/fields?recordType=${recordType}`, { cache: "no-store" });
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
        () => postJson<Types.Workspace>(`/api/invites/${token}/accept`, {}),
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
        () => postJson<Types.Workspace>(`/api/invite-links/${token}/accept`, {}),
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
