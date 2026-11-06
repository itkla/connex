const FORBIDDEN_ESCAPES = /%(?:2f|5c|0[0-9a-f]|1[0-9a-f]|7f)|%c2%[89][0-9a-f]/i;
const ENCODED_UNRESERVED = /%([0-9a-f]{2})/gi;

function forbidden(value: string): boolean {
    return FORBIDDEN_ESCAPES.test(value) || [...value].some((character) => {
        const code = character.charCodeAt(0);
        return character === "\\" || code <= 31 || (code >= 127 && code <= 159);
    });
}

/** Validates and normalizes a relative authentication destination before browser URL parsing. */
export function authReturnPath(candidate: unknown): string | null {
    if (typeof candidate !== "string" || !candidate.startsWith("/") || candidate.startsWith("//") || forbidden(candidate)
        || [...candidate].some((character) => ' "<>^`{|}[]'.includes(character))) return null;
    try {
        const decoded = decodeURIComponent(candidate);
        if (!decoded.startsWith("/") || decoded.startsWith("//") || forbidden(decoded)) return null;
        const normalized = candidate.replace(ENCODED_UNRESERVED, (escape, hex: string) => {
            const character = String.fromCharCode(Number.parseInt(hex, 16));
            return /[a-z0-9._~-]/i.test(character) ? character : escape;
        });
        const base = "https://connex.invalid";
        const url = new URL(normalized, base);
        if (url.origin !== base || !url.pathname.startsWith("/") || url.pathname.startsWith("//")) return null;
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
