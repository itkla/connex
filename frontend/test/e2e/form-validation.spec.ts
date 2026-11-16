import { expect, test } from '@playwright/test';
import { message } from './support/messages';
import { useLocale } from './support/locale';
import { runFixture } from './support/fixtures';

for (const locale of ['en', 'ja'] as const) {
    test.describe(`auth validation in ${locale}`, () => {
        test.use({ storageState: { cookies: [], origins: [] } });
        test('required fields and server errors preserve focus and localized copy @mobile', async ({ page }) => {
            await useLocale(page, locale);
            let requests = 0;
            const pending = Promise.withResolvers<void>();
            await page.route('**/api/auth/register', async (route) => {
                requests++;
                await pending.promise;
                await route.fulfill({ status: 400, contentType: 'application/json', body: JSON.stringify({
                    code: 'VALIDATION_FAILED', fieldErrors: { password: 'raw password', username: 'raw username' },
                    fieldErrorCodes: { username: 'Pattern', password: 'Size' },
                }) });
            });
            await page.goto('/auth/register');
            const submit = page.getByRole('button', { name: message(locale, 'auth', 'AuthRegister.submitLabel'), exact: true });
            await submit.click();
            await expect(page.locator('#register-username')).toBeFocused();
            await expect(page.locator('form [aria-invalid="true"]')).toHaveCount(4);
            await expect(page.locator('form [role="alert"]')).toHaveCount(0);
            await expect(page.locator('form [aria-live="polite"]')).toHaveText(message(locale, 'auth', 'AuthForm.formHasErrors'));
            expect(requests).toBe(0);
            await page.locator('#register-username').fill('validation_probe');
            await page.locator('#register-email').fill('validation@example.test');
            await page.locator('#register-displayName').fill('Validation probe');
            await page.locator('#register-password').fill('Aa12345#');
            await submit.click();
            await expect(submit).toHaveAttribute('aria-busy', 'true');
            expect(await submit.getAttribute('disabled')).toBeNull();
            await expect(submit).toBeFocused();
            pending.resolve();
            await expect(page.locator('#register-username')).toBeFocused();
            await expect(page.locator('#register-username-error')).toHaveText(message(locale, 'auth', 'AuthForm.usernameFormat'));
            await expect(submit).toHaveText(message(locale, 'auth', 'AuthRegister.submitLabel'));
            expect(requests).toBe(1);
        });
    });

    test(`activity subject uses inline errors and a 255-character limit in ${locale}`, async ({ page }, testInfo) => {
        const contact = runFixture(testInfo.project.name).contacts.activity;
        await useLocale(page, locale);
        await page.goto(`/records/contacts/${contact.id}`);
        await page.getByRole('button', { name: message(locale, 'activity', 'ActivityCreateDialog.titleCreate'), exact: true }).first().click();
        const subject = page.locator('#activity-subject');
        await expect(subject).toHaveAttribute('maxlength', '255');
        await page.getByRole('button', { name: message(locale, 'activity', 'ActivityCreateDialog.create'), exact: true }).click();
        await expect(subject).toBeFocused();
        await expect(page.locator('#activity-subject-error')).toHaveText(message(locale, 'activity', 'ActivityCreateDialog.toastSubjectRequired'));
        await subject.fill('a'.repeat(255));
        await expect(page.locator('#activity-subject-count')).toContainText('255/255');
        await page.route('**/api/activities', async (route) => {
            if (route.request().method() !== 'POST') return route.continue();
            await route.fulfill({ status: 400, contentType: 'application/json', body: JSON.stringify({
                code: 'VALIDATION_FAILED', fieldErrors: { subject: 'size must be between 0 and 255' }, fieldErrorCodes: { subject: 'Size' },
            }) });
        });
        await page.getByRole('button', { name: message(locale, 'activity', 'ActivityCreateDialog.create'), exact: true }).click();
        await expect(subject).toBeFocused();
        await expect(page.locator('#activity-subject-error')).toHaveText(message(locale, 'activity', 'ActivityCreateDialog.subjectTooLong'));
    });

    for (const kind of ['contact', 'company'] as const) {
        test(`${kind} creation requires a name inline in ${locale}`, async ({ page }) => {
            await useLocale(page, locale);
            await page.goto('/dashboard');
            await page.getByRole('complementary').getByRole('button', { name: message(locale, 'actions', 'Actions.quickCreate.trigger'), exact: true }).click();
            const optionKey = kind === 'contact' ? 'Actions.create.person' : 'Actions.create.company';
            await page.getByRole('option', { name: message(locale, 'actions', optionKey), exact: true }).click();
            const field = page.locator(kind === 'contact' ? '#name' : '#company-name');
            await expect(field).toBeVisible();
            const namespace = kind === 'contact' ? 'contacts' : 'companies';
            const prefix = kind === 'contact' ? 'ContactsNewContactDialog' : 'CompaniesNewDialog';
            await page.getByRole('button', { name: message(locale, namespace, `${prefix}.create`), exact: true }).click();
            await expect(field).toBeFocused();
            await expect(page.locator(kind === 'contact' ? '#name-error' : '#company-name-error')).toHaveText(message(locale, namespace, `${prefix}.nameRequired`));
        });
    }
}
