import { randomUUID } from "node:crypto";

import { expect, type Page, type Request } from "@playwright/test";

import { message } from "./messages";
import { navigateThroughRouter, refreshThroughRouter } from "./router-refresh";

/**
 * A marker a test stamps on the document before leaving a page. Finding it again after Back proves
 * the router restored the history entry in place; an entry that lost `__NA` makes Next's `popstate`
 * handler reload the document instead, which discards the marker.
 */
declare global {
    interface Window {
        ownedUrlDocumentProbe?: string;
    }
}

/** One RSC request the page started: a router navigation, restore or refresh, or a prefetch. */
export type RscRequest = {
    url: string;
    prefetch: boolean;
    startedAt: number;
    settled: boolean;
};

/**
 * What {@link recordRscRequests} has seen since it attached: every RSC request the page started,
 * oldest first, and the address of the document the page last loaded, which is the address Next
 * seeded the router's canonical URL from.
 */
export type RscLog = { readonly requests: RscRequest[]; documentUrl: string | null };

/** The part of an address an owned-param browser is responsible for: its path and the keys it owns. */
export type UrlState = {
    pathname: string;
    params: Readonly<Record<string, string | null>>;
};

/**
 * The most non-prefetch RSC requests one owned-param write may cause: none.
 *
 * The writer hands Next's history patch a state it does not short-circuit on, so every write
 * dispatches an `ACTION_RESTORE` (#1781). The restore replays the tree the current entry already
 * holds under the history-traversal freshness policy, so the router renders it from the cache it
 * has and fetches nothing; a CI trace of the analytics test recorded no router fetch across its
 * writes. Nothing else can fetch inside a write's window: {@link expectBoundedWrite} lets the page
 * settle first, the app refreshes the router only after a mutation, a retry or a locale change,
 * none of which a write step performs, and it has no server actions. So a restore that re-fetched
 * once per write fails the gate, as one that loops does.
 */
export const MAX_ROUTER_FETCHES_PER_WRITE = 0;

/** How long the page must start no RSC request, with none still in flight, to count as settled. */
const RSC_QUIET_MS = 750;

/** How long the page gets to settle before the router is reported as looping. */
const RSC_SETTLE_TIMEOUT_MS = 15_000;

/** The in-app page a history-traversal check leaves to and comes back from. */
const TRAVERSAL_DESTINATION = "/dashboard";

/**
 * Uncaught errors Chromium reports through `pageerror` that say nothing about the page under test.
 * A chart that resizes inside a `ResizeObserver` callback raises the first on a busy runner.
 */
const BENIGN_PAGE_ERRORS: readonly RegExp[] = [/ResizeObserver loop/i];

/** Reports whether a request is one Next's router issued for an RSC payload. */
export function isRscRequest(request: Request): boolean {
    return request.headers().rsc === "1";
}

/** Reports whether an RSC request is a prefetch rather than a navigation, restore or refresh. */
export function isRscPrefetch(request: Request): boolean {
    const headers = request.headers();
    return "next-router-prefetch" in headers || "next-router-segment-prefetch" in headers;
}

/**
 * Starts recording every RSC request the page issues, so a test can bound what one step caused and
 * tell a settled router from a looping one, along with the address of the document it last loaded.
 *
 * A request still pending when a new document finishes parsing counts as settled. A reload cuts off
 * whatever the previous document still had in flight, and a cut-off request is not guaranteed to
 * report finishing or failing; one that never did would read as in flight for good and fail the
 * next settle wait. Its document is gone, so it can no longer drive the router. Anything the new
 * document started before it finished parsing stops counting as in flight too; no bound counts
 * those requests, because each bound counts only what its own step started.
 *
 * @param page the page to watch
 * @returns the live log, appended to as requests start and marked as they finish or fail
 */
export function recordRscRequests(page: Page): RscLog {
    const log: RscLog = { requests: [], documentUrl: null };
    const { requests } = log;
    const pending = new Map<Request, RscRequest>();
    const settle = (request: Request) => {
        const entry = pending.get(request);
        if (entry === undefined) return;
        entry.settled = true;
        pending.delete(request);
    };
    page.on("response", (response) => {
        const request = response.request();
        if (request.isNavigationRequest() && request.frame() === page.mainFrame()) {
            log.documentUrl = response.url();
        }
    });
    page.on("domcontentloaded", () => {
        for (const request of [...pending.keys()]) settle(request);
    });
    page.on("request", (request) => {
        if (!isRscRequest(request)) return;
        const entry: RscRequest = {
            url: request.url(),
            prefetch: isRscPrefetch(request),
            startedAt: Date.now(),
            settled: false,
        };
        requests.push(entry);
        pending.set(request, entry);
    });
    page.on("requestfinished", settle);
    page.on("requestfailed", settle);
    return log;
}

