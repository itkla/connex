import { randomUUID } from 'node:crypto';
import { createTranslator } from 'next-intl';
import { expect, test } from '@playwright/test';

import en from '@/messages/en/importExport.json';
import ja from '@/messages/ja/importExport.json';

import { activeWorkspaceId, csrfBootstrap, registerUser, seeder } from './support/api';
import { useLocale } from './support/locale';

test.use({ storageState: { cookies: [], origins: [] } });
test.setTimeout(120_000);

for (const locale of ['en', 'ja'] as const) {
    test(`reviews missing close dates and imports mapped deal history in ${locale}`, async ({ page }) => {
        const key = `dates${randomUUID().replaceAll('-', '').slice(0, 12)}`;
        await registerUser(page.request, {
            username: key,
            password: `DatesHarness!${key}A1`,
            email: `${key}@example.invalid`,
        });
        const workspaceId = await activeWorkspaceId(page.request);
        const seed = seeder(page.request, workspaceId, await csrfBootstrap(page.request));
        const pipeline = await seed.post('/api/pipelines', { name: 'Historical pipeline' });
        if (typeof pipeline.id !== 'number') throw new Error('Pipeline id is missing');
        await seed.post(`/api/pipelines/${pipeline.id}/stages`, {
            name: 'Won', position: 0, success: true, failure: false,
        });
        await seed.post(`/api/pipelines/${pipeline.id}/stages`, {
            name: 'Lost', position: 1, success: false, failure: true,
        });
        const t = createTranslator({
            locale, messages: locale === 'en' ? en : ja, namespace: 'importExport',
        });
        const pageErrors: string[] = [];
        page.on('pageerror', (error) => pageErrors.push(error.message));
        await useLocale(page, locale);
        await page.goto('/records/deals?view=table');
        await page.getByRole('button', { name: t('moreActions'), exact: true }).click();
        await page.getByRole('menuitem', { name: t('openImport'), exact: true }).click();
        const dialog = page.getByRole('dialog');
        await expect(dialog.getByRole('heading', { name: t('title.deals') })).toBeVisible();
        await dialog.locator('input[type="file"]').setInputFiles({
            name: 'historical-deals.csv',
            mimeType: 'text/csv',
            buffer: Buffer.from([
                'Name,Value,Pipeline,Stage,Close history,Creation history',
                ...Array.from({ length: 100 }, () => 'Historic won,300,Historical pipeline,Won,2025/03/01,2025-02-01'),
                'Missing won,100,Historical pipeline,Won,,',
                'Missing lost,50,Historical pipeline,Lost,,',
                'Future won,25,Historical pipeline,Won,2099-01-01,2025-02-01',
            ].join('\n')),
        });
        for (const [header, field] of [['Close history', 'closedAt'], ['Creation history', 'createdAt']] as const) {
            const mappingRow = dialog.getByText(header, { exact: true }).locator('..').locator('..');
            await mappingRow.getByRole('combobox').first().click();
            await page.getByRole('option', { name: t(`fields.${field}`), exact: true }).click();
        }
        const previewResponse = page.waitForResponse((response) =>
            response.url().endsWith('/api/imports/deals/preview') && response.request().method() === 'POST');
        await dialog.getByRole('button', { name: t('next'), exact: true }).click();
        const response = await previewResponse;
        expect(response.status()).toBe(200);
        const preview: unknown = await response.json();
        expect(preview).toMatchObject({
            invalid: 0, toCreate: 4,
            rows: expect.arrayContaining([expect.objectContaining({
                rowIndex: 0,
                dealDates: expect.objectContaining({
                    closedAt: '2025-03-01T00:00:00Z', createdAt: '2025-02-01T00:00:00Z', usesImportTime: false,
                }),
            })]),
        });
        await expect(dialog.getByText(t('missingCloseDates', { count: 2 }), { exact: true })).toBeVisible();
        await expect(dialog.getByText(t('futureCloseDate'), { exact: true })).toBeVisible();
        const historicalRow = dialog.getByRole('row').filter({ hasText: 'Historic won' }).first();
        await expect(historicalRow.getByText(new RegExp(t('fields.closedAt')))).toContainText('2025');
        await expect(historicalRow.getByText(new RegExp(t('fields.createdAt')))).toContainText('2025');
        await expect(dialog.getByRole('button', { name: t('import'), exact: true })).toBeEnabled();
        const commitResponse = page.waitForResponse((result) =>
            result.url().endsWith('/api/imports/deals') && result.request().method() === 'POST');
        await dialog.getByRole('button', { name: t('import'), exact: true }).click();
        const committed = await commitResponse;
        expect(committed.status()).toBe(200);
        expect(await committed.json()).toMatchObject({ created: 4, failed: [] });
        const submitted: unknown = committed.request().postDataJSON();
        expect(submitted).toMatchObject({
            mapping: expect.arrayContaining([
                { column: 'Close history', field: 'closedAt' },
                { column: 'Creation history', field: 'createdAt' },
            ]),
            duplicateReviewProof: expect.stringMatching(/^[0-9a-f]{64}$/),
        });
        await expect(dialog.getByText(t('doneTitle'), { exact: true })).toBeVisible();
        const stored = await page.request.get('/api/deals/page?page=1&size=25', {
            headers: { 'X-Workspace-Id': String(workspaceId) },
        });
        expect(stored.status()).toBe(200);
        const records: unknown = await stored.json();
        expect(records).toMatchObject({ items: expect.arrayContaining([expect.objectContaining({
            name: 'Historic won', closedAt: '2025-03-01 00:00:00', createdAt: '2025-02-01 00:00:00',
        })]) });
        expect(pageErrors).toEqual([]);
    });
}
