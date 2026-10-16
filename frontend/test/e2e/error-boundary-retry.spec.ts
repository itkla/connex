import type { Request, Response } from "@playwright/test";

import { deleteAttachment, uploadContactAttachment } from "./support/attachments";
import { expect, test } from "./support/cleanup";
import { runFixture } from "./support/fixtures";
import { message } from "./support/messages";
import {
    captureUncaughtErrors,
    expectNoSegmentError,
    expectUrlState,
    isRscPrefetch,
    isRscRequest,
    recordRscRequests,
    urlStateOf,
    waitForRscQuiet,
    type UrlState,
} from "./support/owned-url";

const FILES_PATH = "/library/files";
const ATTACHMENTS_PAGE_PATH = "/api/attachments/page";

/**
 * The most non-prefetch RSC requests one "Try again" may cause: the refresh's own re-fetch, plus
 * one for an unrelated router action that lands in the same window. A retry that loops exceeds it.
 */
const MAX_RECOVERY_REFETCHES = 2;

/** Matches the Files library's page request for the given search and sort. */
function isAttachmentsPage(response: Response, q: string, sort: string | null): boolean {
    const url = new URL(response.url());
    return url.pathname === ATTACHMENTS_PAGE_PATH
        && url.searchParams.get("q") === q
        && url.searchParams.get("sort") === (sort ?? "newest");
}

/** Matches the router's re-fetch of the Files library segment, as opposed to a prefetch of it. */
function isFilesRefetch(request: Request): boolean {
    return isRscRequest(request)
        && !isRscPrefetch(request)
        && new URL(request.url()).pathname === FILES_PATH;
}

/**
 * The real-browser gate #1781 set for app-shell boundaries recovering with Next's `retry`.
 *
 * Before #1781 every boundary read `unstable_retry`, a prop the installed Next no longer passes, so
 * "Try again" fell back to `reset`: it re-rendered the failed segment from the client state it
 * already held and never re-fetched it. `retry` refreshes the router and resets the boundary in one
 * transition. The refresh re-fetches the canonical URL the router holds and writes it back to the
 * address bar, so on a page holding a shallow URL write it is only safe because the owned-param
 * writer now lets the router hear that write; a router that had not heard it would re-fetch the
 * address from before the write and erase the param.
 *
 * The segment error is forced from the outside with no production test hook: the Files library's
 * page request is answered once with a body whose `items` is `null`, which breaks the `Page<T>`
 * contract the client trusts by type, so `FilesBrowser` throws while rendering and the `library`
 * segment's `error.tsx` takes over. That is a render-phase throw inside the `(app)` shell, the case
 * a segment boundary exists for, raised by the very sort change whose URL write the page then holds.
 * If `FilesBrowser` ever starts validating the page shape and stops throwing, the first assertion
 * below fails and says so; pick another client data request whose malformed answer reaches render.
 */
test.describe("app-shell error boundary retry (#1781)", () => {
    test("Try again re-fetches the failed segment without losing the page's shallow URL writes", async ({ page, cleanup }, testInfo) => {
        test.setTimeout(120_000);
        const fixture = runFixture(testInfo.project.name);
        const token = `${Date.now().toString(36)}${testInfo.retry}`;
        const fileName = `retry-probe-${token}.png`;
        const fileId = await uploadContactAttachment(page.request, fixture, fixture.contacts.watch.id, fileName);
        cleanup.add((api) => deleteAttachment(api, fixture, fileId));
        const rsc = recordRscRequests(page);
        const uncaught = captureUncaughtErrors(page);
        const label = (key: string) => message("en", "attachments", `LibraryFiles.${key}`);
        const main = page.locator("[data-app-main]");
        const card = main.getByText(fileName, { exact: true }).first();
        const search = page.getByRole("textbox", { name: label("searchPlaceholder"), exact: true });
        const sortTrigger = page.getByRole("button", { name: label("sortLabel"), exact: true });
        const sortByName = page.getByRole("menuitemradio", { name: label("sortName"), exact: true });
        const boundary = main.getByRole("heading", { name: message("en", "errors", "ErrorState.title"), exact: true });
        const tryAgain = main.getByRole("button", { name: message("en", "errors", "ErrorState.retry"), exact: true });
        const searched: UrlState = { pathname: FILES_PATH, params: { q: token, sort: null } };
        const sorted: UrlState = { pathname: FILES_PATH, params: { q: token, sort: "name" } };

        await page.goto(FILES_PATH);
        await expect(card).toBeVisible();

        const filtered = page.waitForResponse((response) => isAttachmentsPage(response, token, null));
        await search.fill(token);
        await filtered;
        await expectUrlState(page, searched, "the search write");
        await expect(card).toBeVisible();
        await waitForRscQuiet(rsc, "after the search write");

        let faultServed = false;
        await page.route(
            (url) => url.pathname === ATTACHMENTS_PAGE_PATH,
            async (route) => {
                faultServed = true;
                await route.fulfill({ status: 200, json: { items: null, total: 1 } });
            },
            { times: 1 },
        );
        await sortTrigger.click();
        await sortByName.click();

        await expect(
            boundary,
            "the malformed page answer no longer makes FilesBrowser throw, so no segment boundary took over; the spec needs a new fault",
        ).toBeVisible();
        expect(faultServed).toBe(true);
        await expectUrlState(page, sorted, "the failed page");
        await waitForRscQuiet(rsc, "after the segment failed");

        const refetch = page.waitForRequest(isFilesRefetch);
        const reloadedPage = page.waitForResponse((response) => isAttachmentsPage(response, token, "name"));
        const first = rsc.requests.length;
        await tryAgain.click();

        const request = await refetch;
        expect(
            urlStateOf(request.url(), Object.keys(sorted.params)),
            "Try again re-fetched an address without the page's shallow URL writes",
        ).toEqual(sorted);
        await reloadedPage;

        await expect(boundary).toBeHidden();
        await expect(card).toBeVisible();
        await expectUrlState(page, sorted, "after Try again");
        await expect(search).toHaveValue(token);
        await sortTrigger.click();
        await expect(sortByName).toHaveAttribute("aria-checked", "true");
        await page.keyboard.press("Escape");

        await waitForRscQuiet(rsc, "after the segment recovered");
        const refetches = rsc.requests.slice(first).filter((entry) => !entry.prefetch);
        expect(
            refetches.length,
            `recovery re-fetches the segment once and then settles: ${refetches.map((entry) => entry.url).join(", ")}`,
        ).toBeLessThanOrEqual(MAX_RECOVERY_REFETCHES);
        await expectNoSegmentError(page, uncaught);
    });
});
