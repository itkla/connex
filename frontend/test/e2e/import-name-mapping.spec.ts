import { randomUUID } from 'node:crypto';
import { createTranslator } from 'next-intl';
import { expect, test, type Locator, type Page } from '@playwright/test';

import en from '@/messages/en/importExport.json';
import ja from '@/messages/ja/importExport.json';
import { activeWorkspaceId, registerUser } from './support/api';
import { useLocale } from './support/locale';

test.use({ storageState: { cookies: [], origins: [] } });
test.setTimeout(120_000);

function isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === 'object' && value !== null && !Array.isArray(value);
}

async function choose(page: Page, trigger: Locator, label: string): Promise<void> {
    await trigger.click();
    await page.getByRole('option', { name: label, exact: true }).click();
}

for (const locale of ['en', 'ja'] as const) {
    test(`composes complete names and keeps uncertain headers unmapped in ${locale} @mobile`, async ({ page }) => {
        const key = `names${randomUUID().replaceAll('-', '').slice(0, 12)}`;
        await registerUser(page.request, {
            username: key, password: `ImportNames!${key}A1`, email: `${key}@example.invalid`,
        });
        const workspaceId = await activeWorkspaceId(page.request);
        await useLocale(page, locale);
        await page.emulateMedia({ colorScheme: locale === 'ja' ? 'dark' : 'light' });
        const t = createTranslator({ locale, messages: locale === 'en' ? en : ja, namespace: 'importExport' });
        await page.goto('/records/contacts?view=table');
        await page.getByRole('button', { name: t('moreActions'), exact: true }).click();
        await page.getByRole('menuitem', { name: t('openImport'), exact: true }).click();
        const dialog = page.getByRole('dialog');
        const headers = locale === 'ja'
            ? ['名', '姓', 'メールアドレス', '会社名', '役職', '電話番号', 'Lifecycle Stage', 'disqualificationReasonLabel']
            : ['First Name', 'Last Name', 'Email', 'Company Name', 'Job Title', 'Phone Number', 'Lifecycle Stage', 'disqualificationReasonLabel'];
        const source = locale === 'ja'
            ? ['太郎,山田,one@example.invalid,㈱テスト,部長,03-1234-5678,Marketing Qualified Lead,Not a tag',
                ',佐藤,two@example.invalid,㈱テスト,課長,,Customer,Also not a tag']
            : ['Aiko,Mori,one@example.invalid,Example Company,Director,03-1234-5678,Marketing Qualified Lead,Not a tag',
                ',Ito,two@example.invalid,Example Company,Manager,,Customer,Also not a tag'];
        await dialog.locator('input[type="file"]').setInputFiles({
            name: 'split-names.csv', mimeType: 'text/csv', buffer: Buffer.from([headers.join(','), ...source].join('\n')),
        });
        const mapColumn = (column: string) => dialog.getByRole('combobox', { name: t('mapColumn', { column }), exact: true });
        for (const [index, field] of [[0, 'givenName'], [1, 'familyName'], [2, 'email'], [3, 'company'], [4, 'title'], [5, 'phone']] as const) {
            await expect(mapColumn(headers[index])).toHaveText(t(`fields.${field}`));
        }
        await expect(mapColumn('Lifecycle Stage')).toHaveText(t('ignore'));
        await expect(mapColumn('disqualificationReasonLabel')).toHaveText(t('ignore'));
        const originalName = locale === 'ja' ? '山田 太郎' : 'Aiko Mori';
        const reversedName = locale === 'ja' ? '太郎 山田' : 'Mori Aiko';
        const familyOnly = locale === 'ja' ? '佐藤' : 'Ito';
        const nameOrder = () => dialog.getByRole('combobox', { name: t('nameOrder'), exact: true });
        await expect(dialog.getByRole('status')).toHaveText(t('namePreview', { names: `${originalName}, ${familyOnly}` }));
        await choose(page, nameOrder(), t(locale === 'ja' ? 'nameOrderGivenFamily' : 'nameOrderFamilyGiven'));
        await expect(dialog.getByRole('status')).toHaveText(t('namePreview', { names: `${reversedName}, ${familyOnly}` }));

        const firstPreview = page.waitForResponse((response) => response.url().endsWith('/api/imports/persons/preview') && response.request().method() === 'POST');
        await dialog.getByRole('button', { name: t('next'), exact: true }).click();
        expect((await firstPreview).status()).toBe(200);
        await expect(dialog.getByText(reversedName, { exact: true })).toBeVisible();
        await dialog.getByRole('button', { name: t('back'), exact: true }).click();
        await choose(page, nameOrder(), t(locale === 'ja' ? 'nameOrderFamilyGiven' : 'nameOrderGivenFamily'));

        await choose(page, mapColumn(headers[0]), t('fields.name'));
        await expect(dialog.getByRole('alert')).toHaveText(t('nameMappingConflict'));
        await expect(dialog.getByRole('button', { name: t('next'), exact: true })).toBeDisabled();
        await choose(page, mapColumn(headers[0]), t('fields.givenName'));
        await expect(dialog.getByRole('alert')).toHaveCount(0);

        const previewResponse = page.waitForResponse((response) => response.url().endsWith('/api/imports/persons/preview') && response.request().method() === 'POST');
        await dialog.getByRole('button', { name: t('next'), exact: true }).click();
        const preview = await previewResponse;
        expect(preview.status()).toBe(200);
        const previewPayload: unknown = preview.request().postDataJSON();
        if (!isRecord(previewPayload)) {
            throw new Error('Import preview payload must be an object');
        }
        await expect(dialog.getByText(originalName, { exact: true })).toBeVisible();
        await expect(dialog.getByText(familyOnly, { exact: true })).toBeVisible();
        await expect(dialog.getByText(reversedName, { exact: true })).toHaveCount(0);
        const commitResponse = page.waitForResponse((response) => response.url().endsWith('/api/imports/persons') && response.request().method() === 'POST');
        await dialog.getByRole('button', { name: t('import'), exact: true }).click();
        const committed = await commitResponse;
        expect(committed.status()).toBe(200);
        expect(committed.request().postDataJSON()).toMatchObject(previewPayload);
        expect(await committed.json()).toMatchObject({ created: 2, failed: [] });
        await expect(dialog.getByText(t('doneTitle'), { exact: true })).toBeVisible();

        const pin = { 'X-Workspace-Id': String(workspaceId) };
        const people = await page.request.get('/api/persons/page?page=1&size=25', { headers: pin });
        expect(people.status()).toBe(200);
        expect(await people.json()).toMatchObject({ total: 2, items: expect.arrayContaining([
            expect.objectContaining({ name: originalName, email: 'one@example.invalid', title: locale === 'ja' ? '部長' : 'Director' }),
            expect.objectContaining({ name: familyOnly, email: 'two@example.invalid' }),
        ]) });
        const companies = await page.request.get('/api/companies/page?page=1&size=25', { headers: pin });
        expect(companies.status()).toBe(200);
        expect(await companies.json()).toMatchObject({ total: 1, items: [expect.objectContaining({ name: locale === 'ja' ? '㈱テスト' : 'Example Company' })] });
        const tags = await page.request.get('/api/tags', { headers: pin });
        expect(tags.status()).toBe(200);
        expect(await tags.json()).toEqual([]);
    });
}

