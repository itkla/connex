import { randomUUID } from 'node:crypto';
import { expect, test, type Locator } from '@playwright/test';
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
        const optIn = panel.getByRole('button', { name: m('actions.granted'), exact: true });
        await optIn.focus();
        await optIn.press('Enter');
        const cancelConfirmation = page.getByRole('dialog', { name: m('actions.granted'), exact: true });
        await cancelConfirmation.getByRole('button', { name: m('cancel'), exact: true }).press('Enter');
        await expect(cancelConfirmation).not.toBeVisible();
        await expect(optIn).toBeFocused();

        const confirm = async (dialog: Locator, path: string, method: string) => {
            await expect(dialog).toBeVisible();
            const [response] = await Promise.all([
                page.waitForResponse((res) => res.url().endsWith(path) && res.request().method() === method),
                dialog.getByRole('button', { name: m('confirm'), exact: true }).click(),
            ]);
            expect(response.ok()).toBe(true);
            await expect(page.getByRole('dialog')).not.toBeVisible();
            await expect(panel.locator('[aria-busy]')).toHaveAttribute('aria-busy', 'false');
        };
        const change = async (action: string, path: string, method: string) => {
            const opener = panel.getByRole('button', { name: m(action), exact: true });
            await expect(opener).toBeEnabled();
            await opener.click();
            await confirm(page.getByRole('dialog', { name: m(action), exact: true }), path, method);
        };

        const detailPath = `/records/contacts/${id}`;
        const releaseRefresh = Promise.withResolvers<void>();
        let refreshStarted = false;
        await page.route(`**${detailPath}*`, async (route) => {
            if (new URL(route.request().url()).pathname !== detailPath || route.request().headers().rsc !== '1') {
                await route.continue();
                return;
            }
            refreshStarted = true;
            await releaseRefresh.promise;
            await route.continue();
        });
        try {
            await change('restrict', '/api/suppressions', 'POST');
            await expect(panel.getByText(m('blocked'), { exact: true })).toBeVisible();
            await expect.poll(() => refreshStarted).toBe(true);
            await expect(optIn).toBeEnabled();
            await optIn.click();
            await expect(cancelConfirmation).toBeVisible();
            const refreshedStatus = page.waitForResponse((response) => response.url().endsWith(`/api/persons/${id}/marketing-status`) && response.request().method() === 'GET');
            releaseRefresh.resolve();
            expect((await refreshedStatus).ok()).toBe(true);
            await expect(cancelConfirmation).toBeVisible();
            await confirm(cancelConfirmation, `/api/persons/${id}/consent`, 'PUT');
        } finally {
            releaseRefresh.resolve();
            await page.unrouteAll({ behavior: 'wait' });
        }
        await expect(panel.getByText(m('recordedConsent').replace('{status}', m('statuses.granted')), { exact: true })).toBeVisible();
        await expect(panel).toBeFocused();
        await expect(panel.getByText(m('blocked'), { exact: true })).toBeVisible();
        const scopedRestrictions = await context.request.get(`/api/suppressions/persons/${id}`, { headers });
        expect(scopedRestrictions.status()).toBe(200);
        expect(await scopedRestrictions.json()).toEqual({ items: [{ id: expect.any(Number), channel: 'email', address: `contact-${key}@example.invalid`, reason: 'do_not_contact' }] });
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
        await expect(panel).toBeFocused();
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
        expect((await context.request.put(`/api/persons/${person.id}/consent`, { headers, data: { channel: 'email', purpose: 'marketing', status: 'granted', source: 'manual', expectedVersion: 0 } })).status()).toBe(404);
        expect((await context.request.get(`/api/suppressions/persons/${person.id}`, { headers })).status()).toBe(404);
        expect((await context.request.post('/api/suppressions', { headers, data: { scope: 'workspace', channel: 'email', address: `foreign-${otherKey}@example.invalid`, personId: person.id, reason: 'do_not_contact' } })).status()).toBe(404);
        expect((await context.request.delete(`/api/suppressions/${restriction.id}`, { headers })).status()).toBe(404);
    } finally {
        await other.close();
    }
});

