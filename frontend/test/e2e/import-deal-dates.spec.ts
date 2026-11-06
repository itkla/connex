import { randomUUID } from 'node:crypto';
import { createTranslator } from 'next-intl';
import { expect, test, type Page } from '@playwright/test';
import type { Locale } from '@/i18n/config';

import en from '@/messages/en/importExport.json';
import ja from '@/messages/ja/importExport.json';

import { activeWorkspaceId, csrfBootstrap, registerUser, seeder } from './support/api';
import { useLocale } from './support/locale';
import { message } from './support/messages';

test.use({ storageState: { cookies: [], origins: [] } });
test.setTimeout(120_000);

function isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === 'object' && value !== null;
}

/** Completes the normal passkey ceremony required to change the reporting timezone. */
async function enrolReportingAdministrator(page: Page, password: string, locale: Locale): Promise<void> {
    const cdp = await page.context().newCDPSession(page);
    await cdp.send('WebAuthn.enable');
    try {
        const { authenticatorId } = await cdp.send('WebAuthn.addVirtualAuthenticator', { options: {
            protocol: 'ctap2', ctap2Version: 'ctap2_1', transport: 'internal',
            hasResidentKey: true, hasUserVerification: true, isUserVerified: true,
            automaticPresenceSimulation: true,
        } });
        try {
            await page.goto('/settings/personal/security');
            await page.getByRole('button', { name: message(locale, 'account', 'AccountSecurity.add'), exact: true }).first().click();
            const dialog = page.getByRole('dialog', { name: message(locale, 'account', 'AccountSecurity.passwordTitle') });
            await dialog.getByLabel(message(locale, 'account', 'AccountSecurity.passwordLabel')).fill(password);
            await dialog.getByRole('button', { name: message(locale, 'account', 'AccountSecurity.continue'), exact: true }).click();
            await expect(page.getByText(message(locale, 'account', 'AccountSecurity.added'), { exact: true })).toBeVisible();
        } finally {
            await cdp.send('WebAuthn.removeVirtualAuthenticator', { authenticatorId });
        }
    } finally {
        await cdp.send('WebAuthn.disable');
        await cdp.detach();
    }
}

