import { expect, test } from "@playwright/test";

import { refreshThroughRouter } from "./support/router-refresh";

test.use({ storageState: { cookies: [], origins: [] } });

/** A public app-router page with no one-time-link entry hook, so nothing else rewrites the URL. */
const PROBE_PAGE = "/privacy";

/**
 * Pins the Next 16 behaviour `writeOwnedParamsToUrl` depends on (#1781).
 *
 * A shallow list-state write that hands the patch back Next's own `history.state` is short-circuited
 * to the native method, so the router never hears it and keeps a canonical URL without the param;
 * the next router action — the refresh behind an app-shell boundary's "Try again" — then writes that
 * stale URL over the address bar and the deep link is gone from the history entry and from any later
 * reload. Stripping `__NA` from a copy of the state lets the patch dispatch its restore instead, and
 * it puts `__NA` and the internals tree back before the native write, so the entry stays app-router
 * owned and whatever custom marker the app stamped on it survives.
 *
 * `test/unit/listStateUrl.test.ts` pins which of these two shapes the writer passes; this spec
 * measures, in a real browser against the installed Next, what each shape actually does.
 */
test("a shallow URL write only survives a router refresh when the router heard it", async ({ page }) => {
    await page.goto(PROBE_PAGE);
    await page.waitForFunction(() => {
        const state: unknown = window.history.state;
        return typeof window.next?.router?.refresh === "function"
            && state !== null
            && typeof state === "object"
            && "__NA" in state;
    });

    await page.evaluate((path) => {
        window.history.replaceState(window.history.state, "", `${path}?probe=passthrough`);
    }, PROBE_PAGE);
    await expect(page).toHaveURL(/\?probe=passthrough$/);

    await refreshThroughRouter(page);

    await expect(
        page,
        "a state carrying __NA skips the restore, so the refresh republished the stale canonical URL",
    ).toHaveURL((url) => url.pathname === PROBE_PAGE && url.search === "");

    await page.evaluate((path) => {
        const state: unknown = window.history.state;
        const custom: Record<string, unknown> = state !== null && typeof state === "object" ? { ...state } : {};
        delete custom.__NA;
        custom.listStateProbe = "kept";
        window.history.replaceState(custom, "", `${path}?probe=restored`);
    }, PROBE_PAGE);
    await expect(page).toHaveURL(/\?probe=restored$/);
    expect(
        await page.evaluate(() => {
            const state: unknown = window.history.state;
            return state !== null
                && typeof state === "object"
                && "__NA" in state
                && JSON.stringify(state).includes('"listStateProbe":"kept"');
        }),
        "the patch must put __NA back — a later back navigation cannot restore an entry without it — and keep the writer's own marker",
    ).toBe(true);

    await refreshThroughRouter(page);

    await expect(
        page,
        "a state without __NA must dispatch the restore, so the refresh keeps the written URL",
    ).toHaveURL((url) => url.pathname === PROBE_PAGE && url.search === "?probe=restored");
});
