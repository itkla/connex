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

async function postJson<T>(path: string, body: unknown = {}): Promise<T> {
    return requestJson<T>(path, {
        method: "POST",
        body: JSON.stringify(body),
    });
}

async function getJson<T>(path: string, init: RequestInit = {}): Promise<T> {
    return requestJson<T>(path, { ...init, method: "GET" });
}

async function putJson<T>(path: string, body: unknown = {}): Promise<T> {
    return requestJson<T>(path, {
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

/*
* == Activity management
*/

export function getActivities(init: RequestInit = {}) { // get all activities for all users
    return getJson<Types.Activity[]>(`/api/activities`, init);
}

export function getActivitiesFromCookie(cookie: string | null) { // authenticate then get all activities
    return safeWithCookie<Types.Activity>((init) => getActivities(init), cookie);
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

/*
* == Contact management
*/

export function getContacts(init: RequestInit = {}) {
    return getJson<Types.Contact[]>(`/api/persons`, init);
}

export function getContactsFromCookie(cookie: string | null) {
    return safeWithCookie<Types.Contact>((init) => getContacts(init), cookie);
}

export function getContactById(id: number, init: RequestInit = {}) {
    return getJson<Types.Contact>(`/api/persons/${id}`, init);
}

// export function getContactFromCookie(id: number, cookie: string | null) {
//     return safeWithCookie<Contact>((init) => getContactById(id, init), cookie);
// }

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

/*
* == Deal management
*/

export function getDeals(init: RequestInit = {}) {
    return getJson<Types.Deal[]>(`/api/deals`, init);
}

export function getDealsFromCookie(cookie: string | null) {
    return safeWithCookie<Types.Deal>((init) => getDeals(init), cookie);
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

/*
* == Tag management
*/

export function getTags(init: RequestInit = {}) {
    return getJson<Types.Tag[]>(`/api/tags`, init);
}

export function getTagsFromCookie(cookie: string | null) {
    return safeWithCookie<Types.Tag>((init) => getTags(init), cookie);
}