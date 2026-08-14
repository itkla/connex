import { expect, test, type Page } from "@playwright/test";

import { activeWorkspaceId, csrfBootstrap, registerUser } from "./support/api";

function isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === "object" && value !== null;
}

async function installVirtualAuthenticator(page: Page): Promise<() => Promise<void>> {
    const session = await page.context().newCDPSession(page);
    await session.send("WebAuthn.enable");
    try {
        const { authenticatorId } = await session.send("WebAuthn.addVirtualAuthenticator", {
            options: {
                protocol: "ctap2",
                ctap2Version: "ctap2_1",
                transport: "internal",
                hasResidentKey: true,
                hasUserVerification: true,
                automaticPresenceSimulation: true,
                isUserVerified: true,
            },
        });
        return async () => {
            if (page.isClosed()) return;
            await session.send("WebAuthn.removeVirtualAuthenticator", { authenticatorId });
            await session.send("WebAuthn.disable");
            await session.detach();
        };
    } catch (error) {
        await session.send("WebAuthn.disable");
        await session.detach();
        throw error;
    }
}

test.describe("workspace and organization identity", () => {
    test("privileged MFA confinement lands on a usable enrolment flow without reloading", async ({ browser }, testInfo) => {
        test.setTimeout(90_000);
        const baseURL = testInfo.project.use.baseURL;
        if (typeof baseURL !== "string") throw new Error("The E2E project requires a base URL");
        const runId = `mfa${Date.now().toString(36)}${testInfo.retry}`;
        const password = `MfaEnroll!${runId}A1`;
        const context = await browser.newContext({
            baseURL,
            locale: "en-US",
            timezoneId: "UTC",
            reducedMotion: "reduce",
            storageState: { cookies: [], origins: [] },
        });
        let removeVirtualAuthenticator: (() => Promise<void>) | null = null;
        try {
            await registerUser(context.request, {
                username: runId,
                password,
                email: `${runId}@example.com`,
            });
            await activeWorkspaceId(context.request);
            await csrfBootstrap(context.request);
            const page = await context.newPage();
            removeVirtualAuthenticator = await installVirtualAuthenticator(page);
            let notificationCountRequests = 0;
            await page.route("**/api/notifications/counts", async (route) => {
                notificationCountRequests += 1;
                if (notificationCountRequests === 1) {
                    await route.fulfill({
                        status: 403,
                        contentType: "application/json",
                        body: JSON.stringify({
                            code: "PRIVILEGED_MFA_ENROLLMENT_REQUIRED",
                            message: "A passkey must be enrolled before this privileged account can continue",
                        }),
                    });
                } else {
                    await route.fallback();
                }
            });

            await page.goto("/account/security");
            await expect(page).toHaveURL(/\/account\/security\?mfa=enroll$/, { timeout: 20_000 });
            const addPasskey = page.getByRole("button", { name: "Add a passkey" }).first();
            await expect(addPasskey).toBeVisible();
            expect(notificationCountRequests).toBe(1);

            await addPasskey.click();
            const passwordDialog = page.getByRole("dialog", { name: "Confirm your password" });
            await passwordDialog.getByLabel("Current password").fill(password);
            await passwordDialog.getByRole("button", { name: "Continue", exact: true }).click();
            await expect(page.getByText("Passkey added", { exact: true })).toBeVisible();
            await expect(page).toHaveURL(/\/account\/security$/);
            await page.getByRole("link", { name: "Dashboard", exact: true }).click();
            await expect(page).toHaveURL(/\/dashboard$/);
            await expect.poll(() => notificationCountRequests).toBeGreaterThan(1);
        } finally {
            if (removeVirtualAuthenticator) await removeVirtualAuthenticator();
            await context.close();
        }
    });

    test("renames both scopes and navigates the authorized organization layout", async ({ browser }, testInfo) => {
        const baseURL = testInfo.project.use.baseURL;
        if (typeof baseURL !== "string") throw new Error("The E2E project requires a base URL");
        const runId = `settings${Date.now().toString(36)}${testInfo.retry}`;
        const password = `Settings!${runId}A1`;
        const context = await browser.newContext({
            baseURL,
            locale: "en-US",
            timezoneId: "UTC",
            reducedMotion: "reduce",
            storageState: { cookies: [], origins: [] },
        });
        let removeVirtualAuthenticator: (() => Promise<void>) | null = null;
        try {
            await registerUser(context.request, {
                username: runId,
                password,
                email: `${runId}@example.com`,
            });
            const workspaceId = await activeWorkspaceId(context.request);
            const page = await context.newPage();
            removeVirtualAuthenticator = await installVirtualAuthenticator(page);
            const workspaceName = `Design operations ${runId}`;
            const organizationName = `Northstar ${runId}`;

            await page.goto("/account/security");
            await page.getByRole("button", { name: "Add a passkey" }).first().click();
            const passwordDialog = page.getByRole("dialog", { name: "Confirm your password" });
            await passwordDialog.getByLabel("Current password").fill(password);
            await passwordDialog.getByRole("button", { name: "Continue", exact: true }).click();
            await expect(page.getByText("Passkey added", { exact: true })).toBeVisible();

            await page.goto("/settings/general");
            await expect(page.getByRole("heading", { name: "Workspace identity" })).toBeVisible();
            await page.getByLabel("Workspace name").fill(workspaceName);
            await page.getByLabel("Reporting timezone").fill("Asia/Tokyo");
            await page.getByRole("option", { name: "Asia/Tokyo", exact: true }).click();
            await page.getByRole("button", { name: "Save changes" }).click();
            await expect(page.getByText("Workspace settings updated.")).toBeVisible();
            await expect(page.getByRole("button", { name: "Switch workspace" })).toContainText(workspaceName);
            await expect(page.getByRole("navigation", { name: "Breadcrumb" })).toContainText(workspaceName);

            await page.goto("/organization/overview");
            await expect(page.getByRole("heading", { name: "Organization identity" })).toBeVisible();
            await page.getByLabel("Organization name").fill(organizationName);
            await page.getByRole("button", { name: "Save changes" }).click();
            await expect(page.getByText("Organization settings updated.")).toBeVisible();
            await expect(page.getByText(organizationName, { exact: true }).first()).toBeVisible();
            await expect(page.getByText("Asia/Tokyo", { exact: true })).toBeVisible();
            await expect(page.getByRole("navigation", { name: "Breadcrumb" })).toContainText(organizationName);

            await page.getByRole("button", { name: "Table" }).click();
            const organizationTable = page.getByRole("table", {
                name: "Authorized organization workspaces and memberships",
            });
            await expect(organizationTable).toBeVisible();
            await expect(organizationTable.getByRole("button", { name: workspaceName, exact: true })).toBeVisible();
            await organizationTable.getByRole("button", { name: "E2E Harness", exact: true }).click();
            await expect(page).toHaveURL(new RegExp(`/users/\\d+$`));
            const userBreadcrumb = page.getByRole("navigation", { name: "Breadcrumb" });
            await expect(userBreadcrumb).toContainText("E2E Harness");
            await expect(userBreadcrumb).toContainText("Users");
            await expect(userBreadcrumb).not.toContainText(organizationName);

            const workspacesResponse = await context.request.get("/api/workspaces");
            expect(workspacesResponse.status()).toBe(200);
            const payload: unknown = await workspacesResponse.json();
            if (!isRecord(payload) || !Array.isArray(payload.workspaces)) {
                throw new Error("Workspace response is missing its membership list");
            }
            const workspace = payload.workspaces.find((entry) => isRecord(entry) && entry.id === workspaceId);
            expect(workspace).toMatchObject({
                name: workspaceName,
                orgName: organizationName,
                timezone: "Asia/Tokyo",
            });
        } finally {
            await removeVirtualAuthenticator?.();
            await context.close();
        }
    });
});
