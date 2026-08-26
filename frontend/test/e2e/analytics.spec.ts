import { expect, test } from "@playwright/test";

test.describe("analytics", () => {
    test("range and granularity switching updates the URL, controls, and panels", async ({ page }) => {
        await page.goto("/insights/analytics");
        await expect(page.getByRole("heading", { name: "Analytics" })).toBeVisible();
        await expect(page.getByRole("heading", { name: "At a glance" })).toBeVisible();
        await expect(page.getByRole("heading", { name: "Trends" })).toBeVisible();
        await expect(page.getByRole("heading", { name: "Breakdowns and diagnostics" })).toBeVisible();

        const rangeGroup = page.getByRole("group", { name: "Time range" });
        await expect(rangeGroup).toBeVisible();
        await expect(rangeGroup.getByRole("button", { name: "90 days" })).toHaveAttribute("aria-pressed", "true");
        await expect(page.getByRole("heading", { name: "Revenue", exact: true })).toBeVisible();

        const granularityGroup = page.getByRole("group", { name: "Granularity" });
        await expect(granularityGroup.getByRole("button", { name: "Month" })).toBeVisible();

        await rangeGroup.getByRole("button", { name: "30 days" }).click();
        await expect(rangeGroup.getByRole("button", { name: "30 days" })).toHaveAttribute("aria-pressed", "true");
        await expect(page).toHaveURL(/range=30d/);
        await expect(granularityGroup.getByRole("button", { name: "Month" })).toBeHidden();

        await granularityGroup.getByRole("button", { name: "Day" }).click();
        await expect(granularityGroup.getByRole("button", { name: "Day" })).toHaveAttribute("aria-pressed", "true");
        await expect(page).toHaveURL(/granularity=day/);

        await expect(page.getByRole("heading", { name: "Revenue", exact: true })).toBeVisible();
        await expect(page.getByRole("heading", { name: "Pipeline by stage" })).toBeVisible();
    });
});
