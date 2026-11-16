import { randomUUID } from 'node:crypto';
import { expect, test, type Page } from '@playwright/test';
import { activeWorkspaceId, csrfBootstrap, registerUser, seeder } from './support/api';
import { useLocale } from './support/locale';
import { message } from './support/messages';

test.use({ storageState: { cookies: [], origins: [] } });

async function openEditor(page: Page, surface: 'detail' | 'list' | 'profile', name: string, locale: 'en' | 'ja') {
    if (surface === 'detail') {
        await page.getByRole('button', { name: message(locale, 'contacts', 'ContactsActionsMenu.edit'), exact: true }).click();
    } else if (surface === 'profile') {
        await page.getByRole('button', { name: message(locale, 'records', 'RecordActionMenu.menuAria').replace('{name}', name), exact: true }).click();
        await page.getByRole('menuitem', { name: message(locale, 'records', 'RecordActionMenu.quickEdit'), exact: true }).click();
    } else {
        await page.getByRole('row').filter({ hasText: name }).getByRole('checkbox').check();
        await page.getByRole('button', { name: message(locale, 'contacts', 'ContactsBrowser.quickEdit'), exact: true }).click();
    }
    const editor = page.getByRole('dialog', { name: message(locale, 'contacts', 'ContactsQuickEditSheet.titleSingle'), exact: true });
    await expect(editor).toBeVisible();
    return editor;
}

for (const locale of ['en', 'ja'] as const) {
    for (const surface of ['detail', 'list', 'profile'] as const) {
        for (const openBeforeWrite of [true, false]) {
            test(`${surface} ${locale}: ${openBeforeWrite ? 'stale Phone save refuses a newer Title' : 'Edit refreshes a stale page'} across two tabs`, async ({ page, context }) => {
                const key = randomUUID();
                await registerUser(context.request, {
                    username: `contact_${key.replaceAll('-', '')}`, password: `Contact!${key}A1`, email: `contact-${key}@example.invalid`,
                });
                const workspaceId = await activeWorkspaceId(context.request);
                const csrf = await csrfBootstrap(context.request);
                const headers = { 'X-Workspace-Id': String(workspaceId) };
                const seed = seeder(context.request, workspaceId, csrf);
                const name = `Concurrent contact ${key}`;
                const company = surface === 'profile' ? await seed.post('/api/companies', { name: `Company ${key}` }) : null;
                const created = await seed.post('/api/persons', { name, title: 'Director', phone: '111111', companyId: company?.id ?? null });
                if (typeof created.id !== 'number') throw new Error('The created contact has no id');
                const id = created.id;
                await useLocale(context, locale);
                const detailUrl = `/records/contacts/${id}`;
                const url = surface === 'detail' ? detailUrl : surface === 'profile' ? `/records/companies/${company?.id}` : `/records/contacts?view=table&q=${encodeURIComponent(name)}`;
                const otherTab = await context.newPage();
                try {
                    await page.goto(url);
                    if (surface !== 'list') await expect(page.getByRole('heading', { name, exact: true }).first()).toBeVisible();
                    else await expect(page.getByRole('row').filter({ hasText: name })).toBeVisible();
                    if (openBeforeWrite) await openEditor(page, surface, name, locale);
                    await otherTab.goto(detailUrl);
                    const otherEditor = await openEditor(otherTab, 'detail', name, locale);
                    await otherEditor.locator(`#title-${id}`).fill('VP');
                    const saved = otherTab.waitForResponse((response) => response.url().endsWith(`/api/persons/${id}`)
                        && response.request().method() === 'PUT');
                    await otherEditor.getByRole('button', { name: message(locale, 'contacts', 'ContactsQuickEditSheet.save'), exact: true }).click();
                    expect((await saved).status()).toBe(200);
                    await expect(otherEditor).not.toBeVisible();
                    const editor = openBeforeWrite
                        ? page.getByRole('dialog', { name: message(locale, 'contacts', 'ContactsQuickEditSheet.titleSingle'), exact: true })
                        : await openEditor(page, surface, name, locale);
                    await expect(editor.locator(`#title-${id}`)).toHaveValue(openBeforeWrite ? 'Director' : 'VP');
                    await editor.locator(`#phone-${id}`).fill('222222');
                    const result = page.waitForResponse((response) => response.url().endsWith(`/api/persons/${id}`)
                        && response.request().method() === 'PUT');
                    await editor.getByRole('button', { name: message(locale, 'contacts', 'ContactsQuickEditSheet.save'), exact: true }).click();
                    expect((await result).status()).toBe(openBeforeWrite ? 409 : 200);
                    if (!openBeforeWrite) {
                        await expect(editor).not.toBeVisible();
                        const stored = await context.request.get(`/api/persons/${id}`, { headers });
                        expect(await stored.json()).toMatchObject({ title: 'VP', phone: '222222' });
                        return;
                    }
                    const conflict = page.getByRole('dialog', { name: message(locale, 'contacts', 'ContactsEditConflict.title'), exact: true });
                    await expect(conflict.getByRole('listitem')).toContainText([message(locale, 'contacts', 'ContactsEditConflict.fields.title')]);
                    await conflict.getByRole('button', { name: message(locale, 'contacts', 'ContactsEditConflict.keepEditing'), exact: true }).click();
                    await expect(editor.locator(`#phone-${id}`)).toHaveValue('222222');
                    await expect(editor.locator(`#title-${id}`)).toHaveValue('Director');
                    const refused = page.waitForResponse((response) => response.url().endsWith(`/api/persons/${id}`)
                        && response.request().method() === 'PUT');
                    await editor.getByRole('button', { name: message(locale, 'contacts', 'ContactsQuickEditSheet.save'), exact: true }).click();
                    const refusal = await refused;
                    expect(refusal.status()).toBe(409);
                    expect(await refusal.json()).toMatchObject({ code: 'STALE_RECORD', currentVersion: expect.any(Number) });
                    const stored = await context.request.get(`/api/persons/${id}`, { headers });
                    expect(await stored.json()).toMatchObject({ title: 'VP', phone: '111111' });
                    await conflict.getByRole('button', { name: message(locale, 'contacts', 'ContactsEditConflict.reload'), exact: true }).click();
                    const confirm = page.getByRole('dialog', { name: message(locale, 'contacts', 'ContactsEditConflict.confirmTitle'), exact: true });
                    await confirm.getByRole('button', { name: message(locale, 'contacts', 'ContactsEditConflict.discardAndReload'), exact: true }).click();
                    await expect(editor.locator(`#title-${id}`)).toHaveValue('VP');
                    await expect(editor.locator(`#phone-${id}`)).toHaveValue('111111');
                } finally {
                    await otherTab.close();
                }
            });
        }
    }
}

