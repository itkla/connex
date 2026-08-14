/**
 * Reads the one-time bearer from the URL fragment and removes the entire non-canonical URL before
 * any subsequent network request or navigation can expose it. Fragments never reach the server on
 * initial navigation; the returned value must be sent only to the matching exchange endpoint body.
 */
export function takeOneTimeLinkToken(): string | null {
    if (typeof window === "undefined") {
        return null;
    }
    const token = new URLSearchParams(window.location.hash.slice(1)).get("token");
    if (window.location.hash || window.location.search) {
        window.history.replaceState(window.history.state, "", window.location.pathname);
    }
    return token?.trim() || null;
}
