/** Resolves local authentication destinations without trusting an external origin. */
export function authReturnPath(candidate: unknown): string | null {
    if (typeof candidate !== "string" || !candidate.startsWith("/")) return null;
    try {
        const base = "https://connex.invalid";
        const url = new URL(candidate, base);
        if (url.origin !== base || url.pathname.startsWith("//")) return null;
        return `${url.pathname}${url.search}${url.hash}`;
    } catch {
        return null;
    }
}

/** Preserves the safe return destination when switching between sign-in and registration. */
export function authSwitchHref(path: string, redirect: string | null): string {
    const destination = authReturnPath(redirect);
    return destination ? `${path}?${new URLSearchParams({ redirect: destination })}` : path;
}

/** Defers optional personal workspace creation while the browser returns to an invitation. */
export function returnsToInvitation(candidate: unknown): boolean {
    const destination = authReturnPath(candidate);
    if (!destination) return false;
    const pathname = new URL(destination, "https://connex.invalid").pathname;
    return pathname === "/invite" || pathname === "/invite-link";
}
