import { randomUUID } from 'node:crypto';
import { expect, test } from './support/cleanup';
import { csrfBootstrap, seeder } from './support/api';
import { runFixture } from './support/fixtures';
import { useLocale } from './support/locale';
import { message } from './support/messages';

function isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === 'object' && value !== null;
}

for (const locale of ['en', 'ja'] as const) {
    test(`report live CSV, snapshot CSV and PNG leave export evidence (${locale}) @mobile`, async ({ page, cleanup }, testInfo) => {
        test.setTimeout(120_000);
        await useLocale(page, locale);
        const fixture = runFixture(testInfo.project.name);
        const name = `Export audit ${randomUUID()}`;
        const created = await seeder(page.request, fixture.workspaceId, await csrfBootstrap(page.request)).post('/api/reports', {
            name, description: null, cadence: 'monthly', templateKey: null,
            config: {
                widgets: [{ id: 'deals', title: 'Deals', dataSource: 'deals', measure: 'count', groupBy: 'owner', chartType: 'table' }],
                filters: null, range: { start: '2026-10-01', end: '2026-10-31' }, bucket: 'month',
                layout: [{ widgetId: 'deals', x: 0, y: 0, width: 12, height: 4 }],
            },
        });
        const reportId = created.id;
        if (typeof reportId !== 'number') throw new Error('Expected a report ID');
        cleanup.add(async (api) => {
            const csrf = await csrfBootstrap(api);
            const response = await api.delete(`/api/reports/${reportId}`, {
                headers: { 'X-Workspace-Id': String(fixture.workspaceId), [csrf.headerName]: csrf.token },
            });
            expect(response.status()).toBe(204);
        });
        await page.goto(`/insights/reports/${reportId}`);
        await expect(page.getByRole('article').getByRole('heading', { name, exact: true })).toBeVisible();
        const exportButton = page.getByRole('button', { name: message(locale, 'reports', 'Reports.document.export'), exact: true });
        await expect(exportButton).toBeEnabled();
        await exportButton.click();
        const liveDownload = page.waitForEvent('download');
        await page.getByRole('menuitem', { name: message(locale, 'reports', 'Reports.document.csv'), exact: true }).click();
        expect((await liveDownload).suggestedFilename()).toBe(`${name}.csv`);

        const snapshotResponse = page.waitForResponse((response) => new URL(response.url()).pathname === `/api/reports/${reportId}/snapshots`
            && response.request().method() === 'POST');
        await page.getByRole('button', { name: message(locale, 'reports', 'Reports.document.snapshot'), exact: true }).click();
        const snapshotResult = await snapshotResponse;
        expect(snapshotResult.ok()).toBe(true);
        const snapshot: unknown = await snapshotResult.json();
        if (!isRecord(snapshot) || typeof snapshot.id !== 'number') throw new Error('Expected a saved report snapshot');
        await expect(page.getByText(message(locale, 'reports', 'Reports.document.snapshotCreated'), { exact: true })).toBeVisible();
        await exportButton.click();
        const snapshotDownload = page.waitForEvent('download');
        await page.getByRole('menuitem', { name: message(locale, 'reports', 'Reports.document.csv'), exact: true }).click();
        expect((await snapshotDownload).suggestedFilename()).toBe(`report-${reportId}-snapshot-${snapshot.id}.csv`);

        await exportButton.click();
        const completionResponse = page.waitForResponse((response) => new URL(response.url()).pathname === `/api/reports/${reportId}/export-completion`);
        const pngDownload = page.waitForEvent('download');
        await page.getByRole('menuitem', { name: message(locale, 'reports', 'Reports.document.png'), exact: true }).click();
        const completed = await completionResponse;
        expect(completed.status()).toBe(204);
        const submitted: unknown = completed.request().postDataJSON();
        expect(submitted).toEqual({ format: 'png' });
        expect((await pngDownload).suggestedFilename()).toBe(`${name}.png`);

        const auditResponse = await page.request.get(`/api/audit?entityType=report&entityId=${reportId}&limit=200&offset=0`, {
            headers: { 'X-Workspace-Id': String(fixture.workspaceId) },
        });
        expect(auditResponse.status()).toBe(200);
        const audit: unknown = await auditResponse.json();
        if (!Array.isArray(audit)) throw new Error('Expected report audit events');
        const exports = audit.filter((event: unknown) => isRecord(event) && event.action === 'report.export');
        expect(exports).toHaveLength(3);
        const metadata = exports.map((event: unknown) => {
            if (!isRecord(event) || event.outcome !== 'success' || !isRecord(event.changes)) throw new Error('Expected successful export evidence');
            expect(event.changes).not.toHaveProperty('name');
            return event.changes;
        });
        expect(metadata.filter((event) => event.format === 'csv' && event.snapshot === false)).toHaveLength(1);
        expect(metadata.filter((event) => event.format === 'csv' && event.snapshot === true)).toHaveLength(1);
        const png = metadata.filter((event) => event.format === 'png');
        expect(png).toHaveLength(1);
        expect(png[0]).toMatchObject({ imageCount: 1, documentCount: 1, widgetCount: 1 });
        expect(png[0]).not.toHaveProperty('rowCount');
    });
}
