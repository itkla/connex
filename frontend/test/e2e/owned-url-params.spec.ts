import type { APIRequestContext, Page, TestInfo } from "@playwright/test";

import { csrfBootstrap, seeder, type RunFixture } from "./support/api";
import { deleteAttachment, uploadContactAttachment } from "./support/attachments";
import { expect, test } from "./support/cleanup";
import { runFixture } from "./support/fixtures";
import { message } from "./support/messages";
import {
    captureUncaughtErrors,
    expectBoundedWrite,
    expectNoSegmentError,
    expectStateSurvivesHistoryTraversal,
    expectStateSurvivesReload,
    expectStateSurvivesRouterRefresh,
    expectUrlState,
    recordRscRequests,
    type UrlState,
} from "./support/owned-url";

test.describe.configure({ timeout: 120_000 });

/** The Radar families, in the order the board lists them. */
const RADAR_FAMILIES = ["relationship_decay", "deal_risk", "warm_path"] as const;

/** Builds the address an owned-param browser should hold. */
function at(pathname: string, params: Record<string, string | null>): UrlState {
    return { pathname, params };
}

function isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === "object" && value !== null;
}

function escapeRegExp(value: string): string {
    return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

/** A short, URL-safe token unique to this test attempt, used to name what it seeds. */
function runToken(testInfo: TestInfo): string {
    return `${Date.now().toString(36)}${testInfo.retry}`;
}

/**
 * Makes Radar render its filters in a tenant that has no signals. The board hides its filters when
 * the feed is empty, which a freshly provisioned e2e tenant always is, but shows them, over an honest
 * "some signals couldn't be refreshed" notice, when one family is unavailable. Only the client's own
 * refresh of the feed is rewritten; the server render and every other request stay real.
 */
async function showRadarFilters(page: Page) {
    await page.route((url) => url.pathname === "/api/radar", async (route) => {
        if (route.request().method() !== "GET") {
            await route.continue();
            return;
        }
        const response = await route.fetch();
        const payload: unknown = response.ok() ? await response.json() : null;
        if (!isRecord(payload)) {
            await route.fulfill({ response });
            return;
        }
        const known = Array.isArray(payload.families) ? payload.families.filter(isRecord) : [];
        const families = RADAR_FAMILIES.map((family) => ({
            ...(known.find((state) => state.family === family) ?? {}),
            family,
            status: family === "warm_path" ? "unavailable" : "available",
        }));
        await route.fulfill({ response, json: { ...payload, families } });
    });
}

/** Reads the id of a pipeline the setup project seeded, by its name. */
async function pipelineIdByName(api: APIRequestContext, fixture: RunFixture, name: string): Promise<number> {
    const response = await api.get("/api/pipelines", {
        timeout: 120_000,
        headers: { "X-Workspace-Id": String(fixture.workspaceId) },
    });
    expect(response.status()).toBe(200);
    const pipelines: unknown = await response.json();
    const found: unknown = Array.isArray(pipelines)
        ? pipelines.find((pipeline: unknown) => isRecord(pipeline) && pipeline.name === name)
        : undefined;
    if (!isRecord(found) || typeof found.id !== "number") {
        throw new Error(`The seeded pipeline "${name}" is missing from the e2e tenant`);
    }
    return found.id;
}

/** Creates an open task assigned to the signed-in user, so it lands in the default "My open" queue. */
async function createTask(api: APIRequestContext, fixture: RunFixture, description: string): Promise<number> {
    const me = await api.get("/api/auth/me", { timeout: 120_000 });
    expect(me.status()).toBe(200);
    const user: unknown = await me.json();
    if (!isRecord(user) || typeof user.id !== "number") {
        throw new Error("The current-user response carries no numeric id");
    }
    const task = await seeder(api, fixture.workspaceId, await csrfBootstrap(api)).post("/api/tasks", {
        description,
        completed: false,
        assignedToId: user.id,
        personId: fixture.contacts.watch.id,
    });
    if (typeof task.id !== "number") {
        throw new Error("The created-task response carries no numeric id");
    }
    return task.id;
}

/** Deletes a task this spec created. */
async function deleteTask(api: APIRequestContext, fixture: RunFixture, taskId: number): Promise<void> {
    const csrf = await csrfBootstrap(api);
    const response = await api.delete(`/api/tasks/${taskId}`, {
        timeout: 120_000,
        headers: {
            "X-Workspace-Id": String(fixture.workspaceId),
            [csrf.headerName]: csrf.token,
        },
    });
    expect(response.ok(), `DELETE /api/tasks/${taskId}: ${response.status()}`).toBe(true);
}

/**
 * The authenticated real-browser gate #1781 set for letting the router hear owned-param writes.
 *
 * Every browser that writes its own params through `writeOwnedParamsToUrl` — directly, as the
 * pipelines browser does, or through `useOwnedUrlParams` — now hands Next's history patch a state it
 * does not short-circuit on, so each write dispatches an `ACTION_RESTORE` where it used to dispatch
 * nothing. That is what keeps the router's canonical URL, and so `useSearchParams` and the refresh
 * behind an app-shell boundary's "Try again", in step with the address bar. Each test drives one of
 * the seven browsers through a deep link with an owned param, its owned-param writes, its peek drawer
 * where it has one, a router refresh, a reload and Back/Forward, and holds every step to three
 * things: the owned params survive, no write drives a router fetch or leaves the router fetching,
 * and no segment falls back to its error boundary.
 *
 * Every router refresh starts from an address the router could only know by hearing a write: one
 * whose owned params differ from the deep link the document loaded at, which is where Next seeded
 * the router's canonical URL. A router that missed the write would re-fetch the deep link and put it
 * back in the address bar, so the refresh fails exactly when a browser's writes go unheard again;
 * `expectStateSurvivesRouterRefresh` refuses to run from the loaded address.
 */
test.describe("owned URL params (#1781)", () => {
    test.afterEach(async ({ page }) => {
        await page.unrouteAll({ behavior: "ignoreErrors" });
    });

    test("analytics keeps its range and grain through writes, refresh, reload and history", async ({ page }) => {
        const rsc = recordRscRequests(page);
        const uncaught = captureUncaughtErrors(page);
        const label = (key: string) => message("en", "analytics", `AnalyticsPage.${key}`);
        const path = "/insights/analytics";
        const range = page.getByRole("group", { name: label("rangeLabel"), exact: true });
        const grain = page.getByRole("group", { name: label("granularityLabel"), exact: true });
        const daily = at(path, { range: "30d", granularity: "day" });
        const weekly = at(path, { range: "30d", granularity: null });
        const verifyGrain = (grainKey: "granularityDay" | "granularityWeek") => async () => {
            await expect(range.getByRole("button", { name: label("range30d"), exact: true }))
                .toHaveAttribute("aria-pressed", "true");
            await expect(grain.getByRole("button", { name: label(grainKey), exact: true }))
                .toHaveAttribute("aria-pressed", "true");
        };
        const verifyDaily = verifyGrain("granularityDay");
        const verifyWeekly = verifyGrain("granularityWeek");

        await page.goto(`${path}?range=30d&granularity=day`);
        await expectUrlState(page, daily, "the deep link");
        await verifyDaily();

        await expectBoundedWrite(page, rsc, "choosing the range's default grain", weekly, () =>
            grain.getByRole("button", { name: label("granularityWeek"), exact: true }).click());
        await expectStateSurvivesRouterRefresh(page, rsc, weekly, verifyWeekly);

        await expectBoundedWrite(page, rsc, "choosing the day grain", daily, () =>
            grain.getByRole("button", { name: label("granularityDay"), exact: true }).click());
        await expectStateSurvivesReload(page, daily, verifyDaily);
        await expectStateSurvivesHistoryTraversal(page, daily, verifyDaily);
        await expectNoSegmentError(page, uncaught);
    });

    test("radar keeps its family and state filters through writes, refresh, reload and history", async ({ page }) => {
        await showRadarFilters(page);
        const rsc = recordRscRequests(page);
        const uncaught = captureUncaughtErrors(page);
        const label = (key: string) => message("en", "radar", `Radar.${key}`);
        const path = "/intelligence/radar";
        const families = page.getByRole("group", { name: label("filters.familyLabel"), exact: true });
        const dealRisk = at(path, { family: "deal_risk", state: "all" });
        const everyFamily = at(path, { family: null, state: "all" });
        const verifyFamily = (familyKey: "family.deal_risk" | "filters.allFamilies") => async () => {
            await expect(families.getByRole("button", { name: label(familyKey), exact: true }))
                .toHaveAttribute("aria-pressed", "true");
        };
        const verifyDealRisk = verifyFamily("family.deal_risk");
        const verifyEveryFamily = verifyFamily("filters.allFamilies");

        await page.goto(`${path}?family=deal_risk&state=all`);
        await expectUrlState(page, dealRisk, "the deep link");
        await verifyDealRisk();

        await expectBoundedWrite(page, rsc, "showing every family", everyFamily, () =>
            families.getByRole("button", { name: label("filters.allFamilies"), exact: true }).click());
        await expectStateSurvivesRouterRefresh(page, rsc, everyFamily, verifyEveryFamily);

        await expectBoundedWrite(page, rsc, "showing deal risk", dealRisk, () =>
            families.getByRole("button", { name: label("family.deal_risk"), exact: true }).click());
        await expectStateSurvivesReload(page, dealRisk, verifyDealRisk);
        await expectStateSurvivesHistoryTraversal(page, dealRisk, verifyDealRisk);
        await expectNoSegmentError(page, uncaught);
    });

    test("the documents library keeps its view through writes, refresh, reload and history", async ({ page }) => {
        const rsc = recordRscRequests(page);
        const uncaught = captureUncaughtErrors(page);
        const label = (key: string) => message("en", "document-templates", `DocumentsLibrary.${key}`);
        const path = "/library/documents";
        const view = page.getByRole("group", { name: label("viewSwitchAria"), exact: true });
        const templates = at(path, { list: "templates" });
        const generated = at(path, { list: null });
        const verifyView = (viewKey: "viewDocuments" | "viewTemplates") => async () => {
            await expect(view.getByRole("button", { name: label(viewKey), exact: true }))
                .toHaveAttribute("aria-pressed", "true");
        };
        const verifyTemplates = verifyView("viewTemplates");
        const verifyGenerated = verifyView("viewDocuments");

        await page.goto(`${path}?list=templates`);
        await expectUrlState(page, templates, "the deep link");
        await verifyTemplates();

        await expectBoundedWrite(page, rsc, "switching to generated documents", generated, () =>
            view.getByRole("button", { name: label("viewDocuments"), exact: true }).click());
        await expectStateSurvivesRouterRefresh(page, rsc, generated, verifyGenerated);

        await expectBoundedWrite(page, rsc, "switching to templates", templates, () =>
            view.getByRole("button", { name: label("viewTemplates"), exact: true }).click());
        await expectStateSurvivesReload(page, templates, verifyTemplates);
        await expectStateSurvivesHistoryTraversal(page, templates, verifyTemplates);
        await expectNoSegmentError(page, uncaught);
    });

    test("the files library keeps its search, sort and open file through writes, refresh, reload and history", async ({ page, cleanup }, testInfo) => {
        const fixture = runFixture(testInfo.project.name);
        const token = runToken(testInfo);
        const fileName = `owned-url-${token}.png`;
        const fileId = await uploadContactAttachment(page.request, fixture, fixture.contacts.watch.id, fileName);
        cleanup.add((api) => deleteAttachment(api, fixture, fileId));
        const rsc = recordRscRequests(page);
        const uncaught = captureUncaughtErrors(page);
        const label = (key: string) => message("en", "attachments", `LibraryFiles.${key}`);
        const path = "/library/files";
        const detail = page.getByRole("dialog", { name: fileName, exact: true });
        const card = page.locator("[data-app-main]").getByText(fileName, { exact: true }).first();
        const search = page.getByRole("textbox", { name: label("searchPlaceholder"), exact: true });
        const sortBy = async (option: string) => {
            await page.getByRole("button", { name: label("sortLabel"), exact: true }).click();
            await page.getByRole("menuitemradio", { name: option, exact: true }).click();
        };
        const closeDetail = () => detail.getByRole("button", { name: "Close", exact: true }).click();
        const opened = at(path, { q: token, sort: "name", file: String(fileId) });
        const sorted = at(path, { q: token, sort: "name", file: null });
        const verifySorted = async () => {
            await expect(search).toHaveValue(token);
            await expect(card).toBeVisible();
            await expect(detail).toBeHidden();
            await page.getByRole("button", { name: label("sortLabel"), exact: true }).click();
            await expect(page.getByRole("menuitemradio", { name: label("sortName"), exact: true }))
                .toHaveAttribute("aria-checked", "true");
            await page.keyboard.press("Escape");
        };

        await page.goto(`${path}?q=${token}&sort=name&file=${fileId}`);
        await expectUrlState(page, opened, "the deep link");
        await expect(detail).toBeVisible();

        await expectBoundedWrite(page, rsc, "closing the deep-linked file", sorted, closeDetail);
        await expect(detail).toBeHidden();
        await expectBoundedWrite(page, rsc, "sorting newest first", at(path, { q: token, sort: null, file: null }), () =>
            sortBy(label("sortNewest")));
        await expectBoundedWrite(page, rsc, "sorting by name", sorted, () => sortBy(label("sortName")));
        await expectBoundedWrite(page, rsc, "opening the file from the library", opened, () => card.click());
        await expect(detail).toBeVisible();
        await expectBoundedWrite(page, rsc, "closing the file", sorted, closeDetail);

        await expectStateSurvivesRouterRefresh(page, rsc, sorted, verifySorted);
        await expectStateSurvivesReload(page, sorted, verifySorted);
        await expectStateSurvivesHistoryTraversal(page, sorted, verifySorted);
        await expectNoSegmentError(page, uncaught);
    });

    test("the activities timeline keeps its open activity through writes, refresh, reload and history", async ({ page }, testInfo) => {
        const activity = runFixture(testInfo.project.name).activities.evidence;
        const rsc = recordRscRequests(page);
        const uncaught = captureUncaughtErrors(page);
        const label = (key: string) => message("en", "activity", `ActivityEditActivitySheet.${key}`);
        const path = "/activity/all";
        const sheet = page.getByRole("dialog", { name: label("title"), exact: true });
        const row = page.getByRole("button", { name: new RegExp(`^${escapeRegExp(activity.name)}`) }).first();
        const closeSheet = () => sheet.getByRole("button", { name: label("cancel"), exact: true }).click();
        const opened = at(path, { activity: String(activity.id) });
        const closed = at(path, { activity: null });
        const verifyOpened = async () => {
            await expect(sheet).toBeVisible();
        };
        const verifyClosed = async () => {
            await expect(row).toBeVisible();
            await expect(sheet, "the cleared activity deep link reopened the sheet").toBeHidden();
        };

        await page.goto(`${path}?activity=${activity.id}`);
        await expectUrlState(page, opened, "the deep link");
        await verifyOpened();

        await expectBoundedWrite(page, rsc, "closing the deep-linked activity", closed, closeSheet);
        await verifyClosed();
        await expectStateSurvivesRouterRefresh(page, rsc, closed, verifyClosed);

        await expectBoundedWrite(page, rsc, "opening the activity from the timeline", opened, () => row.click());
        await verifyOpened();
        await expectStateSurvivesReload(page, opened, verifyOpened);
        await expectStateSurvivesHistoryTraversal(page, opened, verifyOpened);
        await expectBoundedWrite(page, rsc, "closing the restored activity", closed, closeSheet);
        await expect(sheet).toBeHidden();
        await expectNoSegmentError(page, uncaught);
    });

    test("the tasks list keeps its open task through writes, refresh, reload and history", async ({ page, cleanup }, testInfo) => {
        const fixture = runFixture(testInfo.project.name);
        const description = `Owned URL task ${runToken(testInfo)}`;
        const taskId = await createTask(page.request, fixture, description);
        cleanup.add((api) => deleteTask(api, fixture, taskId));
        const rsc = recordRscRequests(page);
        const uncaught = captureUncaughtErrors(page);
        const label = (key: string) => message("en", "activity", key);
        const path = "/activity/tasks";
        const sheet = page.getByRole("dialog", { name: label("ActivityEditTaskSheet.title"), exact: true });
        const row = page.getByRole("button", {
            name: label("ActivityTasks.ariaOpenTask").replace("{name}", description),
            exact: true,
        }).first();
        const closeSheet = () => sheet.getByRole("button", { name: label("ActivityEditTaskSheet.cancel"), exact: true }).click();
        const opened = at(path, { task: String(taskId) });
        const closed = at(path, { task: null });
        const verifyOpened = async () => {
            await expect(sheet).toBeVisible();
        };
        const verifyClosed = async () => {
            await expect(row).toBeVisible();
            await expect(sheet, "the cleared task deep link reopened the sheet").toBeHidden();
        };

        await page.goto(`${path}?task=${taskId}`);
        await expectUrlState(page, opened, "the deep link");
        await verifyOpened();

        await expectBoundedWrite(page, rsc, "closing the deep-linked task", closed, closeSheet);
        await verifyClosed();
        await expectStateSurvivesRouterRefresh(page, rsc, closed, verifyClosed);

        await expectBoundedWrite(page, rsc, "opening the task from its row", opened, () => row.click());
        await verifyOpened();
        await expectStateSurvivesReload(page, opened, verifyOpened);
        await expectStateSurvivesHistoryTraversal(page, opened, verifyOpened);
        await expectBoundedWrite(page, rsc, "closing the restored task", closed, closeSheet);
        await expect(sheet).toBeHidden();
        await expectNoSegmentError(page, uncaught);
    });

    test("the pipelines browser clears its edit deep link for good", async ({ page }, testInfo) => {
        const fixture = runFixture(testInfo.project.name);
        const pipelineId = await pipelineIdByName(page.request, fixture, "E2E Pipeline");
        const rsc = recordRscRequests(page);
        const uncaught = captureUncaughtErrors(page);
        const label = (key: string) => message("en", "pipelines", `PipelinesQuickEditSheet.${key}`);
        const path = "/records/pipelines";
        const heading = page.getByRole("heading", {
            level: 1,
            name: message("en", "pipelines", "PipelinesBrowser.title"),
            exact: true,
        });
        const sheet = page.getByRole("dialog", { name: label("titleSingle"), exact: true });
        const editing = at(path, { edit: String(pipelineId) });
        const closed = at(path, { edit: null });
        const verifyEditing = async () => {
            await expect(sheet).toBeVisible();
        };
        const verifyClosed = async () => {
            await expect(heading).toBeVisible();
            await expect(sheet, "the cleared edit deep link reopened the sheet").toBeHidden();
        };

        await page.goto(`${path}?edit=${pipelineId}`);
        await expectUrlState(page, editing, "the deep link");
        await verifyEditing();
        await expectStateSurvivesReload(page, editing, verifyEditing);

        await expectBoundedWrite(page, rsc, "closing the deep-linked pipeline", closed, () =>
            sheet.getByRole("button", { name: label("cancel"), exact: true }).click());
        await expect(sheet).toBeHidden();

        await expectStateSurvivesRouterRefresh(page, rsc, closed, verifyClosed);
        await expectStateSurvivesReload(page, closed, verifyClosed);
        await expectStateSurvivesHistoryTraversal(page, closed, verifyClosed);
        await expectNoSegmentError(page, uncaught);
    });
});
