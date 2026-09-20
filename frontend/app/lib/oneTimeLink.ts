let syncOwed = false;

/** The document's current URL below the origin, the form `syncStrippedUrlWithRouter` replays. */
function currentUrl(): string {
    return `${window.location.pathname}${window.location.search}${window.location.hash}`;
}

/** The one-time bearer the address bar carries in its fragment right now, if any. */
function fragmentBearer(): string | null {
    const token = new URLSearchParams(window.location.hash.slice(1)).get("token");
    return token?.trim() || null;
}

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
    const token = fragmentBearer();
    if (window.location.hash || window.location.search) {
        window.history.replaceState(window.history.state, "", window.location.pathname);
        syncOwed = true;
    }
    return token;
}

/**
 * Hands the app router the bearer-free URL the document actually holds, through Next's patched
 * `history.replaceState`, so the router adopts it as its canonical URL.
 *
 * The router seeds that canonical URL from `location.href`, bearer included, and the strip above
 * cannot correct it: passing Next's own state, which carries `__NA`, makes the patch hand the call
 * straight to the native method without dispatching its restore. The router then keeps the
 * pre-strip URL and re-publishes it to the address bar and the current history entry on the next
 * action that reuses it, such as the refresh behind an error boundary's retry. Passing `null`
 * instead makes the patch copy `__NA` and the internals tree onto a fresh state and dispatch the
 * restore, so the router adopts the bearer-free URL and the history entry stays app-router owned.
 *
 * What gets replayed is the document's current URL rather than the one the strip wrote, because a
 * restore carries a URL with it: replaying a recorded URL that the document has since moved past
 * would drag the router back to it, while the URL the address bar holds at this instant cannot be
 * stale. The bearer only ever arrives in the fragment, so any fragment-free current URL is safe to
 * publish, whatever else wrote to the address bar between the strip and this call.
 *
 * A bearer sitting in the fragment at this point is a second emailed link that landed after the
 * strip, which `useOneTimeLinkEntry` answers with a reload. Publishing a fragment-free URL now would
 * take that bearer out of the address bar before the re-entered page could read it, so the sync
 * stands down and stays owed rather than spent: a later scheduled run can still make the correction,
 * and the reload re-runs the strip from the top. The flag likewise clears only once the write has
 * returned, so a refused `replaceState` leaves the correction owed too.
 *
 * Only Next's patch may receive this call. The native method would write the `null` through and
 * drop `__NA`, which leaves a history entry a later back navigation cannot restore, and would not
 * correct the canonical URL either; callers must run it no earlier than a task scheduled from a
 * mount effect, by which point `AppRouter`'s own mount effect has installed the patch.
 * `useOneTimeLinkEntry` is the only supported caller.
 */
export function syncStrippedUrlWithRouter(): void {
    if (typeof window === "undefined" || !syncOwed) {
        return;
    }
    if (fragmentBearer() !== null) {
        return;
    }
    window.history.replaceState(null, "", currentUrl());
    syncOwed = false;
}
