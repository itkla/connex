import { expect, test } from '@playwright/test';
import { runFixture } from './support/fixtures';
import { useLocale } from './support/locale';
import { message } from './support/messages';

for (const locale of ['en', 'ja'] as const) {
    test(`ACT-32 ${locale} company selection names the plural and quick edit describes fields`, async ({ page }, testInfo) => {
        const company = runFixture(testInfo.project.name).companies.primary;
        await useLocale(page, locale);
        await page.goto(`/records/companies?view=table&q=${encodeURIComponent(company.name)}`);
        const main = page.locator('[data-app-main]');
        const row = main.locator(`[data-record-row-id="${company.id}"]`);
        await expect(row).toBeVisible();

        const selectAll = main.getByRole('checkbox', {
            name: locale === 'en' ? 'Select all companies' : 'すべての 会社 を選択',
            exact: true,
        });
        await selectAll.check();
        await expect(row.getByRole('checkbox')).toBeChecked();
        await main.getByRole('button', {
            name: message(locale, 'companies', 'CompaniesBrowser.quickEdit'), exact: true,
        }).click();

        const sheet = page.getByRole('dialog');
        await expect(sheet).toBeVisible();
        await expect(sheet).toContainText(locale === 'en'
            ? 'Only changed fields will be saved.'
            : '変更した項目のみ保存されます。');
        await sheet.getByRole('button', {
            name: message(locale, 'companies', 'CompaniesQuickEditSheet.cancel'), exact: true,
        }).click();
        await expect(sheet).toBeHidden();
    });
}
