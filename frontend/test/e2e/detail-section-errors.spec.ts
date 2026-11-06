import { expect } from '@playwright/test';

import { test } from './support/capture.fixture';
import { LOCALE_COOKIE } from '@/i18n/config';
import { message } from './support/messages';

for (const locale of ['en', 'ja'] as const) {
    for (const status of [503, 403]) {
        test(`${locale} company detail distinguishes HTTP ${status} from empty sections and retries @mobile`, async ({ page, baseURL }) => {
            if (!baseURL) throw new Error('Detail fixture address is missing');
            await page.context().addCookies([
                { name: LOCALE_COOKIE, value: locale, url: baseURL },
                { name: 'e2e_detail', value: String(status), url: baseURL },
            ]);
            await page.goto('/records/companies/1');
            const contacts = page.locator('#company-detail-related');
            const deals = page.locator('#company-detail-activity');
            const title = message(locale, 'records', `RecordListState.${status === 403 ? 'sectionDeniedTitle' : 'sectionTitle'}`);
            const contactsName = message(locale, 'companies', 'CompaniesContactsGrid.contacts');
            const dealsName = message(locale, 'companies', 'CompaniesDetail.deals');
            await expect(page.getByRole('heading', { name: 'Detail recovery company', exact: true })).toBeVisible();
            await expect(contacts.getByRole('alert')).toContainText(title.replace('{section}', contactsName));
            await expect(deals.getByRole('alert')).toContainText(title.replace('{section}', dealsName));
            await expect(contacts).toContainText('detail-load-test');
            const emptyContacts = message(locale, 'companies', 'CompaniesContactsGrid.noContacts');
            const emptyDeals = message(locale, 'records', 'RecordsPipelineCard.emptyActive');
            await expect(contacts.getByText(emptyContacts, { exact: true })).toHaveCount(0);
            await expect(deals.getByText(emptyDeals, { exact: true })).toHaveCount(0);

            await page.context().clearCookies({ name: 'e2e_detail' });
            await contacts.getByRole('button', { name: message(locale, 'records', 'RecordListState.retry'), exact: true }).click();
            await expect(contacts.getByRole('alert')).toHaveCount(0);
            await expect(deals.getByRole('alert')).toHaveCount(0);
            await expect(contacts.getByText(emptyContacts, { exact: true })).toBeVisible();
            await expect(deals.getByText(emptyDeals, { exact: true })).toBeVisible();
        });
    }
}
