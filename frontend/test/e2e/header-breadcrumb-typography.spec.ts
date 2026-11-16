import { randomUUID } from 'node:crypto';
import { expect, test, type Locator } from '@playwright/test';
import { formatCompactCurrency } from '@/app/lib/utils';
import { activeWorkspaceId, csrfBootstrap, registerUser, seeder } from './support/api';
import { useLocale } from './support/locale';
import { message } from './support/messages';

test.use({ storageState: { cookies: [], origins: [] } });
test.setTimeout(240_000);

function id(record: Record<string, unknown>): number {
    if (typeof record.id !== 'number' || !Number.isSafeInteger(record.id) || record.id <= 0) {
        throw new Error('Expected a positive record id');
    }
    return record.id;
}

async function expectCurrentReadable(toolbar: Locator, label: string) {
    const current = toolbar.locator('[aria-current="page"]');
    await expect(current).toHaveText(label);
    await expect.poll(() => current.evaluate((element) => {
        const text = element.firstChild;
        const item = element.closest('li');
        if (!(text instanceof Text) || !item) throw new Error('Expected the current breadcrumb text');
        const firstSix = new Range();
        firstSix.setStart(text, 0);
        firstSix.setEnd(text, [...text.data].slice(0, 6).join('').length);
        const context = document.createElement('canvas').getContext('2d');
        if (!context) throw new Error('Expected text measurement');
        context.font = getComputedStyle(element).font;
        const ellipsis = element.scrollWidth > element.clientWidth ? context.measureText('…').width : 0;
        const rect = element.getBoundingClientRect();
        const list = element.closest('ol')?.getBoundingClientRect();
        return {
            minimumMet: rect.width >= parseFloat(getComputedStyle(item).minWidth),
            sixCharactersFit: firstSix.getBoundingClientRect().width + ellipsis <= rect.width + 1,
            insideTrail: !!list && rect.left >= list.left - 1 && rect.right <= list.right + 1,
        };
    })).toEqual({ minimumMet: true, sixCharactersFit: true, insideTrail: true });
    const search = toolbar.getByRole('search');
    const trail = toolbar.locator('[data-slot="breadcrumb"]');
    const searchBox = await search.boundingBox();
    const trailBox = await trail.boundingBox();
    if (!searchBox || !trailBox) throw new Error('Expected visible header search and breadcrumbs');
    expect(trailBox.x + trailBox.width).toBeLessThanOrEqual(searchBox.x);
    expect(searchBox.width).toBeLessThan(576);
    expect(searchBox.width).toBeGreaterThan(256);
}

async function expectUnbroken(value: Locator) {
    await expect(value).toHaveCSS('white-space', 'nowrap');
    expect(await value.evaluate((element) => {
        const range = new Range();
        range.selectNodeContents(element);
        return new Set(Array.from(range.getClientRects(), (rect) => Math.round(rect.top))).size;
    })).toBe(1);
}

