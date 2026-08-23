import { expect, test, type APIRequestContext, type Page } from "@playwright/test";

import { activeWorkspaceId, csrfBootstrap } from "./support/api";
import { runFixture } from "./support/fixtures";
import { useLocale } from "./support/locale";
import { message } from "./support/messages";

/**
 * Ask Connex copy, read from the shipped catalogue so an assertion tracks the product rather than a
 * literal pasted into this file.
 */
function copy(locale: "en" | "ja", key: string): string {
    return message(locale, "common", `AskConnex.${key}`);
}

/**
 * Creates a chat through the API the drawer itself uses.
 *
 * Session creation needs `AI_USE` and workspace membership but no configured AI provider — only
 * *asking* a question does — so a deep link, a rail row, and a shared header can all be exercised
 * against a stack that has no provider at all.
 *
 * @returns the new chat's id
 */
async function createChat(api: APIRequestContext, title: string): Promise<number> {
    const workspaceId = await activeWorkspaceId(api);
    const csrf = await csrfBootstrap(api);
    const response = await api.post("/api/ai/assistant/sessions", {
        timeout: 120_000,
        headers: {
            "X-Workspace-Id": String(workspaceId),
            [csrf.headerName]: csrf.token,
        },
        data: { title, autoTitle: false },
    });
    expect(response.status(), await response.text()).toBe(201);
    const body = (await response.json()) as { id: number };
    expect(body.id).toBeGreaterThan(0);
    return body.id;
}

/** The desktop pull tab that opens and closes the persistent panel. */
function pullTab(page: Page, locale: "en" | "ja" = "en") {
    return page.getByRole("button", { name: copy(locale, "title"), exact: true });
}

/** The persistent desktop panel itself. */
function panel(page: Page) {
    return page.locator("#ask-connex-desktop-panel");
}

async function openDrawer(page: Page, locale: "en" | "ja" = "en") {
    await pullTab(page, locale).click();
    await expect(panel(page)).toHaveAttribute("aria-hidden", "false");
}

