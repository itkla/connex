import { randomUUID } from "node:crypto";
import { expect, test, type Page } from "@playwright/test";

import { registerUser } from "./support/api";
import { message } from "./support/messages";
import { useLocale } from "./support/locale";
import { seedFixture, seedFixtureAvailable } from "./support/seed";

test.use({ storageState: { cookies: [], origins: [] } });

/** Captures resource refusals as well as the browser's associated console noise. */
function observeRefusals(page: Page) {
    const responses: string[] = [];
    const consoleErrors: string[] = [];
    const assistantReads: string[] = [];
    page.on("response", (response) => {
        if (response.status() === 403) responses.push(new URL(response.url()).pathname);
    });
    page.on("console", (entry) => {
        if (entry.type() === "error" && /403|forbidden/i.test(entry.text())) consoleErrors.push(entry.text());
    });
    page.on("request", (request) => {
        const path = new URL(request.url()).pathname;
        if (path.startsWith("/api/ai/assistant/")) assistantReads.push(path);
    });
    return { responses, consoleErrors, assistantReads };
}

test("a default member's first load has no refused background requests or red toast @mobile", async ({ page }) => {
    test.skip(!seedFixtureAvailable(), "The deterministic volume seeder has not run against this stack");
    const seed = seedFixture();
    const member = seed.workspaces[0].users.find((user) => user.role === "member");
    if (!member) throw new Error("The seeded workspace needs a default member");
    await useLocale(page, "en");
    await page.goto("/auth/login");
    await page.getByLabel(message("en", "auth", "AuthForm.labelLoginIdentifier"), { exact: true }).fill(member.username);
    await page.getByLabel(message("en", "auth", "AuthForm.labelPassword"), { exact: true }).fill(seed.password);
    const observed = observeRefusals(page);
    await page.getByRole("button", { name: message("en", "auth", "AuthLogin.submitLabel"), exact: true }).click();
    await expect(page).toHaveURL(/\/dashboard/, { timeout: 20_000 });
    await expect(page.locator("[data-app-main]")).toBeVisible();
    await page.waitForLoadState("networkidle");
    expect(observed.responses).toEqual([]);
    expect(observed.consoleErrors).toEqual([]);
    expect(observed.assistantReads).toEqual([]);
    await expect(page.locator('[data-sonner-toast][data-type="error"]')).toHaveCount(0);
    await expect(page.getByRole("button", { name: message("en", "common", "AskConnex.title"), exact: true })).toHaveCount(0);
});

test("a sole owner's passkey step stays quiet and explains client navigation @mobile", async ({ page }, testInfo) => {
    const suffix = `gates${randomUUID().replaceAll("-", "").slice(0, 16)}${testInfo.workerIndex}`;
    await registerUser(page.request, {
        username: suffix, email: `${suffix}@example.com`, password: `Gates!${suffix}A1`,
    });
    await useLocale(page, "en");
    await page.route("**/api/capabilities", async (route) => {
        const response = await route.fetch();
        const body: unknown = await response.json();
        if (typeof body !== "object" || body === null || Array.isArray(body)) throw new Error("Expected capability posture");
        await route.fulfill({ response, json: { ...body, privilegedMfaEnforced: true } });
    });
    await page.route("**/api/auth/webauthn/register/requirements", async (route) => {
        const response = await route.fetch();
        const body: unknown = await response.json();
        if (typeof body !== "object" || body === null || Array.isArray(body)) throw new Error("Expected passkey requirements");
        await route.fulfill({ response, json: { ...body, standing: "ENROLLMENT_REQUIRED" } });
    });
    const observed = observeRefusals(page);
    const blockedReads: string[] = [];
    page.on("request", (request) => {
        const path = new URL(request.url()).pathname;
        if (["/api/saved-views/pins", "/api/notifications/counts", "/api/tasks/summary"].includes(path)) blockedReads.push(path);
    });
    await page.goto("/settings/personal/security?mfa=enroll");
    await expect(page.getByRole("heading", { name: message("en", "account", "AccountSecurity.enrollmentNeededTitle") })).toBeVisible();
    await page.waitForLoadState("networkidle");
    await expect(page.getByRole("button", { name: /^Leave (workspace|organization):/ })).toHaveCount(0);
    await expect(page.getByText(message("en", "account", "AccountSecurity.exitRefusedWorkspaceLastOwner"), { exact: true })).toBeVisible();
    if (testInfo.project.name === "mobile-chromium") {
        await page.getByRole("button", { name: message("en", "common", "MobileNav.more"), exact: true }).click();
    }
    await page.getByRole("link", { name: message("en", "common", "CommonSidebar.navContacts"), exact: true }).first().click();
    await expect(page.getByRole("link", { name: message("en", "account", "AccountSecurity.returnToSecurity"), exact: true }))
        .toHaveAttribute("href", "/settings/personal/security?mfa=enroll");
    await page.waitForLoadState("networkidle");
    expect(blockedReads).toEqual([]);
    expect(observed.assistantReads).toEqual([]);
    expect(observed.responses).toEqual([]);
    expect(observed.consoleErrors).toEqual([]);
    await expect(page.locator('[data-sonner-toast][data-type="error"]')).toHaveCount(0);
});

