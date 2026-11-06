import { randomUUID } from 'node:crypto';
import { expect, test, type Page } from '@playwright/test';
import type { Locale } from '@/i18n/config';
import { activeWorkspaceId, csrfBootstrap, registerUser, seeder } from './support/api';
import { useLocale } from './support/locale';
import { message } from './support/messages';

test.use({ storageState: { cookies: [], origins: [] } });
test.setTimeout(120_000);

function positiveId(value: unknown): number {
    if (typeof value !== 'object' || value === null || !('id' in value)
        || typeof value.id !== 'number' || !Number.isSafeInteger(value.id) || value.id <= 0) {
        throw new Error('Expected a record identifier');
    }
    return value.id;
}

/** Completes the passkey ceremony required to change workspace settings. */
async function enrolWorkspaceAdministrator(page: Page, password: string, locale: Locale): Promise<void> {
    const cdp = await page.context().newCDPSession(page);
    await cdp.send('WebAuthn.enable');
    try {
        const { authenticatorId } = await cdp.send('WebAuthn.addVirtualAuthenticator', { options: {
            protocol: 'ctap2', ctap2Version: 'ctap2_1', transport: 'internal',
            hasResidentKey: true, hasUserVerification: true, isUserVerified: true,
            automaticPresenceSimulation: true,
        } });
        try {
            await page.goto('/settings/personal/security');
            await page.getByRole('button', { name: message(locale, 'account', 'AccountSecurity.add'), exact: true }).first().click();
            const dialog = page.getByRole('dialog', { name: message(locale, 'account', 'AccountSecurity.passwordTitle') });
            await dialog.getByLabel(message(locale, 'account', 'AccountSecurity.passwordLabel')).fill(password);
            await dialog.getByRole('button', { name: message(locale, 'account', 'AccountSecurity.continue'), exact: true }).click();
            await expect(page.getByText(message(locale, 'account', 'AccountSecurity.added'), { exact: true })).toBeVisible();
        } finally {
            await cdp.send('WebAuthn.removeVirtualAuthenticator', { authenticatorId });
        }
    } finally {
        await cdp.send('WebAuthn.disable');
        await cdp.detach();
    }
}

for (const locale of ['en', 'ja'] as const) {
    test(`workspace default starts new deals in EUR and preserves existing GBP deals (${locale}) @mobile`, async ({ page, context }) => {
        const key = randomUUID().replaceAll('-', '');
        const password = `Currency!${key}A1`;
        await registerUser(context.request, {
            username: `defaultcurrency_${key}`, password, email: `currency_${key}@example.invalid`,
        });
        const workspaceId = await activeWorkspaceId(context.request);
        const csrf = await csrfBootstrap(context.request);
        const headers = { 'X-Workspace-Id': String(workspaceId), [csrf.headerName]: csrf.token };
        const seed = seeder(context.request, workspaceId, csrf);
        const pipelineName = `Sales ${key}`;
        const pipeline = positiveId(await seed.post('/api/pipelines', { name: pipelineName }));
        const stage = positiveId(await seed.post(`/api/pipelines/${pipeline}/stages`, { name: 'Proposal', position: 0, success: false, failure: false }));
        const oldName = `Existing sterling ${key}`;
        const review = await seed.post('/api/duplicate-preflight/deals', { name: oldName });
        if (typeof review.reviewToken !== 'string') throw new Error('Expected duplicate review proof');
        const existing = positiveId(await seed.post('/api/deals', {
            name: oldName, value: 50, currency: 'GBP', pipeline, stage, duplicateReviewToken: review.reviewToken,
        }));

        const invalid = await context.request.patch(`/api/workspaces/${workspaceId}/default-currency`, {
            headers, data: { defaultCurrency: 'YEN', expectedIdentityVersion: 0 },
        });
        expect(invalid.status()).toBe(400);
        const invalidBody: unknown = await invalid.json();
        expect(invalidBody).toMatchObject({ fieldErrors: { defaultCurrency: expect.any(String) } });
        const refused = await context.request.patch(`/api/workspaces/${workspaceId}/default-currency`, {
            headers, data: { defaultCurrency: 'EUR', expectedIdentityVersion: 0 },
        });
        expect(refused.status()).toBe(403);
        expect(await refused.json()).toMatchObject({ code: 'RECENT_AUTHENTICATION_REQUIRED' });

        await useLocale(context, locale);
        await context.addInitScript((theme) => localStorage.setItem('theme', theme), locale === 'ja' ? 'dark' : 'light');
        await enrolWorkspaceAdministrator(page, password, locale);
        await page.goto('/settings/workspace/general');
        await expect(page.getByText(message(locale, 'workspace', 'WorkspaceIdentity.currencyFallback').replace('{currency}', 'USD'), { exact: true })).toBeVisible();
        await page.locator('#workspace-default-currency').click();
        await page.getByRole('option', { name: 'EUR', exact: true }).click();
        const currencyForm = page.locator('form').filter({ has: page.locator('#workspace-default-currency') });
        const saved = page.waitForResponse((response) => response.url().endsWith(`/api/workspaces/${workspaceId}/default-currency`)
            && response.request().method() === 'PATCH');
        await currencyForm.getByRole('button', { name: message(locale, 'workspace', 'WorkspaceIdentity.currencySave'), exact: true }).click();
        const saveResponse = await saved;
        expect(saveResponse.status()).toBe(200);
        expect(saveResponse.request().headers()['x-workspace-id']).toBe(String(workspaceId));
        await expect(page.getByText(message(locale, 'workspace', 'WorkspaceIdentity.currencyEffective').replace('{currency}', 'EUR'), { exact: true })).toBeVisible();
        await page.reload();
        await expect(page.locator('#workspace-default-currency')).toContainText('EUR');

        await page.goto('/records/deals');
        await page.getByRole('button', { name: message(locale, 'deals', 'DealsBrowser.addDeal'), exact: true }).click();
        const dialog = page.getByRole('dialog', { name: message(locale, 'deals', 'DealsNewDialog.title'), exact: true });
        await expect(dialog.locator('#deal-currency')).toHaveValue('EUR');
        await dialog.locator('#deal-name').fill(`Euro default ${key}`);
        await dialog.locator('#deal-value').fill('1234');
        await dialog.locator('#deal-pipeline').click();
        await page.getByRole('option', { name: pipelineName, exact: true }).click();
        await dialog.locator('#deal-stage').click();
        await page.getByRole('option', { name: 'Proposal', exact: true }).click();
        const created = page.waitForResponse((response) => response.url().endsWith('/api/deals') && response.request().method() === 'POST');
        await dialog.getByRole('button', { name: message(locale, 'deals', 'DealsNewDialog.create'), exact: true }).click();
        const response = await created;
        expect(response.status()).toBe(200);
        const deal: unknown = await response.json();
        expect(deal).toMatchObject({ currency: 'EUR', value: 1234 });
        const persisted = await context.request.get(`/api/deals/${positiveId(deal)}`, { headers });
        expect(persisted.status()).toBe(200);
        expect(await persisted.json()).toMatchObject({ currency: 'EUR' });
        const unchanged = await context.request.get(`/api/deals/${existing}`, { headers });
        expect(unchanged.status()).toBe(200);
        expect(await unchanged.json()).toMatchObject({ currency: 'GBP', value: 50 });
    });
}