test('stale confirmations refresh consent and preserve a concurrent privacy hold @mobile', async ({ page, context }) => {
    const key = randomUUID();
    await registerUser(context.request, { username: `stale_${key.replaceAll('-', '')}`, password: `Consent!${key}A1`, email: `stale-${key}@example.invalid` });
    const workspaceId = await activeWorkspaceId(context.request);
    const csrf = await csrfBootstrap(context.request);
    const headers = { 'X-Workspace-Id': String(workspaceId) };
    const created = await seeder(context.request, workspaceId, csrf).post('/api/persons', { name: `Stale marketing contact ${key}`, email: `stale-contact-${key}@example.invalid` });
    if (typeof created.id !== 'number') throw new Error('The contact has no id');
    const id = created.id;
    const m = (name: string) => message('en', 'contacts', `ContactMarketingPreferences.${name}`);
    const restriction = (name: string) => message('en', 'contacts', `ContactRestrictions.${name}`);
    await useLocale(context, 'en');
    await page.goto(`/records/contacts/${id}`);
    const panel = page.getByRole('region', { name: m('title'), exact: true });
    await panel.getByRole('button', { name: m('actions.granted'), exact: true }).click();
    const staleConsent = page.getByRole('dialog', { name: m('actions.granted'), exact: true });
    await expect(staleConsent.getByText(m('clearOptOutWarning'), { exact: true })).toHaveCount(0);
    const otherTab = await context.newPage();
    try {
        await otherTab.goto(`/records/contacts/${id}`);
        const otherPanel = otherTab.getByRole('region', { name: m('title'), exact: true });
        await otherPanel.getByRole('button', { name: m('actions.revoked'), exact: true }).click();
        const revoked = otherTab.waitForResponse((response) => response.url().endsWith(`/api/persons/${id}/consent`) && response.request().method() === 'PUT');
        await otherTab.getByRole('dialog', { name: m('actions.revoked'), exact: true }).getByRole('button', { name: m('confirm'), exact: true }).click();
        expect((await revoked).status()).toBe(200);
        const conflict = page.waitForResponse((response) => response.url().endsWith(`/api/persons/${id}/consent`) && response.request().method() === 'PUT');
        await staleConsent.getByRole('button', { name: m('confirm'), exact: true }).click();
        const refused = await conflict;
        expect(refused.status()).toBe(409);
        expect(await refused.json()).toMatchObject({ code: 'STALE_RECORD', currentVersion: expect.any(Number) });
        await expect(staleConsent).not.toBeVisible();
        await expect(panel.getByText(m('conflict'), { exact: true })).toBeVisible();
        await expect(panel.getByText(m('recordedConsent').replace('{status}', m('statuses.revoked')), { exact: true })).toBeVisible();
        const freshOptIn = panel.getByRole('button', { name: m('actions.granted'), exact: true });
        await freshOptIn.focus();
        await freshOptIn.press('Enter');
        await expect(staleConsent.getByText(m('clearOptOutWarning'), { exact: true })).toBeVisible();
        await staleConsent.getByRole('button', { name: m('cancel'), exact: true }).press('Enter');
        await expect(freshOptIn).toBeFocused();

        await panel.getByRole('button', { name: m('privacyAction'), exact: true }).click();
        const stalePrivacy = page.getByRole('dialog', { name: restriction('title'), exact: true });
        await expect(stalePrivacy.getByRole('switch', { name: restriction('suspendLabel'), exact: true })).not.toBeChecked();
        await otherPanel.getByRole('button', { name: m('privacyAction'), exact: true }).click();
        const otherPrivacy = otherTab.getByRole('dialog', { name: restriction('title'), exact: true });
        await otherPrivacy.getByRole('switch', { name: restriction('suspendLabel'), exact: true }).click();
        const held = otherTab.waitForResponse((response) => response.url().endsWith(`/api/persons/${id}/restrictions`) && response.request().method() === 'PUT');
        await otherPrivacy.getByRole('button', { name: restriction('save'), exact: true }).click();
        expect((await held).status()).toBe(200);
        await expect(stalePrivacy.getByRole('switch', { name: restriction('suspendLabel'), exact: true })).not.toBeChecked();
        await stalePrivacy.getByRole('switch', { name: restriction('provisionLabel'), exact: true }).click();
        const patched = page.waitForResponse((response) => response.url().endsWith(`/api/persons/${id}/restrictions`) && response.request().method() === 'PUT');
        await stalePrivacy.getByRole('button', { name: restriction('save'), exact: true }).click();
        const saved = await patched;
        expect(saved.status()).toBe(200);
        expect(saved.request().postDataJSON()).toEqual({ provisionCeased: true });
        const stored = await context.request.get(`/api/persons/${id}`, { headers });
        expect(stored.status()).toBe(200);
        expect(await stored.json()).toMatchObject({ suspendedAt: expect.any(String), provisionCeasedAt: expect.any(String) });
    } finally {
        await otherTab.close();
    }
});
