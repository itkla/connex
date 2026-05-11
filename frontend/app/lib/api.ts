const API_BASE =
    typeof window === "undefined"
        ? process.env.API_URL ?? process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080"
        : "";

// Auth

export type User = {
    id: number;
    username: string;
    displayName: string;
    email: string;
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

async function postJson<T>(path: string, body: unknown = {}): Promise<T> {
    return requestJson<T>(path, {
        method: "POST",
        body: JSON.stringify(body),
    });
}

async function getJson<T>(path: string, init: RequestInit = {}): Promise<T> {
    return requestJson<T>(path, { ...init, method: "GET" });
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