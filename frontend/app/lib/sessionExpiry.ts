import { ApiError } from "@/app/lib/api";

const SIGN_IN_PATH = "/auth/login";
const AUTH_PATH_PREFIX = "/auth/";
const RETURN_PATH_PARAM = "redirect";

/**
 * True when a failure means the caller has no session rather than the action being refused.
 * A bodyless 401 or 403 is how Spring Security answers an unauthenticated caller, while a genuine
 * authorization denial always carries an explanatory body — the same reading
 * `loadCollection` applies on the server.
 * @param error a rejected request's reason
 * @returns whether the user needs to sign in again
 */
export function isSessionExpired(error: unknown): boolean {
    if (!(error instanceof ApiError)) return false;
    return error.status === 401 || (error.status === 403 && error.emptyBody === true);
}

/**
 * Builds the sign-in address that returns the user to where they were, using the same `redirect`
 * parameter the route guard sets so both entry points land on one convention.
 * @param pathname the path the user is on
 * @param search the query string the user is on, including the leading `?`
 * @returns a relative sign-in address carrying the return path
 */
export function signInHref(pathname: string, search: string): string {
    const params = new URLSearchParams({ [RETURN_PATH_PARAM]: `${pathname}${search}` });
    return `${SIGN_IN_PATH}?${params.toString()}`;
}

/**
 * True for the pages that sign a user in or recover an account, which must never be interrupted by
 * a redirect to sign in — that would loop.
 * @param pathname the path the user is on
 * @returns whether the path already belongs to the authentication flow
 */
export function isAuthPath(pathname: string): boolean {
    return pathname === SIGN_IN_PATH || pathname.startsWith(AUTH_PATH_PREFIX);
}

let redirecting = false;

/**
 * Forgets the pending sign-in navigation. In a browser the navigation itself ends this module's
 * life, so nothing ever needs to call this — tests do, because their window outlives the redirect.
 */
export function resetRedirectMemoForTests(): void {
    redirecting = false;
}

/**
 * Takes the browser to sign in, remembering where the user was. Does nothing while rendering on the
 * server or while an authentication page is already showing, and concurrent failures share the one
 * pending navigation instead of each issuing their own.
 * @returns whether the user is on their way to sign in
 */
export function redirectToSignIn(): boolean {
    if (typeof window === "undefined") return false;

    const { pathname, search } = window.location;
    if (isAuthPath(pathname)) return false;
    if (redirecting) return true;

    redirecting = true;
    window.location.assign(signInHref(pathname, search));
    return true;
}
