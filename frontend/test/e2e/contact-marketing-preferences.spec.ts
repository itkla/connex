import { randomUUID } from 'node:crypto';
import { expect, test } from '@playwright/test';
import { activeWorkspaceId, csrfBootstrap, registerUser, seeder } from './support/api';
import { useLocale } from './support/locale';
import { message } from './support/messages';

test.use({ storageState: { cookies: [], origins: [] } });

for (const locale of ['en', 'ja'] as const) {
    test(`contact marketing preferences preserve independent opt-outs and restrictions (${locale}) @mobile`, async ({ page, context }) => {
        const key = randomUUID();
        await registerUser(context.request, { username: `consent_${key.replaceAll('-', '')}`, password: `Consent!${key}A1`, email: `consent-${key}@example.invalid` });
        const workspaceId = await activeWorkspaceId(context.request);
        const csrf = await csrfBootstrap(context.request);
        const headers = { 'X-Workspace-Id': String(workspaceId) };
        const created = await seeder(context.request, workspaceId, csrf).post('/api/persons', {
            name: `Marketing contact ${key}`, email: `contact-${key}@example.invalid`, phone: '+81 90-1234-5678',
        });
        if (typeof created.id !== 'number') throw new Error('The contact has no id');
        const id = created.id;
        const m = (name: string) => message(locale, 'contacts', `ContactMarketingPreferences.${name}`);
        await useLocale(context, locale);
        await page.goto(`/records/contacts/${id}`);
        const panel = page.getByRole('region', { name: m('title'), exact: true });
        await expect(panel.getByText(m('policy'), { exact: true })).toBeVisible();
        await expect(panel.getByText(m('basis'), { exact: true })).toBeVisible();

        const change = async (action: string, path: string, method: string) => {
            await panel.getByRole('button', { name: m(action), exact: true }).click();
            const dialog = page.getByRole('dialog', { name: m(action), exact: true });
            await expect(dialog).toBeVisible();
            const response = page.waitForResponse((res) => res.url().endsWith(path) && res.request().method() === method);
            await dialog.getByRole('button', { name: m('confirm'), exact: true }).click();
            expect((await response).ok()).toBe(true);
            await expect(dialog).not.toBeVisible();
        };

        await change('actions.granted', `/api/persons/${id}/consent`, 'PUT');
        await expect(panel.getByText(m('recordedConsent').replace('{status}', m('statuses.granted')), { exact: true })).toBeVisible();
        await change('restrict', '/api/suppressions', 'POST');
        await expect(panel.getByText(m('blocked'), { exact: true })).toBeVisible();
        await change('actions.revoked', `/api/persons/${id}/consent`, 'PUT');
        await change('actions.granted', `/api/persons/${id}/consent`, 'PUT');
        await expect(panel.getByText(m('blocked'), { exact: true })).toBeVisible();
        await expect(panel.getByRole('button', { name: m('remove'), exact: true })).toBeVisible();

        await change('actions.revoked', `/api/persons/${id}/consent`, 'PUT');
        await panel.getByRole('button', { name: m('remove'), exact: true }).click();
        const removal = page.getByRole('dialog', { name: m('remove'), exact: true });
        const removed = page.waitForResponse((res) => /\/api\/suppressions\/\d+$/.test(res.url()) && res.request().method() === 'DELETE');
        await removal.getByRole('button', { name: m('confirm'), exact: true }).click();
        expect((await removed).status()).toBe(204);
        await expect(removal).not.toBeVisible();
        await expect(panel.getByText(m('blocked'), { exact: true })).toBeVisible();
        const stored = await context.request.get(`/api/persons/${id}/consent`, { headers });
        expect(stored.status()).toBe(200);
        expect(await stored.json()).toEqual(expect.arrayContaining([expect.objectContaining({ channel: 'email', purpose: 'marketing', status: 'revoked', source: 'manual' })]));
        await page.reload();
        await expect(panel.getByText(m('blocked'), { exact: true })).toBeVisible();

        await panel.getByRole('button', { name: m('privacyAction'), exact: true }).click();
        const privacy = page.getByRole('dialog', { name: message(locale, 'contacts', 'ContactRestrictions.title'), exact: true });
        const holdSwitch = privacy.getByRole('switch', { name: message(locale, 'contacts', 'ContactRestrictions.suspendLabel'), exact: true });
        await expect(holdSwitch).not.toBeChecked();
        await holdSwitch.click();
        await expect(holdSwitch).toBeChecked();
        const held = page.waitForResponse((res) => res.url().endsWith(`/api/persons/${id}/restrictions`) && res.request().method() === 'PUT');
        await privacy.getByRole('button', { name: message(locale, 'contacts', 'ContactRestrictions.save'), exact: true }).click();
        expect((await held).status()).toBe(200);
        await expect(privacy).not.toBeVisible();
        await expect(panel.getByText(m('privacyBlocked'), { exact: true })).toBeVisible();
        await expect(panel.getByText(m('blocked'), { exact: true })).toBeVisible();
        await expect(panel.getByText(m('basis'), { exact: true })).toHaveCount(0);
        await page.getByRole('button', { name: message(locale, 'contacts', 'ContactsActionsMenu.moreActions'), exact: true }).click();
        await page.getByRole('menuitem', { name: message(locale, 'contacts', 'ContactsActionsMenu.restrictions'), exact: true }).click();
        await expect(privacy.getByRole('switch', { name: message(locale, 'contacts', 'ContactRestrictions.suspendLabel'), exact: true })).toBeChecked();
    });
}

test('contact preference APIs reject other-workspace contacts and restrictions', async ({ context, browser }) => {
    const key = randomUUID();
    await registerUser(context.request, { username: `boundary_${key.replaceAll('-', '')}`, password: `Consent!${key}A1`, email: `boundary-${key}@example.invalid` });
    const workspaceId = await activeWorkspaceId(context.request);
    const csrf = await csrfBootstrap(context.request);
    const headers = { 'X-Workspace-Id': String(workspaceId), [csrf.headerName]: csrf.token };
    const other = await browser.newContext({ storageState: { cookies: [], origins: [] } });
    try {
        const otherKey = randomUUID();
        await registerUser(other.request, { username: `foreign_${otherKey.replaceAll('-', '')}`, password: `Consent!${otherKey}A1`, email: `foreign-${otherKey}@example.invalid` });
        const otherId = await activeWorkspaceId(other.request);
        const seed = seeder(other.request, otherId, await csrfBootstrap(other.request));
        const person = await seed.post('/api/persons', { name: 'Other workspace contact', email: `foreign-${otherKey}@example.invalid` });
        if (typeof person.id !== 'number') throw new Error('The contact has no id');
        const restriction = await seed.post('/api/suppressions', { scope: 'workspace', channel: 'email', address: `foreign-${otherKey}@example.invalid`, personId: person.id, reason: 'do_not_contact' });
        if (typeof restriction.id !== 'number') throw new Error('The restriction has no id');
        expect((await context.request.get(`/api/persons/${person.id}/consent`, { headers })).status()).toBe(404);
        expect((await context.request.put(`/api/persons/${person.id}/consent`, { headers, data: { channel: 'email', purpose: 'marketing', status: 'granted', source: 'manual' } })).status()).toBe(404);
        expect((await context.request.post('/api/suppressions', { headers, data: { scope: 'workspace', channel: 'email', address: `foreign-${otherKey}@example.invalid`, personId: person.id, reason: 'do_not_contact' } })).status()).toBe(404);
        expect((await context.request.delete(`/api/suppressions/${restriction.id}`, { headers })).status()).toBe(404);
    } finally {
        await other.close();
    }
});
