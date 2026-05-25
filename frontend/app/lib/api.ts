const API_BASE =
    typeof window === "undefined"
        ? process.env.API_URL ?? process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080"
        : "";

import * as Types from '@/app/lib/types';
// Types

async function requestJson<T>(
    path: string,
    init: RequestInit = {},
): Promise<T> {
    const res = await fetch(`${API_BASE}${path}`, {
        ...init,
        credentials: "include",
        headers: {
            ...(init.body ? { "Content-Type": "application/json" } : {}),
            ...init.headers,
        },
    });

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

// TODO: have the backend actually do something with these filters/queries
function buildQuery(params: Record<string, unknown>): string {
    const search = new URLSearchParams();
    for (const [key, value] of Object.entries(params)) {
        if (value !== undefined && value !== null) search.set(key, String(value));
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

export function logout() {
    return postJson<void>("/api/auth/logout");
}

/*
* == User profile management
*/

export function updateUser(id: number, payload: Types.UpdateUserPayload) {
    return putJson<Types.User>(`/api/users/${id}`, payload);
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

export function getContactById(id: number, init: RequestInit = {}) {
    return getJson<Types.Contact>(`/api/persons/${id}`, init);
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

export function createDeal(payload: Types.CreateDealPayload) {
    return postJson<Types.Deal>(`/api/deals`, payload);
}

export function updateDeal(id: number, payload: Types.UpdateDealPayload) {
    return putJson<Types.Deal>(`/api/deals/${id}`, payload);
}

export function deleteDeal(id: number, init: RequestInit = {}) {
    return deleteJson<void[]>(`/api/deals/${id}`, init);
}

export function getDealPeople(id: number, init: RequestInit = {}) {
    return getJson<Types.Contact[]>(`/api/deals/${id}/people`, init);
}

export function addDealPerson(id: number, personId: number, role: string, init: RequestInit = {}) {
    return postJson<void[]>(`/api/deals/${id}/people/${personId}`, { role }, init);
}

export function updateDealPersonRole(id: number, personId: number, role: string, init: RequestInit = {}) {
    return putJson<void[]>(`/api/deals/${id}/people/${personId}`, { role }, init);
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

/*
* == Tag management
*/

export function getTags(init: RequestInit = {}) {
    return getJson<Types.Tag[]>(`/api/tags`, init);
}

export function getTagsFromCookie(cookie: string | null) {
    return safeWithCookie<Types.Tag>((init) => getTags(init), cookie);
}