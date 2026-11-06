import { randomUUID } from 'node:crypto';
import { expect, test } from '@playwright/test';
import { activeWorkspaceId, csrfBootstrap, registerUser } from './support/api';
import { useLocale } from './support/locale';
import { message } from './support/messages';

test.use({ storageState: { cookies: [], origins: [] } });

for (const [locale, theme] of [['en', 'light'], ['ja', 'dark']] as const) {
    test(`approval currency is chosen explicitly and persists (${locale}, ${theme}) @mobile`, async ({ page, context }) => {
        test.setTimeout(120_000);
        const suffix = randomUUID().replaceAll('-', '').slice(0, 12);
        await registerUser(context.request, {
            username: `curguard_${suffix}`, password: `Currency!${suffix}A1`, email: `curguard_${suffix}@example.test`,
        });
        const workspaceId = await activeWorkspaceId(context.request);
        const csrf = await csrfBootstrap(context.request);
        const headers = { 'X-Workspace-Id': String(workspaceId), [csrf.headerName]: csrf.token };
        const invalid = await context.request.post('/api/approval-policies', {
            headers, data: { name: `Invalid ${suffix}`, currency: 'yen', minTotal: 1000 },
        });
        expect(invalid.status()).toBe(400);
        await useLocale(context, locale);
        await context.addInitScript((choice) => localStorage.setItem('theme', choice), theme);
        await page.emulateMedia({ colorScheme: theme });
        await page.goto('/settings/workspace/crm');
        await page.getByRole('button', { name: message(locale, 'approval-policies', 'ApprovalPoliciesBrowser.newButton'), exact: true }).filter({ visible: true }).click();
        const dialog = page.getByRole('dialog');
        const label = (key: string) => message(locale, 'approval-policies', `ApprovalPolicyDialog.${key}`);
        await dialog.getByLabel(label('nameLabel')).fill(`Currency ${suffix}`);
        await dialog.getByLabel(label('minTotalLabel')).fill('1000');
        const save = dialog.getByRole('button', { name: label('save'), exact: true });
        await expect(save).toBeDisabled();
        await dialog.getByLabel(label('currencyLabel')).fill('YEN');
        const refused = page.waitForResponse((response) => response.url().endsWith('/api/approval-policies')
            && response.request().method() === 'POST' && response.status() === 400);
        await save.click();
        await refused;
        await expect(dialog).toBeVisible();
        await expect(dialog.getByLabel(label('nameLabel'))).toHaveValue(`Currency ${suffix}`);
        await dialog.getByLabel(label('currencyLabel')).fill('KWD');
        await expect(save).toBeEnabled();
        const created = page.waitForResponse((response) => response.url().endsWith('/api/approval-policies')
            && response.request().method() === 'POST' && response.ok());
        await save.click();
        const body: unknown = await (await created).json();
        expect(body).toMatchObject({ currency: 'KWD', currencyValid: true, minTotal: 1000 });
        await expect(dialog).toBeHidden();
        await page.reload();
        const row = page.getByRole('row').filter({ hasText: `Currency ${suffix}` });
        await expect(row).toBeVisible();
        await row.getByText(`Currency ${suffix}`, { exact: true }).click();
        await expect(dialog.getByLabel(label('currencyLabel'))).toHaveValue('KWD');
        await dialog.getByLabel(label('currencyLabel')).fill('CLF');
        await save.click();
        await expect(dialog).toBeHidden();
        await page.reload();
        await row.getByText(`Currency ${suffix}`, { exact: true }).click();
        await expect(dialog.getByLabel(label('currencyLabel'))).toHaveValue('CLF');
    });
}
