import { randomUUID } from 'node:crypto';
import { expect, test, type Locator } from '@playwright/test';
import axe from 'axe-core';
import type { ReportDefinition, ReportDefinitionInput, ReportDocument } from '@/app/lib/types';
import { activeWorkspaceId, csrfBootstrap, registerUser, seeder } from './support/api';
import { useLocale } from './support/locale';
import { message } from './support/messages';
import { chartAxisLabelOverflow, X_AXIS_TICK_LABELS, Y_AXIS_TICK_LABELS } from './support/chart-labels';

declare global {
    interface Window { axe: typeof axe }
}

test.use({ storageState: { cookies: [], origins: [] } });
test.setTimeout(120_000);

function id(record: Record<string, unknown>): number {
    if (typeof record.id !== 'number') throw new Error('Expected a numeric record id');
    return record.id;
}

/** Checks the actual painted label boxes against the SVG and the space allocated before the plot. */
async function expectAxisLabelsToFit(chart: Locator) {
    await expect(chart.locator(Y_AXIS_TICK_LABELS).first()).toBeVisible();
    await expect.poll(() => chart.evaluate(chartAxisLabelOverflow, Y_AXIS_TICK_LABELS)).toEqual([]);
}

for (const [locale, currency, theme] of [['en', 'USD', 'light'], ['ja', 'JPY', 'dark']] as const) {
    test(`chart labels, revenue legend and accessible Deals controls (${locale}/${currency}) @mobile`, async ({ page, context }) => {
        const key = randomUUID();
        await registerUser(context.request, { username: `charts_${key}`, password: `Charts!${key}A1`, email: `charts_${key}@example.invalid` });
        const workspaceId = await activeWorkspaceId(context.request);
        const seed = seeder(context.request, workspaceId, await csrfBootstrap(context.request));
        const pipeline = id(await seed.post('/api/pipelines', { name: `Charts ${key}` }));
        const stage = id(await seed.post(`/api/pipelines/${pipeline}/stages`, { name: 'Proposal', position: 0, success: false, failure: false }));
        const now = new Date();
        const currentMonth = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), 1)).toISOString().slice(0, 10);
        for (const [index, amount] of [6_000_000_000, 8_000_000_000].entries()) {
            const name = `Chart deal ${index} ${key}`;
            const review = await seed.post('/api/duplicate-preflight/deals', { name });
            if (typeof review.reviewToken !== 'string') throw new Error('Expected duplicate review proof');
            await seed.post('/api/deals', { name, value: amount, currency, pipeline, stage, expectedCloseDate: currentMonth, duplicateReviewToken: review.reviewToken });
        }
        await useLocale(context, locale);
        await context.addInitScript((value) => localStorage.setItem('theme', value), theme);
        await page.goto(`/dashboard?currency=${currency}`);
        const pipelineChart = page.locator('[data-chart-kind="dashboard-pipeline"]');
        await expect(pipelineChart).toHaveCount(1);
        await expectAxisLabelsToFit(pipelineChart);
        await pipelineChart.locator('.recharts-wrapper').hover();
        await expect(pipelineChart.locator('.recharts-tooltip-wrapper')).toBeVisible();
        await expect(pipelineChart.locator('.recharts-tooltip-wrapper')).not.toContainText(/\d{4}-\d{1,2}/);
        await expect(page.getByRole('button', { name: new RegExp(message(locale, 'dashboard', 'DashboardPipelineChart.profit')) })).toBeVisible();

        await page.goto(`/insights/analytics?currency=${currency}&range=12m&granularity=month`);
        const revenue = page.locator('[data-slot="chart"]').filter({ has: page.locator('#revenue-won-fill') });
        await expectAxisLabelsToFit(revenue);
        const legend = revenue.locator('.recharts-legend-wrapper');
        await expect(legend).toContainText(message(locale, 'analytics', 'AnalyticsRevenue.actual'));
        await expect(legend).toContainText(message(locale, 'analytics', 'AnalyticsRevenue.projected'));
        await expect(legend.locator('line[stroke-dasharray="5 4"]')).toHaveCount(1);
        await expect(revenue.locator(X_AXIS_TICK_LABELS).filter({ hasText: String(now.getUTCFullYear()) }).first()).toBeVisible();

        const input: ReportDefinitionInput = {
            name: `Chart axes ${key}`, description: null, templateKey: null, cadence: 'monthly',
            config: {
                widgets: (['bar', 'line-area'] as const).map((chartType) => ({ id: chartType, title: chartType, dataSource: 'deals', measure: 'won_revenue', groupBy: 'owner', chartType })),
                filters: null, range: { start: currentMonth, end: currentMonth }, bucket: 'month',
                layout: [{ widgetId: 'bar', x: 0, y: 0, width: 12, height: 4 }, { widgetId: 'line-area', x: 0, y: 4, width: 12, height: 4 }],
            },
        };
        const reportId = id(await seed.post('/api/reports', input));
        const definition: ReportDefinition = { ...input, id: reportId, createdBy: null, createdAt: `${currentMonth}T00:00:00Z`, updatedAt: `${currentMonth}T00:00:00Z` };
        const document: ReportDocument = {
            definition, periodStart: currentMonth, periodEnd: currentMonth, priorPeriodStart: currentMonth, priorPeriodEnd: currentMonth,
            narrative: { available: false, sections: [], findings: [], reason: null, generatedAt: null, warnings: 0 },
            widgets: input.config.widgets.map((widget) => ({
                widgetId: widget.id, title: widget.id, chartType: widget.chartType, dataSource: 'deals', measure: 'won_revenue', groupBy: 'owner', unit: currency,
                total: 6_000_000_000, priorTotal: 8_000_000_000, changePercent: null,
                points: [{ key: 'owner', label: 'Owner', value: 6_000_000_000, priorValue: 8_000_000_000, sourceId: 'owner' }],
            })),
            appendix: [], citations: [], generatedAt: `${currentMonth}T00:00:00Z`,
        };
        await page.route(`**/api/reports/${reportId}/generate?*`, (route) => route.fulfill({ json: document }));
        await page.goto(`/insights/reports/${reportId}`);
        const reportCharts = page.getByRole('article').locator('[data-slot="chart"]');
        await expect(reportCharts).toHaveCount(2);
        for (const chart of await reportCharts.all()) await expectAxisLabelsToFit(chart);

        await page.goto(`/records/deals?currency=${currency}`);
        const charts = page.locator('[data-slot="chart"][role="img"]');
        await expect(charts).toHaveCount(2);
        for (const chart of await charts.all()) await expect(chart).toHaveAttribute('aria-label', /.+/);
        await expect(page.locator('[data-slot="chart"] [tabindex="0"]')).toHaveCount(0);
        await page.evaluate(axe.source);
        const violations = await page.evaluate(async () => {
            const result = await window.axe.run('main', { runOnly: { type: 'rule', values: ['button-name', 'svg-img-alt', 'aria-command-name', 'role-img-alt'] } });
            return result.violations.map(({ id, nodes }) => ({ id, targets: nodes.map((node) => node.target) }));
        });
        expect(violations).toEqual([]);
        await page.getByRole('button', { name: message(locale, 'deals', 'DealsBrowser.addDeal'), exact: true }).click();
        const dialog = page.getByRole('dialog', { name: message(locale, 'deals', 'DealsNewDialog.title'), exact: true });
        await expect(dialog.getByRole('button', { name: message(locale, 'common', 'UI.showOptions'), exact: true }).first()).toBeVisible();
        const dialogViolations = await page.evaluate(async () => {
            const result = await window.axe.run('[role="dialog"]', { runOnly: { type: 'rule', values: ['button-name'] } });
            return result.violations.map(({ id }) => id);
        });
        expect(dialogViolations).toEqual([]);
    });
}
