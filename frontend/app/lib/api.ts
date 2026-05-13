const API_BASE =
    typeof window === "undefined"
        ? process.env.API_URL ?? process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080"
        : "";


// Types
export type User = {
    id: number;
    username: string;
    displayName: string;
    email: string;
    createdAt: string;
    updatedAt: string;
    lastLoginAt?: string;
    profilePictureUrl?: string;
};

export type LoginPayload = {
    username: string;
    password: string;
};

export type RegisterPayload = {
    username: string;
    password: string;
    displayName: string;
    email: string;
};

export type AuthResponse = {
    message: string;
};

export type UpdateUserPayload = {
    username: string;
    displayName: string;
    email: string;
    profilePictureUrl?: string;
};

export type Task = {
    id: number;
    description: string;
    completed: boolean;
    dueDate?: string;
    assignedTo: number;
    person?: number | null;
    deal?: number | null;
    createdAt: string;
    updatedAt: string;
};

export type Activity = {
    id: number;
    type: string;
    subject: string;
    notes?: string;
    person?: number | null;
    deal?: number | null;
    createdBy: number;
    timestamp?: string;
};

export type Note = {
    id: number;
    content: string;
    author: number;
    person?: number | null;
    deal?: number | null;
    createdAt: string;
    updatedAt: string;
};

export type Company = {
    id: number;
    name: string;
    website: string;
    industry: string;
    phone: string;
    address: string;
    createdAt: string;
    updatedAt: string;
};

export type Contact = {
    id: number;
    name: string;
    email: string;
    phone: string;
    company: number;
    title: string;
    createdAt: string;
    updatedAt: string;
};

export type Deal = {
    id: number;
    name: string;
    value: number;
    currency: string;
    pipeline: number;
    stage: number;
    company: number;
    expectedCloseDate: string;
    closedAt: string;
    createdAt: string;
    updatedAt: string;
};

export type Pipeline = {
    id: number;
    name: string;
    createdAt: string;
    updatedAt: string;
};

export type Tag = {
    id: number;
    name: string;
    color: string;
    createdAt: string;
    updatedAt: string;
};

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
export function login(payload: LoginPayload) {
    return postJson<AuthResponse>("/api/auth/login", payload);
}

/**
 * POST endpoint to register a new user.
 * 
 * @param payload
 * @return
 */
export function register(payload: RegisterPayload) {
    return postJson<AuthResponse>("/api/auth/register", payload);
}

/**
 * Retrieves the currently authenticated user's profile.
 * 
 * @returns A promise that resolves to the authenticated user's profile information
 * @throws An error if the profile retrieval request fails, including the response text if available
 */
export function me(init: RequestInit = {}) {
    return getJson<User>("/api/auth/me", init);
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

export function updateUser(id: number, payload: UpdateUserPayload) {
    return putJson<User>(`/api/users/${id}`, payload);
}

/*
* == User-associated records
*/

export function getUserTasks(id: number, init: RequestInit = {}) {
    return getJson<Task[]>(`/api/users/${id}/tasks`, init);
}

export function getUserActivities(id: number, init: RequestInit = {}) {
    return getJson<Activity[]>(`/api/users/${id}/activities`, init);
}

export function getUserNotes(id: number, init: RequestInit = {}) {
    return getJson<Note[]>(`/api/users/${id}/notes`, init);
}

export function getUserTasksFromCookie(id: number, cookie: string | null) {
    return safeWithCookie<Task>((init) => getUserTasks(id, init), cookie);
}

export function getUserActivitiesFromCookie(id: number, cookie: string | null) {
    return safeWithCookie<Activity>((init) => getUserActivities(id, init), cookie);
}

export function getUserNotesFromCookie(id: number, cookie: string | null) {
    return safeWithCookie<Note>((init) => getUserNotes(id, init), cookie);
}

/*
* == Task management
*/

export function getTasks(init: RequestInit = {}) { // get all tasks for all users
    return getJson<Task[]>(`/api/tasks`, init);
}

export function getTasksFromCookie(cookie: string | null) { // authenticate then get all tasks
    return safeWithCookie<Task>((init) => getTasks(init), cookie);
}

/*
* == Activity management
*/

export function getActivities(init: RequestInit = {}) { // get all activities for all users
    return getJson<Activity[]>(`/api/activities`, init);
}

export function getActivitiesFromCookie(cookie: string | null) { // authenticate then get all activities
    return safeWithCookie<Activity>((init) => getActivities(init), cookie);
}

/*
* == Note management
*/

// get all notes for all users
export function getNotes(init: RequestInit = {}) {
    return getJson<Note[]>(`/api/notes`, init);
}

export function getNotesFromCookie(cookie: string | null) { // authenticate then get all notes
    return safeWithCookie<Note>((init) => getNotes(init), cookie);
}

/*
* == Company management
*/

export function getCompanies(init: RequestInit = {}) {
    return getJson<Company[]>(`/api/companies`, init);
}

export function getCompaniesFromCookie(cookie: string | null) {
    return safeWithCookie<Company>((init) => getCompanies(init), cookie);
}

/*
* == Contact management
*/

export function getContacts(init: RequestInit = {}) {
    return getJson<Contact[]>(`/api/persons`, init);
}

export function getContactsFromCookie(cookie: string | null) {
    return safeWithCookie<Contact>((init) => getContacts(init), cookie);
}

/*
* == Deal management
*/

export function getDeals(init: RequestInit = {}) {
    return getJson<Deal[]>(`/api/deals`, init);
}

export function getDealsFromCookie(cookie: string | null) {
    return safeWithCookie<Deal>((init) => getDeals(init), cookie);
}

/*
* == Pipeline management
*/

export function getPipelines(init: RequestInit = {}) {
    return getJson<Pipeline[]>(`/api/pipelines`, init);
}

export function getPipelinesFromCookie(cookie: string | null) {
    return safeWithCookie<Pipeline>((init) => getPipelines(init), cookie);
}

/*
* == Tag management
*/

export function getTags(init: RequestInit = {}) {
    return getJson<Tag[]>(`/api/tags`, init);
}

export function getTagsFromCookie(cookie: string | null) {
    return safeWithCookie<Tag>((init) => getTags(init), cookie);
}