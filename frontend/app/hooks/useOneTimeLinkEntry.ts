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
 * component owns it.
 *
 * Unmounting deliberately leaves that task scheduled. An entry that strips and then goes away
 * inside the same task — a throw caught by an error boundary, a `notFound()`, a Suspense teardown —
 * is exactly the case where the router would otherwise keep the pre-strip URL, bearer included, for
 * the life of the document, with nothing left to correct it. The sync replays only the URL the
 * strip wrote and only while the document still holds it, so a stale task cannot drag the router
 * back to a page the visitor has left.
 */
export function useOneTimeLinkEntry(): void {
    useEffect(() => {
        const reopen = () => window.location.reload();
        window.addEventListener("hashchange", reopen);
        window.setTimeout(syncStrippedUrlWithRouter);
        return () => {
            window.removeEventListener("hashchange", reopen);
        };
    }, []);
}
