"use client";

import { useEffect } from "react";

/**
 * Reloads the page whenever its fragment changes while it stays mounted.
 *
 * One-time-link entry pages read their `#token=` bearer once on mount and strip it with
 * `history.replaceState`. Opening a second emailed link in a tab already on the same path is a
 * fragment-only navigation: the browser neither reloads the document nor remounts the route, so the
 * new bearer would linger in the address bar and history while the page kept the previous flow's
 * state. Reloading re-enters the page so the mount effect reads and strips the new bearer before any
 * exchange request. `replaceState` does not fire `hashchange`, so the strip cannot loop.
 */
export function useReloadOnFragmentNavigation(): void {
    useEffect(() => {
        const reopen = () => window.location.reload();
        window.addEventListener("hashchange", reopen);
        return () => window.removeEventListener("hashchange", reopen);
    }, []);
}
