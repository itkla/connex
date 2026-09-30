import { expect, type Page } from "@playwright/test";

/**
 * Next publishes its app-router instance for debugging. Reaching it lets a test perform the refresh
 * that sits behind an error boundary's retry: it supplies no URL, so it re-publishes whatever
 * canonical URL the router still holds.
 */
declare global {
    interface Window {
        next?: { router?: { refresh: () => void } };
    }
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

