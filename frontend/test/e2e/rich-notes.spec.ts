import { expect, test } from "@playwright/test";

import { csrfBootstrap } from "./support/api";
import { runFixture } from "./support/fixtures";

test.describe("rich notes", () => {
    test("server pagination and sanitized dashboard rendering work on desktop and mobile @mobile", async ({ page, request }, testInfo) => {
        const fixture = runFixture(testInfo.project.name);
        const marker = `PagedNotes${Date.now().toString(36)}`;
        const csrf = await csrfBootstrap(request);
        const headers = {
            "X-Workspace-Id": String(fixture.workspaceId),
            [csrf.headerName]: csrf.token,
        };
        const alpha = await request.post("/api/notes", {
            headers,
            data: {
                title: `${marker} Alpha`,
                content: [
                    `## **${marker} bold**`,
                    "",
                    "- [x] Done",
                    "",
                    `[${fixture.companyName}](company:${fixture.companies.primary.id})`,
                    "",
                    "<script>window.epic340Unsafe = true</script>",
                ].join("\n"),
                visibility: "workspace",
            },
        });
        const zeta = await request.post("/api/notes", {
            headers,
            data: {
                title: `${marker} Zeta`,
                content: `${marker} final page`,
                visibility: "workspace",
            },
        });
        expect(alpha.status(), await alpha.text()).toBe(200);
        expect(zeta.status(), await zeta.text()).toBe(200);

        await page.goto(`/activity/notes?q=${marker}&sort=title&dir=asc&size=1`);
        await expect(page.getByText(`${marker} Alpha`, { exact: true })).toBeVisible();
        await expect(page.getByText("Page 1 of 2", { exact: true })).toBeVisible();
        await page.getByRole("button", { name: "Next notes page" }).click();
        await expect(page.getByText(`${marker} Zeta`, { exact: true })).toBeVisible();
        await expect(page.getByText("Page 2 of 2", { exact: true })).toBeVisible();
        await expect(page).toHaveURL(/page=2/);

        await page.goto("/dashboard");
        await expect(page.getByText(`${marker} bold`, { exact: true }).first()).toBeVisible();
        await expect(page.getByRole("checkbox", { name: "Completed checklist item" }).first()).toBeVisible();
        await expect(page.getByRole("link", { name: fixture.companyName }).first()).toBeVisible();
        await expect(page.locator("body")).not.toContainText(`**${marker} bold**`);
        await expect(page.locator("body")).not.toContainText("window.epic340Unsafe");
        expect(await page.evaluate(() => Object.hasOwn(window, "epic340Unsafe"))).toBe(false);
    });
});
