import { randomUUID } from 'node:crypto';
import { expect, test } from '@playwright/test';
import { activeWorkspaceId, csrfBootstrap, registerUser, seeder } from './support/api';
import { useLocale } from './support/locale';
import { message } from './support/messages';

function recordId(record: Record<string, unknown>): number {
    if (typeof record.id !== 'number') throw new Error('Expected a record id');
    return record.id;
}

function timestamp(record: Record<string, unknown>, field: 'createdAt' | 'updatedAt'): Date {
    const raw = record[field];
    if (typeof raw !== 'string') throw new Error(`Expected ${field}`);
    const iso = raw.replace(' ', 'T');
    const date = new Date(/(?:Z|[+-]\d{2}:\d{2})$/u.test(iso) ? iso : `${iso}Z`);
    if (Number.isNaN(date.getTime())) throw new Error(`Invalid ${field}`);
    return date;
}

for (const locale of ['en', 'ja'] as const) {
    test(`record tables format timestamps in the profile zone and company preview uses its brand (${locale})`, async ({ browser }) => {
        test.setTimeout(120_000);
        const context = await browser.newContext({
            storageState: { cookies: [], origins: [] },
            timezoneId: 'America/Los_Angeles',
        });
        try {
            const unique = randomUUID();
            await registerUser(context.request, {
                username: `format-${unique}`, password: `Format-${randomUUID()}!`,
                email: `format-${unique}@example.test`, displayName: 'Formatting Test',
            });
            const csrf = await csrfBootstrap(context.request);
            const headers = { [csrf.headerName]: csrf.token };
            expect((await context.request.patch('/api/users/me', { headers, data: { timezone: 'Asia/Tokyo' } })).ok()).toBe(true);
            const write = seeder(context.request, await activeWorkspaceId(context.request), csrf);
            const contact = await write.post('/api/persons', { name: '🚀 宇宙 太郎' });
            const company = await write.post('/api/companies', { name: '株式会社サンプル' });
            const pipeline = await write.post('/api/pipelines', { name: 'Formatting pipeline' });
            const page = await context.newPage();
            await useLocale(context, locale);
            for (const timeZone of ['Asia/Tokyo', 'UTC']) {
                if (timeZone === 'UTC') {
                    expect((await context.request.patch('/api/users/me', { headers, data: { timezone: timeZone } })).ok()).toBe(true);
                }
                const formatter = new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeStyle: 'short', timeZone });
                for (const [entity, record] of [['contacts', contact], ['companies', company], ['pipelines', pipeline]] as const) {
                    await page.goto(`/records/${entity}?view=table`);
                    const table = page.getByRole('table');
                    const row = table.locator(`[data-record-row-id="${recordId(record)}"]`);
                    await expect(row).toBeVisible();
                    const columnLabels = await table.locator('thead th').allTextContents();
                    const namespace = entity === 'contacts' ? 'ContactsBrowser' : entity === 'companies' ? 'CompaniesBrowser' : 'PipelinesBrowser';
                    for (const field of ['createdAt', 'updatedAt'] as const) {
                        const label = message(locale, entity, `${namespace}.${field === 'createdAt' ? 'columnCreated' : 'columnUpdated'}`);
                        const columnIndex = columnLabels.findIndex((text) => text.trim() === label);
                        expect(columnIndex).toBeGreaterThanOrEqual(0);
                        await expect(row.locator('td').nth(columnIndex)).toHaveText(formatter.format(timestamp(record, field)));
                    }
                    await expect(table).not.toContainText(/\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}/u);
                }
            }
            await page.route('**/api/deals/metrics**', (route) => route.fulfill({
                json: { totalCount: 1234, byCurrency: [{
                    currency: 'USD', openCount: 1234, closedCount: 0, openValue: 5000,
                    closedForecast: 0, closedRevenue: 0, wonCount: 0, lostCount: 0,
                }] },
            }));
            await page.goto('/records/deals');
            const openDealsTile = page.getByText(message(locale, 'deals', 'DealsBrowser.openDeals'), { exact: true }).locator('..').locator('..');
            await expect(openDealsTile).toContainText('1,234');
            await page.goto('/records/companies');
            await page.getByRole('button', { name: message(locale, 'companies', 'CompaniesBrowser.addCompanyAriaLabel'), exact: true }).click();
            const dialog = page.getByRole('dialog');
            await dialog.locator('#company-name').fill('株式会社サンプル');
            await expect(dialog.locator('label[for="company-logo"]')).toHaveText('サ');
            await expect(dialog.locator('#company-name')).toHaveValue('株式会社サンプル');
        } finally {
            await context.close();
        }
    });
}
