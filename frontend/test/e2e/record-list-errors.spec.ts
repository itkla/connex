import { expect, test } from '@playwright/test';

import { runFixture } from './support/fixtures';
import { useLocale } from './support/locale';
import { message } from './support/messages';

for (const locale of ['en', 'ja'] as const) {
    for (const resource of ['contacts', 'companies'] as const) {
        test(`${locale} ${resource} load failure shows Retry before a genuinely empty list @mobile`, async ({ page }) => {
            await useLocale(page, locale);
            let failed = true;
            const endpoint = resource === 'contacts' ? '/api/persons/page' : '/api/companies/page';
            const namespace = resource === 'contacts' ? 'contacts' : 'companies';
            const browser = resource === 'contacts' ? 'ContactsBrowser' : 'CompaniesBrowser';
            await page.route(`**${endpoint}?*`, async (route) => {
                await route.fulfill(failed ? {
                    status: 503,
                    json: { message: 'List unavailable', correlationId: 'list-load-test' },
                } : {
                    status: 200,
                    json: { items: [], total: 0 },
                });
            });
            await page.goto(`/records/${resource}?view=table`);

            const main = page.locator('[data-app-main]');
            const alert = main.getByRole('alert').filter({
                hasText: message(locale, 'records', 'RecordListState.title'),
            });
            const empty = main.getByText(message(locale, namespace, `${browser}.emptyTitle`), { exact: true });
            await expect(alert).toBeVisible();
            await expect(alert).toContainText('list-load-test');
            await expect(empty).toHaveCount(0);
            await expect(main.getByText(message(locale, 'records', 'RecordsRenderView.noResults'), { exact: true })).toHaveCount(0);

            failed = false;
            await alert.getByRole('button', { name: message(locale, 'records', 'RecordListState.retry'), exact: true }).click();
            await expect(alert).toHaveCount(0);
            await expect(empty).toBeVisible();
        });
    }

    test(`${locale} contacts keep loaded rows after a failed refresh and recover with Retry @mobile`, async ({ page }, testInfo) => {
        const contact = runFixture(testInfo.project.name).contacts.peek;
        await useLocale(page, locale);
        let result: 'success' | 'failed' | 'empty' = 'success';
        await page.route('**/api/persons/page?*', async (route) => {
            if (result === 'success') {
                await route.continue();
            } else if (result === 'failed') {
                await route.fulfill({ status: 500, json: { message: 'List unavailable' } });
            } else {
                await route.fulfill({ status: 200, json: { items: [], total: 0 } });
            }
        });
        await page.goto(`/records/contacts?view=table&q=${encodeURIComponent(contact.name)}`);
        const main = page.locator('[data-app-main]');
        const row = main.locator(`[data-record-row-id="${contact.id}"]`);
        await expect(row).toBeVisible();

        result = 'failed';
        await main.getByPlaceholder(message(locale, 'contacts', 'ContactsBrowser.searchPlaceholder')).fill(`${contact.name} unavailable`);
        const alert = main.getByRole('alert').filter({
            hasText: message(locale, 'records', 'RecordListState.title'),
        });
        await expect(alert).toBeVisible();
        await expect(alert).toContainText(message(locale, 'records', 'RecordListState.refreshBody'));
        await expect(row).toBeVisible();
        await expect(main.getByText(message(locale, 'contacts', 'ContactsBrowser.emptyTitle'), { exact: true })).toHaveCount(0);
        await expect(main.getByText(message(locale, 'records', 'RecordsRenderView.noResults'), { exact: true })).toHaveCount(0);

        result = 'empty';
        await alert.getByRole('button', { name: message(locale, 'records', 'RecordListState.retry'), exact: true }).click();
        await expect(alert).toHaveCount(0);
        await expect(row).toHaveCount(0);
        await expect(main.getByText(message(locale, 'records', 'RecordsRenderView.noResults'), { exact: true })).toBeVisible();
    });
}
