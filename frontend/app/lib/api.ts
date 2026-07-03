const API_BASE =
    typeof window === "undefined"
        ? process.env.API_URL ?? process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080"
        : "";

import * as Types from '@/app/lib/types';
// Types

function clientLocale(): string | null {
    if (typeof document === "undefined") {
        return null;
    }
    const match = document.cookie.match(/(?:^|;\s*)NEXT_LOCALE=([^;]+)/);
    return match ? decodeURIComponent(match[1]) : null;
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

// CSRF token, fetched once from the backend and echoed in a header on state-changing requests.
// The frontend and backend can be different origins, so the token is delivered via this endpoint
// rather than a cookie the JS would otherwise be unable to read cross-origin.
let csrfTokenCache: { token: string; headerName: string } | null = null;

async function fetchCsrfToken(): Promise<{ token: string; headerName: string } | null> {
    try {
        const res = await fetch(`${API_BASE}/api/auth/csrf`, { credentials: "include" });
        if (!res.ok) return null;
        const text = await res.text();
        if (!text) return null;
        const data = JSON.parse(text) as { token?: string; headerName?: string };
        return data.token && data.headerName ? { token: data.token, headerName: data.headerName } : null;
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

function isMutating(method?: string): boolean {
    const m = (method ?? "GET").toUpperCase();
    return m !== "GET" && m !== "HEAD" && m !== "OPTIONS";
}

async function requestJson<T>(
    path: string,
    init: RequestInit = {},
): Promise<T> {
    const locale = clientLocale();
    const workspaceId = clientWorkspaceId();
    const mutating = isMutating(init.method);

    const send = (csrf: Record<string, string>) =>
        fetch(`${API_BASE}${path}`, {
            ...init,
            credentials: "include",
            headers: {
                ...(init.body ? { "Content-Type": "application/json" } : {}),
                ...(locale ? { "Accept-Language": locale } : {}),
                ...(workspaceId ? { "X-Workspace-Id": workspaceId } : {}),
                ...csrf,
                ...init.headers,
            },
        });

    let res = await send(mutating ? await csrfHeader() : {});

    // A stale or missing CSRF token surfaces as 403; refresh it once and retry.
    if (res.status === 403 && mutating && typeof window !== "undefined") {
        res = await send(await csrfHeader(true));
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

async function postJson<T>(path: string, body: unknown = {}, init: RequestInit = {}): Promise<T> {
    return requestJson<T>(path, {
        ...init,
        method: "POST",
        body: JSON.stringify(body),
    });
}

async function getJson<T>(path: string, init: RequestInit = {}): Promise<T> {
    return requestJson<T>(path, { ...init, method: "GET" });
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

/*
* == Authentication
*/

export type ApiFieldErrors = Record<string, string>;

export class ApiError extends Error {
    status: number;
    fieldErrors?: ApiFieldErrors;

    constructor(message: string, status: number, fieldErrors?: ApiFieldErrors) {
        super(message);
        this.name = "ApiError";
        this.status = status;
        this.fieldErrors = fieldErrors;
    }
}

export function isFieldError(err: unknown): err is ApiError & { fieldErrors: ApiFieldErrors } {
    return err instanceof ApiError && !!err.fieldErrors && Object.keys(err.fieldErrors).length > 0;
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
            const { message, error, ...fieldErrors } = data;
            const fields = Object.keys(fieldErrors).length > 0 ? fieldErrors : undefined;

            return new ApiError(
                message ?? error ?? "Please fix the highlighted fields.",
                res.status,
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
    return postJson<Types.AuthResponse>("/api/auth/login", payload);
}

/**
 * POST endpoint to register a new user.
 * 
 * @param payload
 * @return
 */
export function register(payload: Types.RegisterPayload) {
    return postJson<Types.AuthResponse>("/api/auth/register", payload);
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

// The attachment entity types (see the <Attachments> usages) mapped to their
// workspace-scoped backend GET, used to authorize a blob write against the caller's
// tenant. Types absent here are denied (fail closed).
const ATTACHMENT_ENTITY_ENDPOINTS: Record<string, string> = {
    company: "/api/companies",
    person: "/api/persons",
    deal: "/api/deals",
    user: "/api/users",
};

/**
 * Server-side (route-handler) probe: performs a workspace-scoped backend GET with the
 * caller's forwarded cookie and reports whether it resolves (HTTP 2xx). Used by the
 * upload blob routes to authorize the target before writing/deleting a file, so a valid
 * session alone cannot touch another tenant's entity.
 * @param cookie the forwarded request cookie header (session + workspace)
 * @param path the backend path to probe (e.g. `/api/companies/12`)
 * @returns true when the backend resolves the resource for the caller's workspace
 */
export async function backendResolves(cookie: string | null, path: string): Promise<boolean> {
    if (!cookie) {
        return false;
    }
    try {
        const res = await fetch(`${API_BASE}${path}`, { headers: { cookie }, cache: "no-store" });
        return res.ok;
    } catch {
        return false;
    }
}

/**
 * Whether the caller's active workspace may access the given attachment entity,
 * checked against the backend. Unknown entity types or non-integer ids are denied.
 * @param cookie the forwarded request cookie header
 * @param entityType the owning entity type (company/person/deal/user)
 * @param entityId the owning entity id
 * @returns true when the caller's workspace owns the entity
 */
export async function workspaceCanAccessEntity(
    cookie: string | null,
    entityType: string,
    entityId: number,
): Promise<boolean> {
    const base = ATTACHMENT_ENTITY_ENDPOINTS[entityType.trim().toLowerCase()];
    if (!base || !Number.isInteger(entityId)) {
        return false;
    }
    return backendResolves(cookie, `${base}/${entityId}`);
}

export function logout() {
    return postJson<void>("/api/auth/logout");
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

/*
* == User profile management
*/

export function updateUser(id: number, payload: Types.UpdateUserPayload) {
    return putJson<Types.User>(`/api/users/${id}`, payload);
}

export function updateMyTimezone(timezone: string) {
    return patchJson<Types.User>("/api/users/me", { timezone });
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

export function getTasks(init: RequestInit = {}) { // get all tasks for all users
    return getJson<Types.Task[]>(`/api/tasks`, init);
}

export function getTasksFromCookie(cookie: string | null) { // authenticate then get all tasks
    return safeWithCookie<Types.Task>((init) => getTasks(init), cookie);
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

export function getActivities(init: RequestInit = {}) { // get all activities for all users
    return getJson<Types.Activity[]>(`/api/activities`, init);
}

export function getActivitiesFromCookie(cookie: string | null) { // authenticate then get all activities
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

// get all notes for all users
export function getNotes(init: RequestInit = {}) {
    return getJson<Types.Note[]>(`/api/notes`, init);
}

export function getNotesFromCookie(cookie: string | null) { // authenticate then get all notes
    return safeWithCookie<Types.Note>((init) => getNotes(init), cookie);
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
    return getJson<Types.Company[]>(`/api/companies`, init);
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

export function deleteCompany(id: number, init: RequestInit = {}) {
    return deleteJson<void[]>(`/api/companies/${id}`, init);
}

export function getCompanyPeople(id: number, init: RequestInit = {}) {
    return getJson<Types.Contact[]>(`/api/companies/${id}/people`, init);
}

export function getCompanyDeals(id: number, init: RequestInit = {}) {
    return getJson<Types.Deal[]>(`/api/companies/${id}/deals`, init);
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
    const locale = clientLocale();
    const workspaceId = clientWorkspaceId();
    const res = await fetch(`${API_BASE}${path}`, {
        credentials: "include",
        headers: {
            ...(locale ? { "Accept-Language": locale } : {}),
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

/** Ids of every contact matching the active filter (for "select all matching", beyond the loaded page). */
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

export function getContactTemperatures(init: RequestInit = {}) {
    return getJson<Types.RelationshipTemperature[]>(`/api/scoring/contacts`, init);
}

export function getContactTemperaturesFromCookie(cookie: string | null) {
    return safeWithCookie<Types.RelationshipTemperature>((init) => getContactTemperatures(init), cookie);
}

export function getCompanyTemperatures(init: RequestInit = {}) {
    return getJson<Types.RelationshipTemperature[]>(`/api/scoring/companies`, init);
}

export function getCompanyTemperaturesFromCookie(cookie: string | null) {
    return safeWithCookie<Types.RelationshipTemperature>((init) => getCompanyTemperatures(init), cookie);
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

export function getIntroSuggestionsFromCookie(cookie: string | null, limit?: number) {
    return safeWithCookie<Types.IntroSuggestion>((init) => getIntroSuggestions(init, limit), cookie);
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

export function deleteContact(id: number, init: RequestInit = {}) {
    return deleteJson<void[]>(`/api/persons/${id}`, init);
}

export function updateContact(id: number, payload: Types.UpdateContactPayload) {
    return putJson<Types.Contact>(`/api/persons/${id}`, payload);
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
    return getJson<Types.Deal[]>(`/api/deals`, init);
}

export function getDealsFromCookie(cookie: string | null) {
    return safeWithCookie<Types.Deal>((init) => getDeals(init), cookie);
}

export function getDealById(id: number, init: RequestInit = {}) {
    return getJson<Types.Deal>(`/api/deals/${id}`, init);
}

export function getDealSummary(id: number, init: RequestInit = {}) {
    return getJson<Types.DealSummary>(`/api/deals/${id}/summary`, init);
}

/** Risk assessment for every at-risk open deal in the active workspace, highest risk first. */
export function getDealRisks(init: RequestInit = {}) {
    return getJson<Types.DealRisk[]>(`/api/deals/risk`, init);
}

/** Risk assessment for a single deal; {@code level} is {@code "none"} when it is not at risk. */
export function getDealRisk(id: number, init: RequestInit = {}) {
    return getJson<Types.DealRisk>(`/api/deals/${id}/risk`, init);
}

export function getDealRisksFromCookie(cookie: string | null) {
    return safeWithCookie<Types.DealRisk>((init) => getDealRisks(init), cookie);
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

export function getDealCollaborators(id: number, init: RequestInit = {}) {
    return getJson<Types.User[]>(`/api/deals/${id}/collaborators`, init);
}

export function replaceDealCollaborators(id: number, userIds: number[]) {
    return putJson<Types.User[]>(`/api/deals/${id}/collaborators`, { userIds });
}

export function getDealPeople(id: number, init: RequestInit = {}) {
    return getJson<Types.Contact[]>(`/api/deals/${id}/people`, init);
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
    return getJson<Types.Page<Types.Notification>>(`/api/notifications${buildQuery(params)}`, {
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
    return postJson<Types.NotificationCounts>("/api/notifications/read-all");
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
 * Records an attachment after its binary has been stored via the Next.js upload route.
 * @param payload - The attachment metadata (entity, url, file name, etc.)
 */
export function createAttachment(payload: Types.CreateAttachmentPayload) {
    return postJson<Types.Attachment>(`/api/attachments`, payload);
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
    return postJson<Types.Workspace>(`/api/workspaces`, { name });
}

export function switchWorkspace(id: number) {
    return postJson<void>(`/api/workspaces/${id}/switch`, {});
}

export function getPendingWorkspaces(init: RequestInit = {}) {
    return getJson<Types.Workspace[]>(`/api/workspaces/pending`, { cache: "no-store", ...init });
}

export function acceptWorkspace(id: number) {
    return postJson<Types.Workspace>(`/api/workspaces/${id}/accept`, {});
}

export function declineWorkspace(id: number) {
    return postJson<void>(`/api/workspaces/${id}/decline`, {});
}

export function leaveWorkspace(id: number) {
    return postJson<void>(`/api/workspaces/${id}/leave`, {});
}

export function getWorkspaceMembers(workspaceId: number, init: RequestInit = {}) {
    return getJson<Types.WorkspaceMember[]>(`/api/workspaces/${workspaceId}/members`, { cache: "no-store", ...init });
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

export function getPermissionCatalog(init: RequestInit = {}) {
    return getJson<string[]>(`/api/permissions`, { cache: "no-store", ...init });
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
    return postJson<Types.Workspace>(`/api/invites/${token}/accept`, {});
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
    return postJson<Types.Workspace>(`/api/invite-links/${token}/accept`, {});
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