test('suggests company names and domains for company records without guessing lifecycle tags', async ({ page }) => {
    const key = `companymap${randomUUID().replaceAll('-', '').slice(0, 12)}`;
    await registerUser(page.request, { username: key, password: `CompanyMap!${key}A1`, email: `${key}@example.invalid` });
    await useLocale(page, 'en');
    const t = createTranslator({ locale: 'en', messages: en, namespace: 'importExport' });
    await page.goto('/records/companies?view=table');
    await page.getByRole('button', { name: t('moreActions'), exact: true }).click();
    await page.getByRole('menuitem', { name: t('openImport'), exact: true }).click();
    const dialog = page.getByRole('dialog');
    await dialog.locator('input[type="file"]').setInputFiles({
        name: 'companies.csv', mimeType: 'text/csv',
        buffer: Buffer.from('Company Name,Company Domain Name,Lifecycle Stage\nExample Company,example.invalid,Customer\n'),
    });
    for (const [column, field] of [['Company Name', 'name'], ['Company Domain Name', 'website']] as const) {
        await expect(dialog.getByRole('combobox', { name: t('mapColumn', { column }), exact: true })).toHaveText(t(`fields.${field}`));
    }
    await expect(dialog.getByRole('combobox', { name: t('mapColumn', { column: 'Lifecycle Stage' }), exact: true })).toHaveText(t('ignore'));
});