test('a stale inline title edit preserves its draft and refuses a newer phone from another tab', async ({ page, context }) => {
    const key = randomUUID();
    await registerUser(context.request, {
        username: `inline_${key.replaceAll('-', '')}`, password: `Contact!${key}A1`, email: `inline-${key}@example.invalid`,
    });
    const workspaceId = await activeWorkspaceId(context.request);
    const seed = seeder(context.request, workspaceId, await csrfBootstrap(context.request));
    const name = `Inline contact ${key}`;
    const created = await seed.post('/api/persons', { name, title: 'Director', phone: '111111' });
    if (typeof created.id !== 'number') throw new Error('The created contact has no id');
    const id = created.id;
    await useLocale(context, 'en');
    await page.goto(`/records/contacts?view=table&q=${encodeURIComponent(name)}`);
    const cell = page.getByRole('button', { name: `Edit Title for ${name}`, exact: true });
    await expect(cell).toBeVisible();
    const otherTab = await context.newPage();
    const releaseRefusal = Promise.withResolvers<void>();
    const intercepted = Promise.withResolvers<void>();
    await page.route(`**/api/persons/${id}`, async (route) => {
        if (route.request().method() !== 'PUT') { await route.continue(); return; }
        const response = await route.fetch();
        intercepted.resolve();
        await releaseRefusal.promise;
        await route.fulfill({ response });
    });
    try {
        await otherTab.goto(`/records/contacts/${id}`);
        const otherEditor = await openEditor(otherTab, 'detail', name, 'en');
        await otherEditor.locator(`#phone-${id}`).fill('222222');
        const phoneSaved = otherTab.waitForResponse((response) => response.url().endsWith(`/api/persons/${id}`) && response.request().method() === 'PUT');
        await otherEditor.getByRole('button', { name: message('en', 'contacts', 'ContactsQuickEditSheet.save'), exact: true }).click();
        expect((await phoneSaved).status()).toBe(200);
        await expect(otherEditor).not.toBeVisible();
        await page.getByRole('row').filter({ hasText: name }).getByRole('checkbox').check();
        await cell.dblclick();
        const inline = page.getByRole('row').filter({ hasText: name }).getByRole('textbox');
        await inline.fill('VP draft');
        const refused = page.waitForResponse((response) => response.url().endsWith(`/api/persons/${id}`) && response.request().method() === 'PUT');
        await inline.press('Enter');
        await intercepted.promise;
        await expect(page.getByRole('button', { name: message('en', 'contacts', 'ContactsBrowser.quickEdit'), exact: true })).toBeDisabled();
        await expect(page.getByRole('button', { name: `Edit Email for ${name}`, exact: true })).toHaveCount(0);
        releaseRefusal.resolve();
        expect((await refused).status()).toBe(409);
        const conflict = page.getByRole('dialog', { name: message('en', 'contacts', 'ContactsEditConflict.title'), exact: true });
        await expect(conflict.getByRole('listitem')).toContainText([message('en', 'contacts', 'ContactsEditConflict.fields.phone')]);
        await conflict.getByRole('button', { name: message('en', 'contacts', 'ContactsEditConflict.keepEditing'), exact: true }).click();
        const editor = page.getByRole('dialog', { name: message('en', 'contacts', 'ContactsQuickEditSheet.titleSingle'), exact: true });
        await expect(editor.locator(`#title-${id}`)).toHaveValue('VP draft');
        const stored = await context.request.get(`/api/persons/${id}`, { headers: { 'X-Workspace-Id': String(workspaceId) } });
        expect(await stored.json()).toMatchObject({ phone: '222222', title: 'Director' });
    } finally {
        releaseRefusal.resolve();
        await page.unrouteAll({ behavior: 'wait' });
        await otherTab.close();
    }
});
