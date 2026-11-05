import { randomUUID } from 'node:crypto';

import type { ReportDefinition, ReportDefinitionInput, ReportDocument, ReportWidgetData } from '@/app/lib/types';
import { csrfBootstrap, seeder } from './support/api';
import { expect, test } from './support/cleanup';
import { runFixture } from './support/fixtures';
import { useLocale } from './support/locale';
import { message } from './support/messages';

function reportDocument(definition: ReportDefinition, mixed: boolean): ReportDocument {
    const widgets: ReportWidgetData[] = definition.config.widgets.map((widget) => ({
        widgetId: widget.id,
        title: widget.title ?? widget.id,
        chartType: widget.chartType,
        dataSource: widget.dataSource,
        measure: widget.measure,
        groupBy: widget.groupBy,
        unit: mixed ? 'mixed' : 'USD',
        total: mixed ? null : 1_450_000.25,
        priorTotal: mixed ? null : 1_020_000.75,
        changePercent: null,
        points: [
            {
                key: mixed ? 'USD:owner' : 'owner',
                label: mixed ? 'USD · Owner' : 'Owner',
                value: 1_450_000.25,
                priorValue: 1_020_000.75,
                sourceId: `${widget.id}-usd`,
            },
            ...(mixed ? [{
                key: 'JPY:owner',
                label: 'JPY · Owner',
                value: 1_733_430_000,
                priorValue: 1_020_000_000,
                sourceId: `${widget.id}-jpy`,
            }] : []),
        ],
    }));
    const appendix = widgets.flatMap((widget) => widget.points.map((point) => ({
        sourceId: point.sourceId,
        widgetId: widget.widgetId,
        label: `Won revenue · ${point.label}`,
        value: point.value,
        priorValue: point.priorValue,
        unit: point.key.startsWith('JPY:') ? 'JPY' : 'USD',
    })));
    return {
        definition,
        periodStart: '2026-10-01',
        periodEnd: '2026-10-31',
        priorPeriodStart: '2026-09-01',
        priorPeriodEnd: '2026-09-30',
        narrative: {
            available: true,
            sections: [{ title: 'Revenue', claims: [{ text: 'Revenue by owner.', sourceIds: appendix.map((row) => row.sourceId) }] }],
            findings: [],
            reason: null,
            generatedAt: '2026-11-01T00:00:00Z',
            warnings: 0,
        },
        widgets,
        appendix,
        citations: appendix,
        generatedAt: '2026-11-01T00:00:00Z',
    };
}

for (const locale of ['en', 'ja'] as const) {
    test(`report rerun explains mixed-currency charts and preserves monetary precision (${locale}) @mobile`, async ({ page, cleanup }, testInfo) => {
        test.setTimeout(120_000);
        await useLocale(page, locale);
        const fixture = runFixture(testInfo.project.name);
        const widgets: ReportDefinitionInput['config']['widgets'] = (['bar', 'line-area', 'funnel', 'table'] as const)
            .map((chartType) => ({
                id: `revenue-${chartType}`,
                title: `Revenue ${chartType}`,
                dataSource: 'deals',
                measure: 'won_revenue',
                groupBy: 'owner',
                chartType,
            }));
        const input: ReportDefinitionInput = {
            name: `Report display ${randomUUID()}`,
            description: null,
            cadence: 'monthly',
            templateKey: null,
            config: {
                widgets,
                filters: null,
                range: { start: '2026-10-01', end: '2026-10-31' },
                bucket: 'month',
                layout: widgets.map((widget, index) => ({ widgetId: widget.id, x: 0, y: index * 4, width: 12, height: 4 })),
            },
        };
        const created = await seeder(page.request, fixture.workspaceId, await csrfBootstrap(page.request))
            .post('/api/reports', input);
        const reportId = created.id;
        if (typeof reportId !== 'number') throw new Error('The created report has no numeric id');
        cleanup.add(async (api) => {
            const csrf = await csrfBootstrap(api);
            const response = await api.delete(`/api/reports/${reportId}`, {
                headers: { 'X-Workspace-Id': String(fixture.workspaceId), [csrf.headerName]: csrf.token },
            });
            expect(response.status()).toBe(204);
        });
        const definition: ReportDefinition = {
            ...input,
            id: reportId,
            createdBy: null,
            createdAt: '2026-11-01T00:00:00Z',
            updatedAt: '2026-11-01T00:00:00Z',
        };
        let mixed = false;
        await page.route(`**/api/reports/${reportId}/generate?*`, async (route) => {
            expect(route.request().method()).toBe('POST');
            await route.fulfill({ json: reportDocument(definition, mixed) });
        });
        await page.goto(`/insights/reports/${reportId}`);
        const paper = page.getByRole('article');
        const notice = message(locale, 'reports', 'Reports.document.mixedCurrencyChartUnavailable');
        const appendix = paper.locator('section[aria-labelledby="appendix-title"]');
        const citations = paper.locator('section[aria-labelledby="citations-title"]');
        await expect(paper.getByRole('heading', { name: input.name, exact: true })).toBeVisible();
        await expect(paper.locator('[data-slot="chart"]')).toHaveCount(3);
        await expect(paper.getByText(notice, { exact: true })).toHaveCount(0);
        await expect(appendix.getByRole('row').filter({ hasText: '$1,450,000.25' })).toHaveCount(4);
        await expect(appendix.getByRole('row').filter({ hasText: '$1,020,000.75' })).toHaveCount(4);
        await expect(citations.getByRole('listitem').filter({ hasText: '$1,450,000.25' })).toHaveCount(4);

        mixed = true;
        await page.getByRole('button', { name: message(locale, 'reports', 'Reports.document.run'), exact: true }).click();
        await expect(paper.getByText(notice, { exact: true })).toHaveCount(3);
        await expect(paper.locator('[data-slot="chart"]')).toHaveCount(0);
        const yen = locale === 'ja' ? '￥1,733,430,000' : '¥1,733,430,000';
        for (const widget of input.config.widgets) {
            const figure = paper.locator('section').filter({
                has: page.getByRole('heading', { name: widget.title ?? widget.id, exact: true }),
            }).last();
            await expect(figure.getByRole('table')).toBeVisible();
            const usdRow = figure.getByRole('row').filter({ hasText: 'USD · Owner' });
            await expect(usdRow.getByRole('cell', { name: '$1,450,000.25', exact: true })).toBeVisible();
            await expect(usdRow.getByRole('cell', { name: '$1,020,000.75', exact: true })).toBeVisible();
            await expect(figure.getByRole('row').filter({ hasText: 'JPY · Owner' }))
                .toContainText(yen);
        }
        await expect(appendix.getByRole('row').filter({ hasText: yen })).toHaveCount(4);
        await expect(citations.getByRole('listitem').filter({ hasText: yen })).toHaveCount(4);
        await page.emulateMedia({ media: 'print' });
        await expect(paper.getByText(notice, { exact: true }).first()).toBeVisible();
        await expect(appendix.getByRole('cell', { name: '$1,450,000.25', exact: true }).first()).toBeVisible();
    });
}
