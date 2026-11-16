import { expect, type Page } from "@playwright/test";

/**
 * Next publishes its app-router instance for debugging. Reaching it lets a test perform the refresh
 * that sits behind an error boundary's retry: it supplies no URL, so it re-publishes whatever
 * canonical URL the router still holds. Its `push` is the same soft navigation a `<Link>` performs,
 * which lets a test leave a page whose modal drawer would otherwise cover the link it would click.
 */
declare global {
    interface Window {
        next?: { router?: { refresh: () => void; push: (href: string) => void } };
    }
}

/**
 * Soft-navigates through the app router, as a `<Link>` click would, and waits for the address bar to
 * reach the target path.
 *
 * @param page the page to navigate
 * @param href the in-app destination, a path below the origin
 */
export async function navigateThroughRouter(page: Page, href: string) {
    expect(await page.evaluate(() => typeof window.next?.router?.push === "function")).toBe(true);
    await page.evaluate((target) => {
        window.next?.router?.push(target);
    }, href);
    const target = new URL(href, "http://router.invalid").pathname;
    await expect(page).toHaveURL((url) => url.pathname === target);
}

/**
 * Marks the current history entry, refreshes through the router, and waits for the commit that
 * drops the mark.
 *
 * A refresh supplies no URL of its own, so it keeps the router's canonical URL and `HistoryUpdater`
 * writes that URL back to the address bar and the history entry. It also commits with
 * `preserveCustomHistoryState` off, which discards the mark — the signal that the write has landed,
 * so the assertions that follow cannot pass by running ahead of it.
 *
 * Marking is only inert because the entry carries `__NA`: Next's patched `replaceState` hands a
 * state that has it straight to the native method. Without it the mark would dispatch a restore of
 * its own and repair the canonical URL these tests are about, so the precondition is asserted
 * rather than assumed.
 */
export async function refreshThroughRouter(page: Page) {
    expect(await page.evaluate(() => typeof window.next?.router?.refresh === "function")).toBe(true);
    expect(await page.evaluate(() => {
        const state: unknown = window.history.state;
        return state !== null && typeof state === "object" && "__NA" in state;
    })).toBe(true);
    await page.evaluate(() => {
        const state: unknown = window.history.state;
        const marked = state !== null && typeof state === "object"
            ? { ...state, refreshProbe: true }
            : { refreshProbe: true };
        window.history.replaceState(marked, "", window.location.href);
    });

    expect(await page.evaluate(() => {
        const state: unknown = window.history.state;
        return state !== null && typeof state === "object" && "__NA" in state && "refreshProbe" in state;
    })).toBe(true);

    await page.evaluate(() => {
        window.next?.router?.refresh();
    });

    await page.waitForFunction(() => {
        const state: unknown = window.history.state;
        return state === null || typeof state !== "object" || !("refreshProbe" in state);
    });
}
