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
        throw new Error(await getErrorMessage(res));
    }

    const text = await res.text();

    if (!text) {
        return undefined as T;
    }

    return JSON.parse(text) as T;
}

async function getErrorMessage(res: Response): Promise<string> {
    const text = await res.text().catch(() => "");

    if (!text) {
        return `Request failed (${res.status})`;
    }

    try {
        const data = JSON.parse(text) as { message?: string; error?: string };
        return data.message ?? data.error ?? text;
    } catch {
        return text;
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