test.describe("Ask Connex dual surface", () => {
    test("the workspace route is protected and returns the visitor after signing in", async ({ browser }, testInfo) => {
        const baseURL = testInfo.project.use.baseURL;
        if (typeof baseURL !== "string") throw new Error("The E2E project requires a base URL");
        const context = await browser.newContext({
            baseURL,
            locale: "en-US",
            timezoneId: "UTC",
            reducedMotion: "reduce",
            storageState: { cookies: [], origins: [] },
        });
        try {
            const page = await context.newPage();
            await page.goto("/ask-connex");
            await expect(page).toHaveURL(/\/auth\/login/);
            await page.goto("/ask-connex/12345");
            await expect(page).toHaveURL(/\/auth\/login/);
        } finally {
            await context.close();
        }
    });

    test("the pull tab opens and closes the panel without leaving the record", async ({ page }, testInfo) => {
        const contact = runFixture(testInfo.project.name).contacts.peek;
        await page.goto(`/records/contacts/${contact.id}`);
        await expect(page.getByRole("heading", { name: contact.name }).first()).toBeVisible();

        const drawer = panel(page);
        await expect(drawer).toHaveAttribute("aria-hidden", "true");

        await openDrawer(page);
        await expect(drawer.getByText(copy("en", "newChat"), { exact: true }).first()).toBeVisible();
        await expect(page).toHaveURL(new RegExp(`/records/contacts/${contact.id}`));
        await expect(page.getByRole("heading", { name: contact.name }).first()).toBeVisible();

        await drawer.getByRole("button", { name: copy("en", "close") }).click();
        await expect(drawer).toHaveAttribute("aria-hidden", "true");
        await expect(pullTab(page)).toBeFocused();
    });

    test("the header states visibility and the page context it will use", async ({ page }, testInfo) => {
        const contact = runFixture(testInfo.project.name).contacts.peek;
        await page.goto(`/records/contacts/${contact.id}`);
        await openDrawer(page);
        const drawer = panel(page);

        await expect(drawer.getByText(copy("en", "visibilityPrivate"), { exact: true })).toBeVisible();

        const context = drawer.getByRole("group", { name: copy("en", "context") });
        await expect(context).toBeVisible();
        await expect(context.getByText(contact.name, { exact: true })).toBeVisible();
        await expect(context.getByText(copy("en", "contextPage"), { exact: true })).toBeVisible();

        await context
            .getByRole("button", { name: copy("en", "removeContext").replace("{label}", contact.name) })
            .click();
        await expect(context.getByText(contact.name, { exact: true })).toBeHidden();

        await context.getByRole("button", { name: copy("en", "contextReset") }).click();
        await expect(context.getByText(contact.name, { exact: true })).toBeVisible();
    });

    test("page context can be kept across navigation and released again", async ({ page }, testInfo) => {
        const fixture = runFixture(testInfo.project.name);
        const contact = fixture.contacts.peek;
        await page.goto(`/records/contacts/${contact.id}`);
        await openDrawer(page);
        const context = panel(page).getByRole("group", { name: copy("en", "context") });

        const pin = context.getByRole("button", {
            name: copy("en", "pinContext").replace("{label}", contact.name),
        });
        await expect(pin).toHaveAttribute("aria-pressed", "false");
        await pin.click();
        await expect(context.getByRole("button", {
            name: copy("en", "unpinContext").replace("{label}", contact.name),
        })).toHaveAttribute("aria-pressed", "true");

        await page.goto(`/records/companies/${fixture.companies.primary.id}`);
        await openDrawer(page);
        const kept = panel(page).getByRole("group", { name: copy("en", "context") });
        await expect(kept.getByText(copy("en", "contextPinned"), { exact: true })).toBeVisible();
        await expect(kept.getByText(contact.name, { exact: true })).toBeVisible();

        await kept
            .getByRole("button", { name: copy("en", "unpinContext").replace("{label}", contact.name) })
            .click();
        await expect(kept.getByText(contact.name, { exact: true })).toBeHidden();
    });

    test("the width control widens the panel and the preference survives a reload", async ({ page }, testInfo) => {
        const contact = runFixture(testInfo.project.name).contacts.peek;
        await page.goto(`/records/contacts/${contact.id}`);
        await openDrawer(page);
        const drawer = panel(page);

        const compactWidth = (await drawer.boundingBox())?.width ?? 0;
        expect(compactWidth).toBeGreaterThan(0);

        const widths = drawer.getByRole("group", { name: copy("en", "width") });
        await expect(widths).toBeVisible();
        await widths.getByRole("button", { name: copy("en", "widthComfortable") }).click();

        await expect.poll(async () => (await drawer.boundingBox())?.width ?? 0)
            .toBeGreaterThan(compactWidth);

        await page.reload();
        await openDrawer(page);
        await expect(
            panel(page)
                .getByRole("group", { name: copy("en", "width") })
                .getByRole("button", { name: copy("en", "widthComfortable") }),
        ).toHaveAttribute("aria-pressed", "true");
    });

    test("open full view carries the same chat into the workspace and back returns to the record", async ({ page }, testInfo) => {
        const contact = runFixture(testInfo.project.name).contacts.peek;
        const chatId = await createChat(page.request, `Full view ${Date.now()}`);

        await page.goto(`/ask-connex/${chatId}`);
        await expect(page.getByRole("heading", { level: 1 })).toBeVisible();
        await page.goto(`/records/contacts/${contact.id}`);
        await openDrawer(page);

        await panel(page).getByRole("button", { name: copy("en", "openWorkspace") }).click();
        await expect(page).toHaveURL(new RegExp(`/ask-connex/${chatId}$`));
        await expect(panel(page)).toHaveCount(0);

        await page.goBack();
        await expect(page).toHaveURL(new RegExp(`/records/contacts/${contact.id}`));
        await expect(page.getByRole("heading", { name: contact.name }).first()).toBeVisible();
    });

    test("the workspace rail bands chats by recency and filters them by title", async ({ page }) => {
        const stamp = Date.now();
        const mine = `Rail chat ${stamp}`;
        const other = `Unrelated ${stamp}`;
        const chatId = await createChat(page.request, mine);
        await createChat(page.request, other);

        await page.goto(`/ask-connex/${chatId}`);

        const rail = page.getByRole("navigation", { name: copy("en", "sessionRail") });
        await expect(rail).toBeVisible();
        await expect(rail.getByRole("heading", { name: copy("en", "sessionGroups.last24h") })).toBeVisible();
        await expect(rail.getByRole("button", { name: new RegExp(mine) })).toHaveAttribute(
            "aria-current",
            "page",
        );
        await expect(rail.getByRole("button", { name: new RegExp(other) })).toBeVisible();

        await page.getByRole("textbox", { name: copy("en", "searchSessions") }).fill(other);
        await expect(rail.getByRole("button", { name: new RegExp(other) })).toBeVisible();
        await expect(rail.getByRole("button", { name: new RegExp(mine) })).toBeHidden();

        await page.getByRole("textbox", { name: copy("en", "searchSessions") }).fill(`no match ${stamp}`);
        await expect(rail.getByText(copy("en", "noMatchingSessions"))).toBeVisible();
    });

    test("a deep link opens that chat and selecting another rewrites the address", async ({ page }) => {
        const stamp = Date.now();
        const first = `Deep link ${stamp}`;
        const second = `Second chat ${stamp}`;
        const firstId = await createChat(page.request, first);
        const secondId = await createChat(page.request, second);

        await page.goto(`/ask-connex/${firstId}`);
        await expect(page.getByRole("heading", { level: 1, name: first })).toBeVisible();

        const rail = page.getByRole("navigation", { name: copy("en", "sessionRail") });
        await rail.getByRole("button", { name: new RegExp(second) }).click();
        await expect(page).toHaveURL(new RegExp(`/ask-connex/${secondId}$`));
        await expect(page.getByRole("heading", { level: 1, name: second })).toBeVisible();

        await page.reload();
        await expect(page.getByRole("heading", { level: 1, name: second })).toBeVisible();
    });

    test("the workspace suppresses the app toolbar and offers no width control", async ({ page }) => {
        const chatId = await createChat(page.request, `Bare workspace ${Date.now()}`);
        await page.goto(`/ask-connex/${chatId}`);

        await expect(page.getByRole("heading", { level: 1 })).toBeVisible();
        await expect(page.locator("[data-app-toolbar]")).toHaveCount(0);
        await expect(page.getByRole("group", { name: copy("en", "width") })).toHaveCount(0);
        await expect(
            page.getByRole("button", { name: copy("en", "openWorkspace") }),
        ).toHaveCount(0);
    });

    test("the command palette reaches the workspace without a sidebar destination", async ({ page }) => {
        await page.goto("/dashboard");
        await expect(page.getByRole("complementary", {
            name: message("en", "common", "CommonSidebar.ariaPrimarySidebar"),
        })).toBeVisible();
        await expect(page.getByRole("link", { name: copy("en", "title"), exact: true })).toHaveCount(0);

        await page.keyboard.press("ControlOrMeta+k");
        const palette = page.getByRole("dialog", {
            name: message("en", "actions", "Actions.palette.trigger"),
        });
        await expect(palette).toBeVisible();
        await palette.getByRole("combobox").fill("Ask Connex");

        const option = palette.getByRole("option", { name: copy("en", "openWorkspaceAction") });
        await expect(option).toBeVisible();
        await option.click();

        await expect(page).toHaveURL(/\/ask-connex/);
        await expect(page.getByRole("heading", { level: 1 })).toBeVisible();
    });

    test("the drawer and the workspace both speak Japanese", async ({ page }, testInfo) => {
        const contact = runFixture(testInfo.project.name).contacts.peek;
        const chatId = await createChat(page.request, `日本語チャット ${Date.now()}`);
        await useLocale(page, "ja");

        await page.goto(`/records/contacts/${contact.id}`);
        await openDrawer(page, "ja");
        const drawer = panel(page);
        await expect(drawer.getByText(copy("ja", "visibilityPrivate"), { exact: true })).toBeVisible();
        await expect(
            drawer.getByRole("button", { name: copy("ja", "openWorkspace") }),
        ).toBeVisible();
        await expect(drawer.getByRole("group", { name: copy("ja", "width") })).toBeVisible();

        await page.goto(`/ask-connex/${chatId}`);
        const rail = page.getByRole("navigation", { name: copy("ja", "sessionRail") });
        await expect(rail).toBeVisible();
        await expect(
            rail.getByRole("heading", { name: copy("ja", "sessionGroups.last24h") }),
        ).toBeVisible();
        await expect(
            page.getByRole("textbox", { name: copy("ja", "searchSessions") }),
        ).toBeVisible();
    });

    test("the bottom bar opens Ask Connex as a full-screen task @mobile-only", async ({ page }, testInfo) => {
        const contact = runFixture(testInfo.project.name).contacts.peek;
        await page.goto(`/records/contacts/${contact.id}`);

        await page.getByRole("button", { name: copy("en", "title"), exact: true }).click();

        const sheet = page.getByRole("dialog", { name: copy("en", "title") });
        await expect(sheet).toBeVisible();
        const bounds = await sheet.boundingBox();
        expect(bounds?.width).toBeGreaterThanOrEqual(400);
        await expect(sheet.getByText(copy("en", "visibilityPrivate"), { exact: true })).toBeVisible();
        await expect(
            sheet.getByRole("group", { name: copy("en", "width") }),
        ).toHaveCount(0);

        await sheet.getByRole("button", { name: copy("en", "close") }).click();
        await expect(sheet).toBeHidden();
        await expect(page.getByRole("heading", { name: contact.name }).first()).toBeVisible();
    });
});
