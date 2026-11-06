import { randomUUID } from 'node:crypto';
import { expect, test, type Locator, type Page } from '@playwright/test';
import type { Locale } from '@/i18n/config';
import { formatCompactCurrency } from '@/app/lib/utils';
import { activeWorkspaceId, csrfBootstrap, registerUser, seeder } from './support/api';
import { useLocale } from './support/locale';
import { message } from './support/messages';

test.use({ storageState: { cookies: [], origins: [] } });

function positiveId(record: Record<string, unknown>): number {
    if (typeof record.id !== 'number' || !Number.isSafeInteger(record.id) || record.id <= 0) {
        throw new Error('Expected a positive record identifier');
    }
    return record.id;
}

async function selectCurrency(page: Page, scope: Locator, locale: Locale, currency: string) {
    const picker = scope.getByRole('button', { name: message(locale, 'analytics', 'AnalyticsPage.currency'), exact: true });
    await picker.focus();
    await picker.press('Enter');
    await page.getByRole('menuitem', { name: new RegExp(currency) }).click();
    await expect(page).toHaveURL(new RegExp(`currency=${currency}`));
}

for (const [locale, theme] of [['en', 'light'], ['ja', 'dark']] as const) {
    test(`Dashboard currency buckets open their Deals records (${locale}, ${theme}) @mobile`, async ({ page, context }) => {
        test.setTimeout(180_000);
        const suffix = randomUUID().replaceAll('-', '').slice(0, 12);
        await registerUser(context.request, {
            username: `curview_${suffix}`, password: `Currency!${suffix}A1`, email: `curview_${suffix}@example.test`,
        });
        const workspaceId = await activeWorkspaceId(context.request);
        const csrf = await csrfBootstrap(context.request);
        const seed = seeder(context.request, workspaceId, csrf);
        const layout = await context.request.put('/api/dashboard-layout', {
            headers: { 'X-Workspace-Id': String(workspaceId), [csrf.headerName]: csrf.token },
            data: { layout: { version: 1, widgets: [
                { id: 'overview', type: 'overview', span: 2 },
                { id: 'pipeline', type: 'pipeline', span: 2 },
                { id: 'kpis', type: 'analyticsKpis', span: 2 },
                { id: 'revenue', type: 'revenueTrend', span: 1 },
                { id: 'wins', type: 'winRate', span: 1 },
                { id: 'value', type: 'pipelineValue', span: 1 },
                { id: 'funnel', type: 'stageFunnel', span: 1 },
            ] } },
        });
        expect(layout.status()).toBe(200);
        const pipeline = positiveId(await seed.post('/api/pipelines', { name: `Currency pipeline ${suffix}` }));
        const stage = positiveId(await seed.post(`/api/pipelines/${pipeline}/stages`, {
            name: 'Proposal', position: 0, success: false, failure: false,
        }));
        const deals = [
            { currency: 'JPY', value: 10_000, name: 'Yen first' },
            { currency: 'JPY', value: 20_000, name: 'Yen second' },
            { currency: 'USD', value: 500, name: 'Dollar deal' },
            { currency: 'EUR', value: 300, name: 'Euro deal' },
        ];
        for (const deal of deals) {
            const review = await seed.post('/api/duplicate-preflight/deals', { name: deal.name });
            if (typeof review.reviewToken !== 'string') throw new Error('Expected a duplicate review token');
            await seed.post('/api/deals', { ...deal, pipeline, stage, duplicateReviewToken: review.reviewToken });
        }
        await useLocale(context, locale);
        await context.addInitScript((choice) => localStorage.setItem('theme', choice), theme);
        await page.emulateMedia({ colorScheme: theme });
        await page.goto('/dashboard');
        const scope = page.getByRole('region', { name: message(locale, 'records', 'DealCurrencyScope.title'), exact: true });
        const dashboardBasis = (currency: string) => message(locale, 'records', 'DealCurrencyScope.dashboardBasis').replace('{currency}', currency);
        const dealsBasis = (currency: string) => message(locale, 'records', 'DealCurrencyScope.dealsBasis').replace('{currency}', currency);
        await expect(scope.getByText(dashboardBasis('JPY'), { exact: true })).toBeVisible();
        const countsBasis = message(locale, 'records', 'DealCurrencyScope.recordCountsBasis');
        await expect(scope.getByText(countsBasis, { exact: true })).toBeVisible();
        await expect(scope).toContainText(locale === 'en' ? '2 deals in other currencies' : '他の通貨の案件2件');
        const projections = page.getByRole('button', { name: new RegExp(message(locale, 'dashboard', 'DashboardPipelineChart.projections')) });
        await expect(projections).toContainText(formatCompactCurrency(30_000, 'JPY', locale));

        for (const [currency, amount] of [['USD', 500], ['EUR', 300], ['JPY', 30_000]] as const) {
            await selectCurrency(page, scope, locale, currency);
            await expect(scope.getByText(dashboardBasis(currency), { exact: true })).toBeVisible();
            await expect(scope.getByText(countsBasis, { exact: true })).toBeVisible();
            await expect(page.getByRole('main').locator('a[href="/records/deals"]')).toContainText('4');
            await expect(scope).toContainText(locale === 'en'
                ? `${currency === 'JPY' ? 2 : 3} deals in other currencies`
                : `他の通貨の案件${currency === 'JPY' ? 2 : 3}件`);
            await expect(projections).toContainText(formatCompactCurrency(amount, currency, locale));
            const widgetBasis = message(locale, 'records', 'DealCurrencyScope.widgetBasis').replace('{currency}', currency);
            await expect(page.getByText(widgetBasis, { exact: true })).toHaveCount(6);
            await page.reload();
            await expect(projections).toContainText(formatCompactCurrency(amount, currency, locale));
            await scope.getByRole('link').click();
            await expect(page).toHaveURL(new RegExp(`/records/deals\\?.*currency=${currency}`));
            await expect(scope.getByText(dealsBasis(currency), { exact: true })).toBeVisible();
            for (const deal of deals) {
                const record = page.getByRole('main').getByText(deal.name, { exact: true }).first();
                if (deal.currency === currency) await expect(record).toBeVisible();
                else await expect(record).toHaveCount(0);
            }
            await page.reload();
            await expect(scope.getByText(dealsBasis(currency), { exact: true })).toBeVisible();
            await page.goto(`/dashboard?currency=${currency}`);
        }

        for (const [requested, expectedCurrency] of [[' ', 'JPY'], [' usd ', 'USD'], ['JPY,USD', 'JPY']] as const) {
            const series = page.waitForResponse((response) => {
                const url = new URL(response.url());
                return url.pathname === '/api/deals/revenue-timeseries'
                    && url.searchParams.get('currency') === expectedCurrency;
            });
            await page.goto(`/records/deals?${new URLSearchParams({ currency: requested })}`);
            await expect(scope.getByText(dealsBasis(expectedCurrency), { exact: true })).toBeVisible();
            expect((await series).status()).toBe(200);
        }

        await page.goto('/records/deals?currency=JPY&view=table&page=2');
        await selectCurrency(page, scope, locale, 'USD');
        await expect(page).not.toHaveURL(/[?&]page=/);
        await expect(page.getByRole('main').getByText('Dollar deal', { exact: true }).first()).toBeVisible();
        await page.reload();
        await expect(scope.getByText(dealsBasis('USD'), { exact: true })).toBeVisible();
        const search = page.getByRole('textbox', { name: message(locale, 'common', 'Filters.searchAria'), exact: true });
        await search.fill('Yen first');
        await expect(scope).toContainText(locale === 'en' ? '1 deal in another currency' : '他の通貨の案件1件');
        await expect(scope.getByText(dealsBasis('USD'), { exact: true })).toBeVisible();
        await selectCurrency(page, scope, locale, 'JPY');
        await expect(page.getByRole('main').getByText('Yen first', { exact: true }).first()).toBeVisible();
    });
}