/**
 * Collects the uncaught errors a page raises, so a test can assert it never broke outright.
 *
 * @param page the page to watch
 * @returns the live list of error messages
 */
export function captureUncaughtErrors(page: Page): string[] {
    const errors: string[] = [];
    page.on("pageerror", (error) => {
        if (BENIGN_PAGE_ERRORS.some((pattern) => pattern.test(error.message))) return;
        errors.push(error.message);
    });
    return errors;
}

/**
 * Reads an address down to the path and the named query keys, `null` for a key that is absent.
 *
 * @param href the address to read
 * @param keys the query keys to report
 * @returns the path and the value of each key
 */
export function urlStateOf(href: string, keys: readonly string[]): UrlState {
    const url = new URL(href);
    return {
        pathname: url.pathname,
        params: Object.fromEntries(keys.map((key) => [key, url.searchParams.get(key)])),
    };
}

/**
 * Waits until the address bar holds the expected path and owned params.
 *
 * @param page the page under test
 * @param expected the path, and each owned key's value or `null` for absent
 * @param context what the test just did, for the failure message
 */
export async function expectUrlState(page: Page, expected: UrlState, context: string) {
    const keys = Object.keys(expected.params);
    await expect
        .poll(() => urlStateOf(page.url(), keys), {
            message: `${context}: the address bar does not hold the owned URL params the page should`,
        })
        .toEqual(expected);
}

/**
 * Waits until the page has started no RSC request for {@link RSC_QUIET_MS} and has none in flight.
 * A router that keeps fetching never gets there, which is how a restore or refresh loop shows up.
 * Until then each poll reads as the requests still in flight or the latest ones started, so a
 * timeout names what kept the page busy.
 *
 * @param rsc the page's RSC log
 * @param context what the test just did, for the failure message
 */
export async function waitForRscQuiet(rsc: RscLog, context: string) {
    const armedAt = Date.now();
    await expect
        .poll(
            () => {
                const inFlight = rsc.requests.filter((request) => !request.settled);
                if (inFlight.length > 0) {
                    return `in flight: ${inFlight.map((request) => request.url).join(", ")}`;
                }
                const last = rsc.requests.at(-1);
                if (Date.now() - Math.max(last?.startedAt ?? 0, armedAt) < RSC_QUIET_MS) {
                    const recent = rsc.requests
                        .filter((request) => request.startedAt >= armedAt)
                        .slice(-5)
                        .map((request) => request.url);
                    return `still starting: ${recent.join(", ") || "nothing yet"}`;
                }
                return "quiet";
            },
            {
                message: `${context}: RSC requests did not settle within ${RSC_SETTLE_TIMEOUT_MS} ms, so the router may be looping`,
                timeout: RSC_SETTLE_TIMEOUT_MS,
                intervals: [100],
            },
        )
        .toBe("quiet");
}

/**
 * Reads whether the current history entry still carries `__NA`, the marker Next's `popstate`
 * handler needs to restore the entry in place rather than reloading the document.
 */
async function entryIsRouterOwned(page: Page): Promise<boolean> {
    return page.evaluate(() => {
        const state: unknown = window.history.state;
        return state !== null && typeof state === "object" && "__NA" in state;
    });
}

/**
 * Performs one owned-param write and holds it to the #1781 contract: the address bar reaches the
 * expected params, the router settles, the write drives no non-prefetch RSC request
 * ({@link MAX_ROUTER_FETCHES_PER_WRITE}), and the entry stays one the router can restore.
 *
 * @param page the page under test
 * @param rsc the page's RSC log
 * @param context what the write is, for failure messages
 * @param expected the address the write must produce
 * @param perform the interaction that causes the write
 */
export async function expectBoundedWrite(
    page: Page,
    rsc: RscLog,
    context: string,
    expected: UrlState,
    perform: () => Promise<void>,
) {
    await waitForRscQuiet(rsc, `${context}, before the write`);
    const first = rsc.requests.length;
    await perform();
    await expectUrlState(page, expected, context);
    await waitForRscQuiet(rsc, context);
    const routerFetches = rsc.requests
        .slice(first)
        .filter((request) => !request.prefetch)
        .map((request) => request.url);
    expect(
        routerFetches.length,
        `${context}: one owned-param write drove ${routerFetches.length} router fetches: ${routerFetches.join(", ")}`,
    ).toBeLessThanOrEqual(MAX_ROUTER_FETCHES_PER_WRITE);
    expect(
        await entryIsRouterOwned(page),
        `${context}: the written history entry lost __NA, so Back to it would reload the page`,
    ).toBe(true);
}

