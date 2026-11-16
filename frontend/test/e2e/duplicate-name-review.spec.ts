import { expect, test } from '@playwright/test';
import { randomUUID } from 'node:crypto';

import { useLocale } from './support/locale';
import { message } from './support/messages';
import { runFixture } from './support/fixtures';

for (const locale of ['en', 'ja'] as const) {
    test(`creates a separate common-name contact after explicit bounded review in ${locale}`, async ({ page }) => {
        const name = locale === 'ja' ? '山田 太郎' : 'Jordan Lee';
        const email = `${randomUUID()}@example.test`;
        const token = randomUUID().replaceAll('-', '') + randomUUID().replaceAll('-', '');
        const submitted: unknown[] = [];
        const reviewBodies: unknown[] = [];
        await useLocale(page, locale);
        await page.route('**/api/duplicate-preflight/persons', async (route) => {
            const reviewBody: unknown = route.request().postDataJSON();
            reviewBodies.push(reviewBody);
            await route.fulfill({ json: {
                recordType: 'person', truncated: true, allowSeparateCreation: true, reviewToken: token,
                candidates: [{ recordType: 'person', recordId: 991, name, ownedByActiveWorkspace: true,
                    strength: 'WEAK', matches: [{ kind: 'NAME', strength: 'WEAK', normalizedValue: name }] }],
            } });
        });
        await page.route('**/api/persons', async (route) => {
            if (route.request().method() !== 'POST') return route.continue();
            const body: unknown = route.request().postDataJSON();
            submitted.push(body);
            await route.fulfill({ json: { id: 992, name, email, tags: [] } });
        });
        await page.goto('/dashboard');
        await page.getByRole('button', { name: message(locale, 'actions', 'Actions.quickCreate.trigger'), exact: true }).click();
        await page.getByRole('option', { name: message(locale, 'actions', 'Actions.create.person'), exact: true }).click();
        await page.getByLabel(message(locale, 'contacts', 'ContactsNewContactDialog.name'), { exact: true }).fill(name);
        await page.getByLabel(message(locale, 'contacts', 'ContactsNewContactDialog.email'), { exact: true }).fill(email);
        await expect(page.getByText(message(locale, 'common', 'DuplicateWarning.nameOnlyTruncated'))).toBeVisible();
        const create = page.getByRole('button', { name: message(locale, 'contacts', 'ContactsNewContactDialog.create'), exact: true });
        await expect(create).toBeDisabled();
        await page.getByRole('checkbox', { name: message(locale, 'common', 'DuplicateWarning.acknowledge') }).check();
        await expect(create).toBeEnabled();
        await create.click();
        await expect.poll(() => submitted.length).toBe(1);
        expect(submitted[0]).toMatchObject({ name, email, duplicateReviewToken: token });
        expect(reviewBodies.at(-1)).toMatchObject({ reviewToken: token });
    });

    test(`reviews a company legal-form variant without creating or attaching it in ${locale}`, async ({ page }, testInfo) => {
        const fixture = runFixture(testInfo.project.name);
        await useLocale(page, locale);
        await page.goto('/dashboard');
        await page.getByRole('button', { name: message(locale, 'actions', 'Actions.quickCreate.trigger'), exact: true }).click();
        await page.getByRole('option', { name: message(locale, 'actions', 'Actions.create.company'), exact: true }).click();
        const companyName = page.locator('#new-company-form').getByRole('textbox', {
            name: `${message(locale, 'companies', 'CompaniesNewDialog.labelName')} *`, exact: true,
        });
        await expect(companyName).toBeEditable();
        await companyName.fill(`${fixture.companyName}${locale === 'ja' ? ' ＬＬＣ' : ' LLC'}`);
        const warning = page.getByRole('region', { name: message(locale, 'common', 'DuplicateWarning.heading') });
        await expect(warning.getByText(fixture.companyName, { exact: true })).toBeVisible();
        await expect(warning.getByText(message(locale, 'common', 'DuplicateWarning.companyNameEvidence'))).toBeVisible();
        const create = page.getByRole('button', { name: message(locale, 'companies', 'CompaniesNewDialog.create'), exact: true });
        await expect(create).toBeDisabled();
        await warning.getByRole('checkbox', { name: message(locale, 'common', 'DuplicateWarning.acknowledge') }).check();
        await expect(create).toBeEnabled();
    });
}
