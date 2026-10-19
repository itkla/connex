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
 * @param hash the fragment the user is on, including the leading `#`, when there is one
 * @returns a relative sign-in address carrying the return path
 */
export function signInHref(pathname: string, search: string, hash = ""): string {
    const params = new URLSearchParams({ [RETURN_PATH_PARAM]: `${pathname}${search}${hash}` });
    return `${SIGN_IN_PATH}?${params.toString()}`;
}

/**
 * Reduces a caller-supplied return address to a same-origin path, or null when it isn't one. The
 * URL parser is the arbiter — pattern checks miss what parsing forgives, like the tab and newline
 * characters browsers strip before resolving `/\t/evil.com` into a protocol-relative address. The
 * reconstructed path must start with exactly one slash: an absolute same-origin address like
 * `https://origin//evil.example/x` parses to a protocol-relative pathname that would leave the
 * origin when navigated to, so it is refused even though its origin matched.
 * @param candidate the requested post-sign-in destination
 * @returns the path, query, and fragment to navigate to, or null when the address must not be used
 */
export function sameOriginPath(candidate: string | null): string | null {
    if (!candidate) return null;
    try {
        const url = new URL(candidate, window.location.origin);
        if (url.origin !== window.location.origin) return null;
        if (url.pathname.startsWith("//")) return null;
        return `${url.pathname}${url.search}${url.hash}`;
    } catch {
        return null;
    }
}

/** A fragment that names a section anchor on a page, such as `#members`. */
const SECTION_FRAGMENT = /^#[a-z0-9]+(?:-[a-z0-9]+)*$/;

/**
 * Re-attaches the section a reader was sent to when the sign-in redirect could not carry it.
 *
 * A retired settings address forwards before authentication to `/route#section`, and the proxy
 * then sends a signed-out reader to sign in with only the path and query, because a fragment never
 * reaches the server. The browser keeps that fragment on the sign-in page's own URL, so once the
 * reader is in it goes back onto a return path that has none. Only a plain section slug qualifies,
 * so nothing else that may sit in a fragment travels with it.
 * @param returnPath a same-origin return path from {@link sameOriginPath}
 * @param fragment the sign-in page's own `location.hash`
 * @returns the return path, with the section re-attached when it lacked one
 */
export function withSectionFragment(returnPath: string, fragment: string): string {
    if (returnPath.includes("#") || !SECTION_FRAGMENT.test(fragment)) return returnPath;
    return `${returnPath}${fragment}`;
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

    const { pathname, search, hash } = window.location;
    if (isAuthPath(pathname)) return false;
    if (redirecting) return true;

    redirecting = true;
    window.location.assign(signInHref(pathname, search, hash));
    return true;
}
