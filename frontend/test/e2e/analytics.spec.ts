import { expect, test } from "@playwright/test";
import { useLocale } from "./support/locale";
import { message } from "./support/messages";

test.describe("analytics", () => {
    test("custom calendar previews stay available while paging years @mobile", async ({ page }) => {
        await useLocale(page, "en");
        const responses: { url: string; status: number }[] = [];
        page.on("response", (response) => {
            if (new URL(response.url()).pathname === "/api/activities/volume") {
                responses.push({ url: response.url(), status: response.status() });
            }
        });
        await page.goto("/insights/analytics");
        const ranges = page.getByRole("group", { name: message("en", "analytics", "AnalyticsPage.rangeLabel") });
        const beforeOpen = responses.length;
        await ranges.getByRole("button", { name: message("en", "analytics", "AnalyticsPage.rangeCustom"), exact: true }).click();
        const calendar = page.getByRole("group", { name: message("en", "analytics", "AnalyticsPage.rangeCustomGrid") });
        await expect(calendar).toBeVisible();
        await expect.poll(() => responses.slice(beforeOpen).some(({ url }) => new URL(url).searchParams.get("granularity") === "day")).toBe(true);

        async function expectYearResponses(since: number) {
            await expect(calendar.getByRole("grid")).toHaveCount(1);
            const caption = await calendar.getByRole("grid").getAttribute("aria-label");
            const years = caption?.match(/^(\d{4}) - (\d{4})$/);
            if (!years) throw new Error(`Unexpected year page: ${caption}`);
            const firstYear = Number(years[1]);
            const expected = Array.from({ length: 6 }, (_, index) =>
                `${firstYear + index * 2}-01-01:${firstYear + index * 2 + 1}-12-31`);
            await expect.poll(() => new Set(responses.slice(since).flatMap(({ url }) => {
                const params = new URL(url).searchParams;
                const window = `${params.get("from")}:${params.get("to")}`;
                return params.get("granularity") === "month" && expected.includes(window) ? [window] : [];
            })).size).toBe(expected.length);
        }

        const beforeYears = responses.length;
        await calendar.getByRole("button", { name: message("en", "analytics", "AnalyticsPage.rangeCustomZoomYears") }).first().click();
        await expectYearResponses(beforeYears);
        for (const key of ["rangeCustomPrevious", "rangeCustomNext", "rangeCustomNext"]) {
            const beforePage = responses.length;
            await calendar.getByRole("button", { name: message("en", "analytics", `AnalyticsPage.${key}`) }).click();
            await expectYearResponses(beforePage);
        }
        expect(responses.filter(({ status }) => status >= 400 && status < 500)).toEqual([]);
        await expect(page.getByText(message("en", "analytics", "AnalyticsPage.rangeCustomPreviewUnavailable"))).toBeHidden();
    });

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
