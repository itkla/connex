"use client";

import { useEffect } from "react";

import { syncStrippedUrlWithRouter } from "@/app/lib/oneTimeLink";

/**
 * Prepares a component that reads a one-time-link `#token=` bearer on mount, covering the two ways
 * the strip in `takeOneTimeLinkToken` can be undone.
 *
 * A second emailed link that lands in a tab already on the same path is a fragment-only navigation:
 * the browser neither reloads the document nor remounts the route, so the new bearer would linger
 * in the address bar and history while the page kept the previous flow's state. Reloading re-enters
 * the page so the mount effect reads and strips the new bearer before any exchange request.
 * `replaceState` does not fire `hashchange`, so the strip cannot loop.
 *
 * The app router, meanwhile, seeds its canonical URL from `location.href` before any of this runs
 * and would re-publish the bearer from there. Handing it the stripped URL has to wait for the
 * `history` patch that `AppRouter` installs in its own mount effect, which React runs after every
 * descendant's. React flushes a commit's effects synchronously within one task, so a task scheduled
 * here runs after that patch is installed, and after the mount effect that strips, whichever
 * component owns it. Unmounting cancels the sync rather than leaving it to run without an entry.
 */
export function useOneTimeLinkEntry(): void {
    useEffect(() => {
        const reopen = () => window.location.reload();
        window.addEventListener("hashchange", reopen);
        const scheduledSync = window.setTimeout(syncStrippedUrlWithRouter);
        return () => {
            window.clearTimeout(scheduledSync);
            window.removeEventListener("hashchange", reopen);
        };
    }, []);
}