/**
 * Refreshes through the router — the action behind an app-shell boundary's "Try again" — and checks
 * that it re-fetched the address the page holds and left that address in place. A router that never
 * heard an owned-param write still holds the address from before it, re-fetches that one, and writes
 * it back over the address bar.
 *
 * That only tells a heard write from an unheard one when the page holds an address the router could
 * have reached no other way. Next seeds the router's canonical URL from the address the document
 * loaded at, so a refresh from that address passes whether or not any write was heard. The check
 * therefore refuses to run when `expected` matches the loaded document's owned params: refresh after
 * a write that moved the page away from them.
 *
 * @param page the page under test
 * @param rsc the page's RSC log
 * @param expected the address the page holds and must keep
 * @param verify asserts the page's own state still matches the address
 */
export async function expectStateSurvivesRouterRefresh(
    page: Page,
    rsc: RscLog,
    expected: UrlState,
    verify: () => Promise<void>,
) {
    const keys = Object.keys(expected.params);
    const loaded = rsc.documentUrl;
    expect(loaded, "no document load was recorded before the router refresh").not.toBeNull();
    if (loaded !== null) {
        expect(
            urlStateOf(loaded, keys),
            "the router refresh starts from the address the document loaded at, which the router holds without hearing any write, so it cannot catch an unheard one",
        ).not.toEqual(expected);
    }
    await waitForRscQuiet(rsc, "before the router refresh");
    const first = rsc.requests.length;
    await refreshThroughRouter(page);
    await expectUrlState(page, expected, "after a router refresh");
    const refetch = rsc.requests.slice(first).find((request) => !request.prefetch);
    expect(refetch, "the router refresh issued no RSC request").toBeDefined();
    if (refetch !== undefined) {
        expect(
            urlStateOf(refetch.url, keys),
            "the router refresh re-fetched an address other than the one the owned-param write produced",
        ).toEqual(expected);
    }
    await waitForRscQuiet(rsc, "after the router refresh");
    await verify();
}

/**
 * Reloads the document and checks that the owned params, and the state they encode, come back.
 *
 * @param page the page under test
 * @param expected the address the page holds and must keep
 * @param verify asserts the page's own state matches the address
 */
export async function expectStateSurvivesReload(
    page: Page,
    expected: UrlState,
    verify: () => Promise<void>,
) {
    await page.reload();
    await expectUrlState(page, expected, "after a reload");
    await verify();
}

/**
 * Leaves the page with a soft navigation, then goes Back, Forward and Back again, checking that each
 * return lands on the same address in the same document with the page's state restored from it.
 *
 * @param page the page under test
 * @param expected the address the page holds and must keep
 * @param verify asserts the page's own state matches the address
 */
export async function expectStateSurvivesHistoryTraversal(
    page: Page,
    expected: UrlState,
    verify: () => Promise<void>,
) {
    expect(
        await entryIsRouterOwned(page),
        "the entry being left lost __NA, so Back to it would reload the page",
    ).toBe(true);
    const probe = randomUUID();
    await page.evaluate((value) => {
        window.ownedUrlDocumentProbe = value;
    }, probe);
    await navigateThroughRouter(page, TRAVERSAL_DESTINATION);

    for (const pass of ["first", "second"]) {
        await page.goBack({ waitUntil: "commit" });
        await expectUrlState(page, expected, `after the ${pass} Back`);
        expect(
            await page.evaluate(() => window.ownedUrlDocumentProbe),
            `the ${pass} Back reloaded the document instead of restoring the entry through the router`,
        ).toBe(probe);
        await verify();
        if (pass === "first") {
            await page.goForward({ waitUntil: "commit" });
            await expect(page).toHaveURL((url) => url.pathname === TRAVERSAL_DESTINATION);
        }
    }
}

/**
 * Checks that no route segment fell back to its error boundary and that the page raised no
 * uncaught error.
 *
 * @param page the page under test
 * @param uncaught the errors {@link captureUncaughtErrors} collected
 */
export async function expectNoSegmentError(page: Page, uncaught: readonly string[]) {
    await expect(
        page.getByRole("heading", { name: message("en", "errors", "ErrorState.title"), exact: true }),
        "a route segment fell back to its error boundary",
    ).toHaveCount(0);
    expect(uncaught, "the page raised an uncaught error").toEqual([]);
}