for (const locale of ['en', 'ja'] as const) {
    for (const theme of ['light', 'dark'] as const) {
        test(`NAV-04 / INS-21 / JA-24 header and numeric typography (${locale}/${theme})`, async ({ page, context }, testInfo) => {
            const key = randomUUID();
            const displayName = locale === 'ja' ? '株式会社つながり営業企画チーム' : 'Relationship Intelligence Sales Operations';
            await registerUser(context.request, {
                username: `header_${key}`, password: `Header!${key}A1`, email: `header_${key}@example.invalid`, displayName,
            });
            const workspaceId = await activeWorkspaceId(context.request);
            const seed = seeder(context.request, workspaceId, await csrfBootstrap(context.request));
            const pipeline = id(await seed.post('/api/pipelines', { name: 'Header pipeline' }));
            const stage = id(await seed.post(`/api/pipelines/${pipeline}/stages`, { name: 'Proposal', position: 0, success: false, failure: false }));
            const recordName = locale === 'ja' ? '株式会社つながり年間契約更新' : 'Relationship platform annual renewal';
            const review = await seed.post('/api/duplicate-preflight/deals', { name: recordName });
            if (typeof review.reviewToken !== 'string') throw new Error('Expected duplicate review proof');
            const amount = 21_050_000_000;
            const dealId = id(await seed.post('/api/deals', {
                name: recordName, pipeline, stage, value: amount, currency: 'JPY', expectedCloseDate: '2026-12-25', duplicateReviewToken: review.reviewToken,
            }));
            const reportName = locale === 'ja' ? '月次営業パイプライン確認資料' : 'Monthly relationship pipeline review';
            const reportId = id(await seed.post('/api/reports', {
                name: reportName, description: null, cadence: 'monthly', templateKey: null,
                config: {
                    widgets: [{ id: 'deals', title: 'Deals', dataSource: 'deals', measure: 'count', groupBy: 'owner', chartType: 'table' }],
                    filters: null, range: { start: '2026-12-01', end: '2026-12-31' }, bucket: 'month',
                    layout: [{ widgetId: 'deals', x: 0, y: 0, width: 12, height: 4 }],
                },
            }));
            const snapshotId = id(await seed.post(`/api/reports/${reportId}/snapshots`, {}));
            await useLocale(context, locale);
            await context.addInitScript((value) => localStorage.setItem('theme', value), theme);

            for (const width of [1280, 1440, 1600]) {
                await page.setViewportSize({ width, height: 1000 });
                const cases = [
                    { route: `/insights/reports/${reportId}/snapshots/${snapshotId}`, label: message(locale, 'common', 'CommonBreadcrumb.snapshot'), surface: 'snapshot' },
                    { route: `/records/deals/${dealId}`, label: recordName, surface: 'record' },
                    { route: '/settings/workspace/people', label: message(locale, 'settings', 'SettingsNav.groupPeopleAccess'), surface: 'settings' },
                ];
                for (const { route, label, surface } of cases) {
                    await page.goto(route);
                    await expect(page.locator('html')).toHaveAttribute('lang', locale);
                    await page.evaluate(() => document.fonts.ready.then(() => undefined));
                    const toolbar = page.locator('[data-app-toolbar]');
                    await expectCurrentReadable(toolbar, label);
                    if (surface === 'snapshot') {
                        const overflow = toolbar.getByRole('button', { name: message(locale, 'common', 'CommonBreadcrumb.showHidden'), exact: true });
                        await overflow.focus();
                        await overflow.press('Enter');
                        await expect(page.getByRole('menuitem', { name: message(locale, 'common', 'CommonBreadcrumb.reports'), exact: true })).toHaveAttribute('href', '/insights/reports');
                        await expect(page.getByRole('menuitem', { name: reportName, exact: true })).toHaveAttribute('href', `/insights/reports/${reportId}`);
                        await page.keyboard.press('Escape');
                        const dateLabel = page.getByRole('article').locator('dt').filter({ hasText: message(locale, 'reports', 'Reports.document.snapshot') });
                        await expectUnbroken(dateLabel.locator('..').locator('dd'));
                        await expectUnbroken(page.getByRole('article').locator('footer span').last());
                        for (const date of await page.getByRole('article').locator('dl dd').all()) await expectUnbroken(date);
                        await testInfo.attach(`report-dates-${width}`, { body: await page.getByRole('article').locator('dl').screenshot(), contentType: 'image/png' });
                    }
                    if (surface === 'record') {
                        for (const key of ['expectedClose', 'closedAt', 'created', 'updated'] as const) {
                            const dateLabel = page.locator('dt').filter({ hasText: message(locale, 'deals', `DealsPage.${key}`) });
                            await expectUnbroken(dateLabel.locator('..').locator('dd'));
                        }
                        const dateLabel = page.locator('dt').filter({ hasText: message(locale, 'deals', 'DealsPage.expectedClose') });
                        await testInfo.attach(`record-date-${width}`, { body: await dateLabel.locator('..').screenshot(), contentType: 'image/png' });
                    }
                    await testInfo.attach(`${surface}-${width}`, { body: await toolbar.screenshot(), contentType: 'image/png' });
                }

                await page.goto('/dashboard?currency=JPY');
                if (locale === 'ja') {
                    await expect(page.locator('body')).toHaveCSS('word-break', 'keep-all');
                    await expect(page.locator('body')).toHaveCSS('line-break', 'strict');
                    await expect(page.locator('body')).toHaveCSS('overflow-wrap', 'anywhere');
                }
                const projections = page.getByRole('button', { name: new RegExp(message(locale, 'dashboard', 'DashboardPipelineChart.projections')) });
                const compact = formatCompactCurrency(amount, 'JPY', locale);
                await expectUnbroken(projections.getByText(compact, { exact: true }));
                await testInfo.attach(`pipeline-${width}`, { body: await projections.screenshot(), contentType: 'image/png' });
                await page.goto('/records/deals?currency=JPY');
                const summary = page.locator('section').filter({ has: page.getByRole('heading', { name: message(locale, 'deals', 'DealsBrowser.sectionSummary'), exact: true }) });
                await expectUnbroken(summary.getByText(compact, { exact: true }));
                await testInfo.attach(`deal-summary-${width}`, { body: await summary.screenshot(), contentType: 'image/png' });
            }
        });
    }
}