test("a fresh enforced owner waits for the account check before mounting readers @mobile", async ({ page }) => {
    const suffix = `gatesfresh${randomUUID().replaceAll("-", "").slice(0, 16)}`;
    await registerUser(page.request, { username: suffix, email: `${suffix}@example.com`, password: `Gates!${suffix}A1` });
    await useLocale(page, "en");
    const standing = Promise.withResolvers<void>();
    await page.route("**/api/capabilities", async (route) => {
        const response = await route.fetch();
        const body: unknown = await response.json();
        if (typeof body !== "object" || body === null || Array.isArray(body)) throw new Error("Expected capability posture");
        await route.fulfill({ response, json: { ...body, privilegedMfaEnforced: true } });
    });
    await page.route("**/api/auth/webauthn/register/requirements", async (route) => {
        await standing.promise;
        await route.fallback();
    });
    const observed = observeRefusals(page);
    const protectedReads: string[] = [];
    page.on("request", (request) => {
        const path = new URL(request.url()).pathname;
        if (["/api/saved-views/pins", "/api/notifications/counts", "/api/tasks/summary", "/api/persons/page"].includes(path)) protectedReads.push(path);
    });
    try {
        await page.goto("/records/contacts");
        await expect(page.getByRole("heading", { name: message("en", "account", "AccountSecurity.accessCheckingTitle") })).toBeVisible();
        expect(protectedReads).toEqual([]);
        expect(observed.assistantReads).toEqual([]);
        standing.resolve();
        await expect(page.getByRole("link", { name: message("en", "account", "AccountSecurity.returnToSecurity") })).toBeVisible();
        await page.waitForLoadState("networkidle");
        expect(protectedReads).toEqual([]);
        expect(observed.assistantReads).toEqual([]);
        expect(observed.responses).toEqual([]);
        expect(observed.consoleErrors).toEqual([]);
        await expect(page.locator('[data-sonner-toast][data-type="error"]')).toHaveCount(0);
    } finally {
        standing.resolve();
        await page.unrouteAll({ behavior: "wait" });
    }
});

test("an unconfigured assistant explains availability before accepting a question", async ({ page }) => {
    const suffix = `gatesai${randomUUID().replaceAll("-", "").slice(0, 16)}`;
    await registerUser(page.request, {
        username: suffix, email: `${suffix}@example.com`, password: `Gates!${suffix}A1`,
    });
    await useLocale(page, "en");
    await page.goto("/ask-connex");
    await expect(page.getByRole("heading", { name: message("en", "common", "AskConnex.unavailable.featureTitle") })).toBeVisible();
    await expect(page.getByRole("combobox", { name: message("en", "common", "AskConnex.composerAria") })).toHaveCount(0);
    await expect(page.getByRole("button", { name: message("en", "common", "AskConnex.jobs.workspaceActivity.label"), exact: true })).toHaveCount(0);
    await expect(page.locator('[data-sonner-toast][data-type="error"]')).toHaveCount(0);
});