for (const locale of ['en', 'ja'] as const) {
    test(`reviews missing close dates and imports mapped deal history in ${locale}`, async ({ page }) => {
        const key = `dates${randomUUID().replaceAll('-', '').slice(0, 12)}`;
        const password = `DatesHarness!${key}A1`;
        await registerUser(page.request, {
            username: key,
            password,
            email: `${key}@example.invalid`,
        });
        const workspaceId = await activeWorkspaceId(page.request);
        const csrf = await csrfBootstrap(page.request);
        const timezoneResponse = await page.request.patch('/api/users/me', {
            headers: { 'X-Workspace-Id': String(workspaceId), [csrf.headerName]: csrf.token },
            data: { timezone: 'Asia/Tokyo' },
        });
        expect(timezoneResponse.status()).toBe(200);
        const membershipsResponse = await page.request.get('/api/workspaces');
        expect(membershipsResponse.status()).toBe(200);
        const memberships: unknown = await membershipsResponse.json();
        if (!isRecord(memberships) || !Array.isArray(memberships.workspaces)) throw new Error('Workspace list missing');
        const workspaces: unknown[] = memberships.workspaces;
        const workspace = workspaces.find((entry) => isRecord(entry) && entry.id === workspaceId);
        if (!isRecord(workspace) || typeof workspace.name !== 'string' || typeof workspace.identityVersion !== 'number'
            || (workspace.timezone != null && typeof workspace.timezone !== 'string')) throw new Error('Workspace identity missing');
        const reportingPayload = { name: workspace.name, timezone: 'Asia/Tokyo', expectedName: workspace.name,
            expectedTimezone: workspace.timezone ?? null, expectedIdentityVersion: workspace.identityVersion };
        const refused = await page.request.patch(`/api/workspaces/${workspaceId}`, {
            headers: { 'X-Workspace-Id': String(workspaceId), [csrf.headerName]: csrf.token },
            data: reportingPayload,
        });
        expect(refused.status()).toBe(403);
        expect(await refused.json()).toMatchObject({ code: 'RECENT_AUTHENTICATION_REQUIRED' });
        await useLocale(page, locale);
        await enrolReportingAdministrator(page, password, locale);
        const steppedUpCsrf = await csrfBootstrap(page.request);
        const reportingResponse = await page.request.patch(`/api/workspaces/${workspaceId}`, {
            headers: { 'X-Workspace-Id': String(workspaceId), [steppedUpCsrf.headerName]: steppedUpCsrf.token },
            data: reportingPayload,
        });
        expect(reportingResponse.status()).toBe(200);
        expect(await reportingResponse.json()).toMatchObject({ id: workspaceId, timezone: 'Asia/Tokyo' });
        const seed = seeder(page.request, workspaceId, steppedUpCsrf);
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
        await page.goto('/records/deals?view=table');
        await page.getByRole('button', { name: t('moreActions'), exact: true }).click();
        await page.getByRole('menuitem', { name: t('openImport'), exact: true }).click();
        const dialog = page.getByRole('dialog');
        await expect(dialog.getByRole('heading', { name: t('title.deals') })).toBeVisible();
        const futureCloseDate = `${new Date().getUTCFullYear() + 1}-01-01`;
        await dialog.locator('input[type="file"]').setInputFiles({
            name: 'historical-deals.csv',
            mimeType: 'text/csv',
            buffer: Buffer.from([
                'Name,Value,Pipeline,Stage,Close history,Creation history,Expected close date',
                ...Array.from({ length: 100 }, () => 'Historic won,300,Historical pipeline,Won,2025/03/01,2025-02-01,2026-11-01'),
                'Missing won,100,Historical pipeline,Won,,,',
                'Missing lost,50,Historical pipeline,Lost,,,',
                `Future won,25,Historical pipeline,Won,${futureCloseDate},2025-02-01,2026-11-01`,
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
                    closedAt: '2025-02-28T15:00:00Z', createdAt: '2025-01-31T15:00:00Z', usesImportTime: false,
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
            name: 'Historic won', closedAt: '2025-02-28 15:00:00', createdAt: '2025-01-31 15:00:00',
        })]) });
        if (!isRecord(records) || !Array.isArray(records.items)) throw new Error('Deal page is missing');
        const items: unknown[] = records.items;
        const historical = items.find((item) => isRecord(item) && item.name === 'Historic won');
        if (!isRecord(historical) || typeof historical.id !== 'number') throw new Error('Historic deal id is missing');
        const revenue = await page.request.get('/api/deals/revenue-timeseries?currency=USD&timezone=Asia%2FTokyo', {
            headers: { 'X-Workspace-Id': String(workspaceId) },
        });
        expect(revenue.status()).toBe(200);
        const chart: unknown = await revenue.json();
        expect(chart).toMatchObject({ closed: expect.arrayContaining([{ year: 2025, month: 3, total: 300 }]) });
        const closeDate = new Intl.DateTimeFormat(locale, { dateStyle: 'long', timeZone: 'Asia/Tokyo' })
            .format(new Date('2025-02-28T15:00:00Z'));
        for (const profileTimezone of ['Asia/Tokyo', 'America/New_York']) {
            const profileResponse = await page.request.patch('/api/users/me', {
                headers: { [steppedUpCsrf.headerName]: steppedUpCsrf.token }, data: { timezone: profileTimezone },
            });
            expect(profileResponse.status()).toBe(200);
            await page.goto(`/records/deals/${historical.id}`);
            await expect(page.getByText(closeDate, { exact: true })).toHaveCount(3);
            await expect(page.getByText(closeDate, { exact: true }).filter({ visible: true }).first()).toBeVisible();
            await page.getByRole('button', { name: message(locale, 'deals', 'DealsActionsMenu.edit'), exact: true }).click();
            await expect(page.getByLabel(message(locale, 'deals', 'DealsQuickEditSheet.closedAt'), { exact: true }))
                .toHaveValue('2025-03-01T00:00');
            await page.goto('/records/deals?view=table&q=Historic%20won');
            const record = page.locator(`[data-record-row-id="${historical.id}"]`);
            await expect(record.getByRole('cell', { name: closeDate, exact: true })).toBeVisible();
            await record.getByRole('cell', { name: 'Historic won', exact: true }).click();
            const peek = page.locator('[data-record-peek]');
            await expect(peek).toBeVisible();
            const closeFact = peek.getByText(message(locale, 'records', 'RecordPeek.closedAt'), { exact: true }).locator('..');
            await expect(closeFact.getByText(closeDate, { exact: true })).toBeVisible();
        }
        expect(pageErrors).toEqual([]);
    });
}
