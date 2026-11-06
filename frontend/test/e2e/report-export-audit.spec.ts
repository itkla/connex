import { randomUUID } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import type { Response } from '@playwright/test';
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
        const snapshotExportPath = `/api/reports/${reportId}/snapshots/${snapshot.id}/export.csv`;
        const snapshotExportMethods: string[] = [];
        page.on('request', (request) => {
            if (new URL(request.url()).pathname === snapshotExportPath) snapshotExportMethods.push(request.method());
        });
        await exportButton.click();
        const [snapshotExported, snapshotDownload] = await Promise.all([
            page.waitForResponse((response) => new URL(response.url()).pathname === snapshotExportPath
                && response.request().method() === 'POST' && response.status() === 200),
            page.waitForEvent('download'),
            page.getByRole('menuitem', { name: message(locale, 'reports', 'Reports.document.csv'), exact: true }).click(),
        ]);
        expect(snapshotExported.ok()).toBe(true);
        expect(snapshotDownload.suggestedFilename()).toBe(`report-${reportId}-snapshot-${snapshot.id}.csv`);

        const completionPath = `/api/reports/${reportId}/export-completion`;
        const completions: Response[] = [];
        let completionAttempts = 0;
        let pngDownloads = 0;
        page.on('request', (request) => {
            if (new URL(request.url()).pathname === completionPath && request.method() === 'POST') completionAttempts += 1;
        });
        page.on('response', (response) => {
            if (new URL(response.url()).pathname === completionPath && response.request().method() === 'POST') completions.push(response);
        });
        page.on('download', (download) => {
            if (download.suggestedFilename().endsWith('.png')) pngDownloads += 1;
        });
        await exportButton.click();
        const [completed, pngDownload] = await Promise.all([
            page.waitForResponse((response) => new URL(response.url()).pathname === completionPath
                && response.request().method() === 'POST' && response.status() === 204),
            page.waitForEvent('download'),
            page.getByRole('menuitem', { name: message(locale, 'reports', 'Reports.document.png'), exact: true }).click(),
        ]);
        const submitted: unknown = completed.request().postDataJSON();
        expect(submitted).toEqual({ format: 'png' });
        expect(pngDownload.suggestedFilename()).toBe(`${name}.png`);
        const pngPath = await pngDownload.path();
        if (pngPath === null) throw new Error('PNG download was not saved');
        const pngBytes = await readFile(pngPath);
        expect(pngBytes.subarray(0, 8)).toEqual(Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]));
        expect(pngBytes.readUInt32BE(16)).toBeGreaterThan(0);
        expect(pngBytes.readUInt32BE(20)).toBeGreaterThan(0);
        expect(pngBytes.readUInt32BE(16)).toBeLessThanOrEqual(16384);
        expect(pngBytes.readUInt32BE(20)).toBeLessThanOrEqual(16384);
        expect(completions.filter((response) => response.status() === 204)).toHaveLength(1);

        const attemptsBeforeFailure = completionAttempts;
        await page.evaluate(() => {
            const original = HTMLCanvasElement.prototype.toBlob;
            HTMLCanvasElement.prototype.toBlob = function failNextReportPng(callback) {
                HTMLCanvasElement.prototype.toBlob = original;
                callback(null);
            };
        });
        await exportButton.click();
        await page.getByRole('menuitem', { name: message(locale, 'reports', 'Reports.document.png'), exact: true }).click();
        await expect(page.getByText(message(locale, 'reports', 'Reports.document.pngFailed'), { exact: true })).toBeVisible();
        await expect(exportButton).toBeEnabled();
        expect(await page.evaluate(() => HTMLCanvasElement.prototype.toBlob.name)).not.toBe('failNextReportPng');

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
        expect(snapshotExportMethods).toEqual(['POST']);
        const png = metadata.filter((event) => event.format === 'png');
        expect(png).toHaveLength(1);
        expect(png[0]).toMatchObject({ imageCount: 1, documentCount: 1, widgetCount: 1 });
        expect(png[0]).not.toHaveProperty('rowCount');
        expect(completionAttempts).toBe(attemptsBeforeFailure);
        expect(completions.filter((response) => response.status() === 204)).toHaveLength(1);
        expect(pngDownloads).toBe(1);
    });
}
