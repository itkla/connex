import { expect, test } from "@playwright/test";
import { useLocale } from "./support/locale";
import { message } from "./support/messages";

for (const locale of ["en", "ja"] as const) {
    test(`record actions and metric definitions have keyboard-accessible names (${locale})`, async ({ page, context }) => {
        await useLocale(context, locale);
        for (const [entity, catalog, namespace, key] of [
            ["contacts", "contacts", "ContactsBrowser", "newAria"],
            ["companies", "companies", "CompaniesBrowser", "addCompanyAriaLabel"],
            ["deals", "deals", "DealsBrowser", "addDeal"],
        ]) {
            await page.goto(`/records/${entity}`);
            const action = page.getByRole("main").locator('[data-slot="split-button"]').getByRole("button", {
                name: message(locale, catalog, `${namespace}.${key}`), exact: true,
            });
            await expect(action).toBeVisible();
            await expect(action).toHaveAccessibleName(new RegExp(`^${locale === "ja" ? "新規" : "New"}`));
            const switcher = page.getByRole("button", {
                name: new RegExp(message(locale, "workspace", "WorkspaceSwitcher.switchAria").replace("{workspace}", ".+").replace("{count}", ".+")),
            });
            await expect(switcher).toBeVisible();
            const workspace = switcher.locator(".font-medium");
            const visibleWorkspace = await workspace.textContent();
            const visibleCount = await switcher.locator(".text-xs").textContent();
            expect(visibleWorkspace?.trim()).toBeTruthy();
            expect(visibleCount?.trim()).toBeTruthy();
            await expect(switcher).toHaveAccessibleName(
                message(locale, "workspace", "WorkspaceSwitcher.switchAria").replace("{workspace}", visibleWorkspace?.trim() ?? "").replace("{count}", visibleCount?.trim() ?? ""),
            );
        }

        const info = page.getByRole("button", { name: message(locale, "deals", "DealsBrowser.openDeals"), exact: true });
        await info.focus();
        await page.keyboard.press("Shift+Tab");
        await page.keyboard.press("Tab");
        await expect(info).toBeFocused();
        await expect(page.getByRole("tooltip")).toContainText(message(locale, "deals", "DealsBrowser.openDealsTooltip"));
        await page.keyboard.press("Escape");
        await expect(page.getByRole("tooltip")).toHaveCount(0);
    });

    test(`pagination, toast region and contact selection use the UI language (${locale})`, async ({ page, context }) => {
        await useLocale(context, locale);
        await page.goto("/records/contacts?view=table&size=10");
        await expect(page.getByRole("navigation", { name: message(locale, "common", "UI.pagination"), exact: true })).toBeVisible();
        await expect(page.getByRole("region", { name: `${message(locale, "common", "UI.notificationsRegion")} alt+T`, exact: true })).toBeAttached();
        const selectAll = message(locale, "records", "RecordsRenderView.selectAllAria")
            .replace("{entityLabel}", message(locale, "contacts", "ContactsBrowser.entityLabel"));
        const checkbox = page.getByRole("checkbox", { name: selectAll, exact: true });
        await expect(checkbox).toBeVisible();
        await checkbox.focus();
        await page.keyboard.press("Space");
        await expect(checkbox).toBeChecked();
        if (locale === "ja") {
            await expect(page.getByRole("navigation", { name: "pagination", exact: true })).toHaveCount(0);
            await expect(page.getByRole("checkbox", { name: "すべての contact を選択", exact: true })).toHaveCount(0);
            await expect(page.getByRole("region", { name: /Notifications/ })).toHaveCount(0);
        }
    });
}
