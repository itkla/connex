let routerHoldsStrippedUrl = false;

/**
 * Reads the one-time bearer from the URL fragment and removes the entire non-canonical URL before
 * any subsequent network request or navigation can expose it. Fragments never reach the server on
 * initial navigation; the returned value must be sent only to the matching exchange endpoint body.
 * This cleanup requires hydration: with JavaScript disabled or hydration interrupted, the fragment
 * can remain in the address bar and local history, but the current link pages expose no third-party
 * navigation and browsers still exclude fragments from HTTP requests.
 *
 * The strip passes Next's own history state so the entry keeps `__NA`, which means the app router
 * never learns of it; `syncStrippedUrlWithRouter` closes that gap once the router can hear it.
 */
export function takeOneTimeLinkToken(): string | null {
    if (typeof window === "undefined") {
        return null;
    }
    const token = new URLSearchParams(window.location.hash.slice(1)).get("token");
    if (window.location.hash || window.location.search) {
        window.history.replaceState(window.history.state, "", window.location.pathname);
        routerHoldsStrippedUrl = true;
    }
    return token?.trim() || null;
}

/**
 * Replays the stripped URL through Next's patched `history.replaceState` so the app router adopts
 * it as its canonical URL.
 *
 * The router seeds that canonical URL from `location.href`, bearer included, and the strip above
 * cannot correct it: passing Next's own state, which carries `__NA`, makes the patch hand the call
 * straight to the native method without dispatching its restore. The router then keeps the
 * pre-strip URL and re-publishes it to the address bar and the current history entry on the next
 * action that reuses it, such as the refresh behind an error boundary's retry. Passing `null`
 * instead makes the patch copy `__NA` and the internals tree onto a fresh state and dispatch the
 * restore, so the router adopts the bearer-free URL and the history entry stays app-router owned.
 *
 * Only Next's patch may receive this call. The native method would write the `null` through and
 * drop `__NA`, which leaves a history entry a later back navigation cannot restore, and would not
 * correct the canonical URL either; callers must run it no earlier than a task scheduled from a
 * mount effect, by which point `AppRouter`'s own mount effect has installed the patch.
 * `useOneTimeLinkEntry` is the only supported caller.
 */
export function syncStrippedUrlWithRouter(): void {
    if (typeof window === "undefined" || !routerHoldsStrippedUrl) {
        return;
    }
    routerHoldsStrippedUrl = false;
    window.history.replaceState(null, "", window.location.pathname);
}
