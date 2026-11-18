import { randomUUID } from 'node:crypto';
import { expect, test } from '@playwright/test';
import { activeWorkspaceId, csrfBootstrap, registerUser, seeder } from './support/api';
import { message } from './support/messages';
import { useLocale } from './support/locale';

test.use({ storageState: { cookies: [], origins: [] } });

for (const locale of ['en', 'ja'] as const) {
    test(`Employment history changes company through the existing dialog (${locale}) @mobile`, async ({ page }) => {
        test.setTimeout(120_000);
        const suffix = randomUUID().replaceAll('-', '').slice(0, 12);
        await registerUser(page.request, {
            username: `employment_${suffix}`, password: `Employment!${suffix}A1`, email: `employment_${suffix}@example.test`,
        });
        const workspaceId = await activeWorkspaceId(page.request);
        const seed = seeder(page.request, workspaceId, await csrfBootstrap(page.request));
        const firstName = `First employer ${suffix}`;
        const nextName = `Next employer ${suffix}`;
        const first = await seed.post('/api/companies', { name: firstName });
        const next = await seed.post('/api/companies', { name: nextName });
        const contact = await seed.post('/api/persons', { name: `Employment contact ${suffix}`, companyId: first.id });
        if (typeof contact.id !== 'number' || typeof next.id !== 'number') throw new Error('Missing record id');
        await useLocale(page, locale);
        await page.goto(`/records/contacts/${contact.id}`);
        const history = page.getByRole('region', { name: message(locale, 'contacts', 'ContactsPage.employmentHistory'), exact: true });
        await history.getByRole('button', { name: message(locale, 'contacts', 'ContactsActionsMenu.changeCompany'), exact: true }).click();
        const dialog = page.getByRole('dialog', { name: message(locale, 'contacts', 'ContactsChangeCompanyDialog.titleSingle'), exact: true });
        const company = dialog.getByRole('combobox', { name: message(locale, 'contacts', 'ContactsChangeCompanyDialog.companyLabel'), exact: true });
        await expect(company).toBeEnabled();
        await company.fill(nextName);
        await page.getByRole('option', { name: nextName, exact: true }).click();
        const saved = page.waitForResponse((response) => response.url().endsWith(`/api/persons/${contact.id}`)
            && response.request().method() === 'PUT');
        await dialog.getByRole('button', { name: message(locale, 'contacts', 'ContactsChangeCompanyDialog.save'), exact: true }).click();
        expect((await saved).status()).toBe(200);
        await expect(dialog).not.toBeVisible();
        await expect(history.getByRole('link', { name: nextName, exact: true })).toBeVisible();
        await expect(history.getByRole('link', { name: firstName, exact: true })).toBeVisible();
        await expect(history.getByText(message(locale, 'contacts', 'ContactsPage.current'), { exact: true })).toHaveCount(1);
    });
}
