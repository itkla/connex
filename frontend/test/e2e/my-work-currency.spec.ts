import { randomUUID } from "node:crypto";
import { expect, test, type Locator } from "@playwright/test";

import type { Locale } from "@/i18n/config";
import { formatCompactCurrency } from "@/app/lib/utils";
import { activeWorkspaceId, csrfBootstrap, registerUser, seeder, type Seeder } from "./support/api";
import { useLocale } from "./support/locale";
import { message } from "./support/messages";

test.use({ storageState: { cookies: [], origins: [] } });

const scenarios: ReadonlyArray<{ locale: Locale; theme: "light" | "dark" }> = [
    { locale: "en", theme: "light" },
    { locale: "ja", theme: "dark" },
];

function positiveId(record: Record<string, unknown>): number {
    if (typeof record.id !== "number" || !Number.isInteger(record.id) || record.id <= 0) {
        throw new Error("The seeded record has no valid id");
    }
    return record.id;
}

function numericField(value: unknown, key: string): number {
    if (typeof value !== "object" || value === null || !(key in value)) {
        throw new Error(`Missing KPI field ${key}`);
    }
    const field: unknown = Reflect.get(value, key);
    if (typeof field !== "number" || !Number.isFinite(field)) {
        throw new Error(`Invalid KPI field ${key}`);
    }
    return field;
}

async function seedDeals(seed: Seeder, pipelineId: number, stageId: number, currency: "JPY" | "USD"): Promise<void> {
    const amounts = currency === "JPY"
        ? [10_000_000, 10_000_000, 10_000_000, 10_000_000, 5_000_000, 450_000]
        : [100_000, 100_000, 100_000, 100_000, 100_000, 100_000, 100_000, 25_000, 10_000, 0];
    for (const [index, value] of amounts.entries()) {
        const name = `${currency} opportunity ${index + 1}`;
        const review = await seed.post("/api/duplicate-preflight/deals", { name });
        if (typeof review.reviewToken !== "string") throw new Error("Deal preflight did not return a review token");
        const id = positiveId(await seed.post("/api/deals", {
            name, value, currency, pipeline: pipelineId, stage: stageId, actualValue: 0, duplicateReviewToken: review.reviewToken,
        }));
        if (currency === "JPY" && index === 0) {
            await seed.post(`/api/deals/${id}/close`, { won: true, actualValue: 1_200_000 });
        } else if (currency === "USD" && index < 3) {
            await seed.post(`/api/deals/${id}/close`, {
                won: index < 2, actualValue: index === 0 ? 25_400 : index === 1 ? 10_000 : 0,
            });
        }
    }
}

async function expectMoney(signals: Locator, locale: Locale, currency: "JPY" | "USD"): Promise<void> {
    const pipeline = currency === "USD" ? 735_000 : 45_450_000;
    const revenue = currency === "USD" ? 35_400 : 1_200_000;
    await expect(signals.getByText(formatCompactCurrency(pipeline, currency, locale), { exact: true })).toBeVisible();
    await expect(signals.getByText(formatCompactCurrency(revenue, currency, locale), { exact: true })).toBeVisible();
    const scope = message(locale, "me", "MePage.currencyScope").replace("{currency}", currency);
    await expect(signals.getByText(scope, { exact: true })).toHaveCount(2);
    await expect(signals.getByText(currency === "USD" ? "67%" : "100%", { exact: true })).toBeVisible();
}

for (const { locale, theme } of scenarios) {
    test(`My Work scopes every deal KPI by currency and agrees with Analytics (${locale}, ${theme}) @mobile`, async ({ page, context }) => {
        test.setTimeout(180_000);
        const unique = randomUUID().replaceAll("-", "").slice(0, 12);
        await registerUser(context.request, {
            username: `currency_${unique}`, password: `Currency!${unique}A1`, email: `currency_${unique}@example.test`,
        });
        const workspaceId = await activeWorkspaceId(context.request);
        const seed = seeder(context.request, workspaceId, await csrfBootstrap(context.request));
        const pipelineId = positiveId(await seed.post("/api/pipelines", { name: "Currency pipeline" }));
        const stageId = positiveId(await seed.post(`/api/pipelines/${pipelineId}/stages`, {
            name: "Qualifying", position: 0, success: false, failure: false,
        }));
        await useLocale(context, locale);
        await context.addInitScript((choice: "light" | "dark") => localStorage.setItem("theme", choice), theme);
        await page.emulateMedia({ colorScheme: theme });

        await seedDeals(seed, pipelineId, stageId, "JPY");
        await page.goto("/me");
        const signals = page.getByRole("region", { name: message(locale, "me", "MePage.dealSignals") });
        const currencyLabel = message(locale, "analytics", "AnalyticsPage.currency");
        await expectMoney(signals, locale, "JPY");
        await expect(signals.getByRole("button", { name: currencyLabel, exact: true })).toHaveCount(0);

        await seedDeals(seed, pipelineId, stageId, "USD");
        await page.goto("/me");
        await expectMoney(signals, locale, "USD");
        const picker = signals.getByRole("button", { name: currencyLabel, exact: true });
        await picker.focus();
        await picker.press("Enter");
        await page.getByRole("menuitem", { name: /JPY/ }).click();
        await expectMoney(signals, locale, "JPY");
        await expect(page).toHaveURL(/currency=JPY/);
        await page.reload();
        await expectMoney(signals, locale, "JPY");

        for (const currency of ["USD", "JPY"] as const) {
            const responsePromise = page.waitForResponse((response) => {
                const url = new URL(response.url());
                return url.pathname === "/api/deals/kpis"
                    && url.searchParams.get("currency") === currency
                    && url.searchParams.get("scope") === "me";
            });
            await page.goto(`/insights/analytics?owner=me&currency=${currency}`);
            const response = await responsePromise;
            expect(response.status()).toBe(200);
            const data: unknown = await response.json();
            const pipeline = currency === "USD" ? 735_000 : 45_450_000;
            const revenue = currency === "USD" ? 35_400 : 1_200_000;
            expect(data).toMatchObject({
                newPipeline: pipeline, wonRevenue: revenue,
                wonCount: currency === "USD" ? 2 : 1, lostCount: currency === "USD" ? 1 : 0,
            });
            await expect(page.getByText(formatCompactCurrency(pipeline, currency, locale), { exact: true }).first()).toBeVisible();
            await expect(page.getByText(formatCompactCurrency(revenue, currency, locale), { exact: true }).first()).toBeVisible();
            await page.goto(`/me?currency=${currency}`);
            await expectMoney(signals, locale, currency);
            const cycle = `${Math.round(numericField(data, "avgCycleDays"))} ${message(locale, "me", "MePage.daysUnit")}`;
            await expect(signals.getByText(cycle, { exact: true })).toBeVisible();
        }
    });
}
