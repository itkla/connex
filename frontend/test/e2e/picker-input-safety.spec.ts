import { expect, test, type Page } from '@playwright/test';
import type { Locale } from '@/i18n/config';
import { runFixture } from './support/fixtures';
import { useLocale as setLocale } from './support/locale';
import { message } from './support/messages';

function contactCopy(locale: Locale, key: string): string {
    return message(locale, 'contacts', `ContactsNewContactDialog.${key}`);
}

async function openContact(page: Page, locale: Locale) {
    await setLocale(page, locale);
    await page.goto('/records/contacts');
    await page.getByRole('button', { name: message(locale, 'contacts', 'ContactsBrowser.newAria'), exact: true }).click();
    const dialog = page.getByRole('dialog', { name: contactCopy(locale, 'dialogTitle'), exact: true });
    await expect(dialog).toBeVisible();
    return dialog;
}

for (const locale of ['en', 'ja'] as const) {
    test(`New contact Company Enter selects, preserves unmatched input and saves the company (${locale}) @mobile`, async ({ page }, testInfo) => {
        const fixture = runFixture(testInfo.project.name);
        const dialog = await openContact(page, locale);
        const name = `Picker ${locale} ${Date.now().toString(36)}`;
        await dialog.getByLabel(contactCopy(locale, 'name'), { exact: true }).fill(name);
        const company = dialog.getByRole('combobox', { name: contactCopy(locale, 'company'), exact: true });
        let creates = 0;
        page.on('request', (request) => {
            if (request.method() === 'POST' && new URL(request.url()).pathname === '/api/persons') creates += 1;
        });
        await company.fill(fixture.companyName);
        await expect(page.getByRole('option', { name: fixture.companyName, exact: true })).toBeVisible();
        await company.fill('No matching company for this picker regression');
        await expect(page.getByRole('option')).toHaveCount(0);
        await company.press('Enter');
        await expect(dialog).toBeVisible();
        await expect(company).toHaveValue('No matching company for this picker regression');
        expect(creates).toBe(0);
        await dialog.getByLabel(contactCopy(locale, 'title'), { exact: true }).fill('Keep the company draft');
        await expect(company).toHaveValue('No matching company for this picker regression');
        const create = dialog.getByRole('button', { name: contactCopy(locale, 'create'), exact: true });
        await expect(create).toBeEnabled();
        await create.click();
        await expect(company).toBeFocused();
        await expect(dialog.getByRole('alert')).toHaveText(contactCopy(locale, 'companySelectionRequired'));
        expect(creates).toBe(0);

        await company.fill('acme-rocket.example.com');
        await expect(page.getByRole('option', { name: fixture.companyName, exact: true })).toBeVisible();
        await expect(page.getByRole('option')).toHaveCount(1);
        await expect(company).toHaveAttribute('aria-activedescendant', /.+/);
        await company.press('Enter');
        await expect(company).toHaveValue(fixture.companyName);
        await expect(page.getByRole('listbox')).toHaveCount(0);
        await expect(dialog).toBeVisible();
        expect(creates).toBe(0);

        const saved = page.waitForResponse((response) => new URL(response.url()).pathname === '/api/persons'
            && response.request().method() === 'POST');
        await dialog.getByRole('button', { name: contactCopy(locale, 'create'), exact: true }).click();
        const response = await saved;
        expect(response.ok()).toBe(true);
        const record: unknown = await response.json();
        if (typeof record !== 'object' || record === null || !('id' in record) || typeof record.id !== 'number') {
            throw new Error('Created contact response has no id');
        }
        const persisted = await page.request.get(`/api/persons/${record.id}`, {
            headers: { 'X-Workspace-Id': String(fixture.workspaceId) },
        });
        expect(persisted.ok()).toBe(true);
        expect(await persisted.json()).toMatchObject({ name, companyId: fixture.companies.primary.id });
    });

    test(`Escape closes one layer and Keep editing restores the edited field (${locale}) @mobile`, async ({ page }, testInfo) => {
        const fixture = runFixture(testInfo.project.name);
        const dialog = await openContact(page, locale);
        const name = dialog.getByLabel(contactCopy(locale, 'name'), { exact: true });
        await name.fill('Keep this unsaved contact');
        const company = dialog.getByRole('combobox', { name: contactCopy(locale, 'company'), exact: true });
        await company.fill(fixture.companyName);
        await expect(page.getByRole('listbox')).toBeVisible();
        await company.press('Escape');
        await expect(page.getByRole('listbox')).toHaveCount(0);
        await expect(page.getByRole('dialog')).toHaveCount(1);
        await expect(company).toBeFocused();
        await expect(name).toHaveValue('Keep this unsaved contact');

        await name.focus();
        await name.press('Escape');
        const confirmation = page.getByRole('dialog', { name: message(locale, 'common', 'ConfirmDiscard.title') });
        const keep = confirmation.getByRole('button', { name: message(locale, 'common', 'ConfirmDiscard.keepEditing') });
        await expect(keep).toBeFocused();
        await keep.press('Enter');
        await expect(confirmation).toHaveCount(0);
        await expect(name).toBeFocused();
        await name.pressSequentially(' safely');
        await expect(name).toHaveValue('Keep this unsaved contact safely');
        const close = dialog.getByRole('button', { name: message(locale, 'common', 'UI.close'), exact: true });
        await close.focus();
        await close.press('Tab');
        await expect.poll(() => dialog.evaluate((node) => node.contains(node.ownerDocument.activeElement))).toBe(true);
        await name.focus();
        await name.press('Escape');
        await expect(keep).toBeFocused();
        await keep.press('Escape');
        await expect(confirmation).toHaveCount(0);
        await expect(name).toBeFocused();
        await name.press('Escape');
        await confirmation.getByRole('button', { name: message(locale, 'common', 'ConfirmDiscard.discard') }).click();
        await expect(page.getByRole('dialog')).toHaveCount(0);
    });
}

test('merge-field insertion restores the editor caret for immediate typing', async ({ page }) => {
    await page.goto('/library/documents/new');
    const editor = page.locator('.tiptap[contenteditable="true"]');
    await editor.fill('Prepared for  today.');
    await editor.press('End');
    for (let i = 0; i < ' today.'.length; i += 1) await editor.press('ArrowLeft');
    await page.getByRole('button', { name: 'Insert field', exact: true }).click();
    await page.getByRole('menuitem', { name: 'Company name', exact: true }).click();
    await expect(editor).toBeFocused();
    await page.keyboard.type('by our team');
    await expect(editor).toContainText('by our team today.');
    await expect(editor.locator('[data-token="company.name"]')).toHaveCount(1);
    await expect(page.getByRole('menu')).toHaveCount(0);
});
