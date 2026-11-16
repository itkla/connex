import { expect, test as base } from "@playwright/test";
import { runFixture } from "./support/fixtures";
import { useLocale } from "./support/locale";
import { message } from "./support/messages";

const test = base.extend<{ hydrationErrors: string[] }>({
    hydrationErrors: [async ({ page }, use) => {
        const errors: string[] = [];
        const recordError = (text: string) => {
            if (/Minified React error #418|Hydration failed|hydrated but some attributes/i.test(text)) {
                errors.push(text);
            }
        };
        page.on("console", (event) => {
            if (event.type() === "error") recordError(event.text());
        });
        page.on("pageerror", (error) => recordError(error.stack ?? error.message));
        await use(errors);
        expect(errors, "Server HTML must hydrate without replacement or attribute mismatches").toEqual([]);
    }, { auto: true }],
});

const routes = [
    "dashboard", "deals", "deal detail", "pipelines", "contacts", "companies",
    "contact detail", "company detail", "CRM settings",
] as const;

test.use({ timezoneId: "America/Los_Angeles" });

for (const locale of ["en", "ja"] as const) {
    test.describe(`authenticated hydration (${locale})`, () => {
        for (const route of routes) {
            test(`hydrates ${route} on a full page load with saved rail mode @mobile`, async ({ page, isMobile }, testInfo) => {
                const fixture = runFixture(testInfo.project.name);
                const paths = {
                    dashboard: "/dashboard",
                    deals: "/records/deals",
                    "deal detail": `/records/deals/${fixture.deals.primary.id}`,
                    pipelines: "/records/pipelines",
                    contacts: "/records/contacts",
                    companies: "/records/companies",
                    "contact detail": `/records/contacts/${fixture.contacts.peek.id}`,
                    "company detail": `/records/companies/${fixture.companies.primary.id}`,
                    "CRM settings": "/settings/workspace/crm",
                };
                await useLocale(page, locale);
                const viewport = page.viewportSize();
                if (isMobile) await page.setViewportSize({ width: 1280, height: 900 });
                await page.goto("/dashboard");
                await expect(page.locator("[data-app-main]").getByRole("heading", { level: 1 }).first()).toBeVisible();
                await page.getByRole("button", {
                    name: message(locale, "common", "CommonContentShell.collapseSidebar"), exact: true,
                }).click();
                await expect(page.getByRole("button", {
                    name: message(locale, "common", "CommonContentShell.expandSidebar"), exact: true,
                })).toBeVisible();
                if (isMobile && viewport) await page.setViewportSize(viewport);

                const response = await page.goto(paths[route]);
                expect(response?.ok()).toBe(true);
                await expect(page.locator("[data-app-main]").getByRole("heading", { level: 1 }).first()).toBeVisible();
                expect(new URL(page.url()).pathname).toBe(paths[route]);

                if (isMobile) {
                    await page.getByRole("button", {
                        name: message(locale, "common", "CommonContentShell.showSidebar"), exact: true,
                    }).click();
                }
                await page.getByRole("button", {
                    name: message(locale, "workspace", "WorkspaceSwitcher.switchAria"), exact: true,
                }).click();
                await expect(page.getByRole("menu")).toBeVisible();
                await page.keyboard.press("Escape");
                await expect(page.getByRole("menu")).toHaveCount(0);
            });
        }
    });
}
