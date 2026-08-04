import { expect, test } from "@playwright/test";

import { activeWorkspaceId, registerUser } from "./support/api";

function isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === "object" && value !== null;
}

test.describe("workspace and organization identity", () => {
    test("renames both scopes and navigates the authorized organization layout", async ({ browser }, testInfo) => {
        const baseURL = testInfo.project.use.baseURL;
        if (typeof baseURL !== "string") throw new Error("The E2E project requires a base URL");
        const runId = `settings${Date.now().toString(36)}${testInfo.retry}`;
        const context = await browser.newContext({
            baseURL,
            locale: "en-US",
            timezoneId: "UTC",
            reducedMotion: "reduce",
        });
        try {
            await registerUser(context.request, {
                username: runId,
                password: `Settings!${runId}A1`,
                email: `${runId}@example.com`,
            });
            const workspaceId = await activeWorkspaceId(context.request);
            const page = await context.newPage();
            const workspaceName = `Design operations ${runId}`;
            const organizationName = `Northstar ${runId}`;

            await page.goto("/settings/general");
            await expect(page.getByRole("heading", { name: "Workspace identity" })).toBeVisible();
            await page.getByLabel("Workspace name").fill(workspaceName);
            await page.getByLabel("Reporting timezone").fill("Asia/Tokyo");
            await page.getByRole("option", { name: "Asia/Tokyo", exact: true }).click();
            await page.getByRole("button", { name: "Save changes" }).click();
            await expect(page.getByText("Workspace settings updated.")).toBeVisible();
            await expect(page.getByRole("button", { name: "Switch workspace" })).toContainText(workspaceName);

            await page.goto("/organization/overview");
            await expect(page.getByRole("heading", { name: "Organization identity" })).toBeVisible();
            await page.getByLabel("Organization name").fill(organizationName);
            await page.getByRole("button", { name: "Save changes" }).click();
            await expect(page.getByText("Organization settings updated.")).toBeVisible();
            await expect(page.getByText(organizationName, { exact: true }).first()).toBeVisible();
            await expect(page.getByText("Asia/Tokyo", { exact: true })).toBeVisible();

            await page.getByRole("button", { name: "Table" }).click();
            await expect(page.getByRole("table", { name: "Authorized organization workspaces and memberships" }))
                .toBeVisible();
            await expect(page.getByRole("button", { name: workspaceName, exact: true })).toBeVisible();
            await page.getByRole("button", { name: "E2E Harness", exact: true }).click();
            await expect(page).toHaveURL(new RegExp(`/users/\\d+$`));

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
            await context.close();
        }
    });
});
