import { expect, test } from "@playwright/test";

/**
 * Every spec in this suite runs with `reducedMotion: "reduce"`, and Playwright counts an element at
 * `opacity: 0` as visible, so a page whose sections never fade in passes every other spec (#2017).
 * This check reads what a person would see instead: once the page has hydrated, every heading in its
 * main region must be painted, with the opacity of every ancestor included. Settings pages are the
 * ones the defect blanked out entirely.
 */
test.describe("reduced motion", () => {
    for (const path of ["/settings/personal/security", "/settings/workspace/people"]) {
        test(`keeps every heading on ${path} painted after hydration`, async ({ page }) => {
            await page.goto(path);
            const headings = page.locator("main").getByRole("heading");
            await expect(headings.first()).toBeVisible();

            await expect
                .poll(() => headings.evaluateAll((elements) => elements
                    .filter((element) => !element.checkVisibility({ opacityProperty: true }))
                    .map((element) => element.textContent?.trim() ?? "")))
                .toEqual([]);
        });
    }
});
