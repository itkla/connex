import { expect, test, type Page } from '@playwright/test';
import { csrfBootstrap, seeder } from './support/api';
import { runFixture } from './support/fixtures';
import { useLocale } from './support/locale';
import { message } from './support/messages';

function isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === 'object' && value !== null;
}

async function openEditor(page: Page, surface: 'detail' | 'list', name: string, locale: 'en' | 'ja') {
    if (surface === 'detail') {
        await page.getByRole('button', { name: message(locale, 'deals', 'DealsActionsMenu.edit'), exact: true }).click();
    } else {
        await page.getByRole('row').filter({ hasText: name }).getByRole('checkbox').check();
        await page.getByRole('button', { name: message(locale, 'deals', 'DealsBrowser.quickEdit'), exact: true }).click();
    }
    return page.getByRole('dialog', { name: message(locale, 'deals', 'DealsQuickEditSheet.titleSingle'), exact: true });
}

for (const locale of ['en', 'ja'] as const) {
    for (const surface of ['detail', 'list'] as const) {
        for (const openBeforeWrite of [true, false]) {
            const checksConflict = openBeforeWrite || surface === 'list';
            test(`${surface} ${locale}: ${checksConflict ? `stale ${openBeforeWrite ? 'open form' : 'page before opening form'} preserves the draft` : 'opening Edit refreshes the snapshot'} across two tabs`, async ({ page, context }, testInfo) => {
                const fixture = runFixture(testInfo.project.name);
                await useLocale(context, locale);
                const csrf = await csrfBootstrap(context.request);
                const seed = seeder(context.request, fixture.workspaceId, csrf);
                const sourceResponse = await context.request.get(`/api/deals/${fixture.deals.primary.id}`);
                expect(sourceResponse.ok()).toBe(true);
                const source: unknown = await sourceResponse.json();
                if (!isRecord(source) || typeof source.pipeline !== 'number' || typeof source.stage !== 'number') {
                    throw new Error('The seeded deal has no pipeline or stage');
                }
                const name = `Concurrent deal ${testInfo.testId} ${Date.now()}`;
                const review = await seed.post('/api/duplicate-preflight/deals', { name, companyId: null });
                const created = await seed.post('/api/deals', {
                    name, value: 100, actualValue: 0, currency: 'JPY', pipeline: source.pipeline,
                    stage: source.stage, company: null, duplicateReviewToken: review.reviewToken,
                });
                if (typeof created.id !== 'number') throw new Error('The created deal has no id');
                const id = created.id;
                const detailUrl = `/records/deals/${id}`;
                const url = surface === 'detail' ? detailUrl : `/records/deals?view=table&q=${encodeURIComponent(name)}`;
                const otherTab = await context.newPage();
                try {
                    await page.goto(url);
                    if (surface === 'detail') await expect(page.getByRole('heading', { name, exact: true }).first()).toBeVisible();
                    else await expect(page.getByRole('row').filter({ hasText: name })).toBeVisible();
                    if (openBeforeWrite) await openEditor(page, surface, name, locale);

                    await otherTab.goto(detailUrl);
                    const otherEditor = await openEditor(otherTab, 'detail', name, locale);
                    await otherEditor.locator(`#deal-value-${id}`).fill('99999');
                    const saved = otherTab.waitForResponse((response) => response.url().endsWith(`/api/deals/${id}`)
                        && response.request().method() === 'PUT');
                    await otherEditor.getByRole('button', { name: message(locale, 'deals', 'DealsQuickEditSheet.save'), exact: true }).click();
                    expect((await saved).status()).toBe(200);
                    await expect(otherEditor).not.toBeVisible();

                    if (!checksConflict) {
                        const editor = await openEditor(page, surface, name, locale);
                        await expect(editor.locator(`#deal-value-${id}`)).toHaveValue('99999');
                        await editor.locator(`#deal-name-${id}`).fill(`${name} draft`);
                        const updated = page.waitForResponse((response) => response.url().endsWith(`/api/deals/${id}`)
                            && response.request().method() === 'PUT');
                        await editor.getByRole('button', { name: message(locale, 'deals', 'DealsQuickEditSheet.save'), exact: true }).click();
                        expect((await updated).status()).toBe(200);
                        await expect(editor).not.toBeVisible();
                        const stored = await context.request.get(`/api/deals/${id}`);
                        expect(await stored.json()).toMatchObject({ name: `${name} draft`, value: 99999 });
                        return;
                    }

                    if (!openBeforeWrite) await openEditor(page, surface, name, locale);
                    const editor = page.getByRole('dialog', { name: message(locale, 'deals', 'DealsQuickEditSheet.titleSingle'), exact: true });
                    const draftName = `${name} draft`;
                    await editor.locator(`#deal-name-${id}`).fill(draftName);
                    const refused = page.waitForResponse((response) => response.url().endsWith(`/api/deals/${id}`)
                        && response.request().method() === 'PUT');
                    await editor.getByRole('button', { name: message(locale, 'deals', 'DealsQuickEditSheet.save'), exact: true }).click();
                    const response = await refused;
                    expect(response.status()).toBe(409);
                    const body: unknown = await response.json();
                    expect(body).toEqual(expect.objectContaining({ code: 'STALE_RECORD', currentVersion: expect.any(Number) }));
                    const conflict = page.getByRole('dialog', { name: message(locale, 'deals', 'DealsEditConflict.title'), exact: true });
                    await expect(conflict.getByRole('listitem')).toContainText([message(locale, 'deals', 'DealsEditConflict.fields.value')]);
                    await conflict.getByRole('button', { name: message(locale, 'deals', 'DealsEditConflict.keepEditing'), exact: true }).click();
                    await expect(editor.locator(`#deal-name-${id}`)).toHaveValue(draftName);
                    await expect(editor.locator(`#deal-value-${id}`)).toHaveValue('100');

                    const stillRefused = page.waitForResponse((result) => result.url().endsWith(`/api/deals/${id}`)
                        && result.request().method() === 'PUT');
                    await editor.getByRole('button', { name: message(locale, 'deals', 'DealsQuickEditSheet.save'), exact: true }).click();
                    expect((await stillRefused).status()).toBe(409);
                    await conflict.getByRole('button', { name: message(locale, 'deals', 'DealsEditConflict.reload'), exact: true }).click();
                    const confirm = page.getByRole('dialog', { name: message(locale, 'deals', 'DealsEditConflict.confirmTitle'), exact: true });
                    await expect(confirm).toBeVisible();
                    await confirm.getByRole('button', { name: message(locale, 'deals', 'DealsEditConflict.discardAndReload'), exact: true }).click();
                    await expect(editor.locator(`#deal-name-${id}`)).toHaveValue(name);
                    await expect(editor.locator(`#deal-value-${id}`)).toHaveValue('99999');
                    const currentResponse = await context.request.get(`/api/deals/${id}`);
                    const current: unknown = await currentResponse.json();
                    expect(current).toEqual(expect.objectContaining({ name, value: 99999 }));
                    await editor.getByRole('button', { name: message(locale, 'deals', 'DealsQuickEditSheet.cancel'), exact: true }).click();
                    await expect(editor).not.toBeVisible();
                } finally {
                    await otherTab.close();
                }
            });
        }
    }
}

test('an inline value save followed by quick-edit name save uses the advanced version', async ({ page, context }, testInfo) => {
    const fixture = runFixture(testInfo.project.name);
    await useLocale(context, 'en');
    const seed = seeder(context.request, fixture.workspaceId, await csrfBootstrap(context.request));
    const sourceResponse = await context.request.get(`/api/deals/${fixture.deals.primary.id}`);
    expect(sourceResponse.ok()).toBe(true);
    const source: unknown = await sourceResponse.json();
    if (!isRecord(source) || typeof source.pipeline !== 'number' || typeof source.stage !== 'number') {
        throw new Error('The seeded deal has no pipeline or stage');
    }
    const name = `Inline deal ${Date.now()} ${testInfo.testId}`;
    const review = await seed.post('/api/duplicate-preflight/deals', { name, companyId: null });
    const created = await seed.post('/api/deals', {
        name, value: 100, actualValue: 0, currency: 'JPY', pipeline: source.pipeline,
        stage: source.stage, company: null, duplicateReviewToken: review.reviewToken,
    });
    if (typeof created.id !== 'number') throw new Error('The created deal has no id');
    const id = created.id;
    await page.goto(`/records/deals?view=table&q=${encodeURIComponent(name)}`);
    const row = page.getByRole('row').filter({ hasText: name });
    const editValue = message('en', 'records', 'RecordsRenderView.editFieldAria')
        .replace('{field}', message('en', 'deals', 'DealsBrowser.columnValue')).replace('{name}', name);
    await row.getByRole('button', { name: editValue, exact: true }).dblclick();
    const input = row.getByRole('textbox');
    await input.fill('99999');
    const valueSaved = page.waitForResponse((response) => response.url().endsWith(`/api/deals/${id}/value`)
        && response.request().method() === 'PUT');
    await input.press('Enter');
    expect((await valueSaved).status()).toBe(200);
    await expect(input).not.toBeVisible();
    const editor = await openEditor(page, 'list', name, 'en');
    await expect(editor.locator(`#deal-value-${id}`)).toHaveValue('99999');
    await editor.locator(`#deal-name-${id}`).fill(`${name} edited`);
    const nameSaved = page.waitForResponse((response) => response.url().endsWith(`/api/deals/${id}`)
        && response.request().method() === 'PUT');
    await editor.getByRole('button', { name: message('en', 'deals', 'DealsQuickEditSheet.save'), exact: true }).click();
    expect((await nameSaved).status()).toBe(200);
    await expect(editor).not.toBeVisible();
    const storedResponse = await context.request.get(`/api/deals/${id}`);
    const stored: unknown = await storedResponse.json();
    expect(stored).toEqual(expect.objectContaining({ name: `${name} edited`, value: 99999 }));
});

test('line rename followed by detail Edit uses the reconciled version while route refresh is held', async ({ page, context }, testInfo) => {
    const fixture = runFixture(testInfo.project.name);
    await useLocale(context, 'en');
    const seed = seeder(context.request, fixture.workspaceId, await csrfBootstrap(context.request));
    const sourceResponse = await context.request.get(`/api/deals/${fixture.deals.primary.id}`);
    expect(sourceResponse.ok()).toBe(true);
    const source: unknown = await sourceResponse.json();
    if (!isRecord(source) || typeof source.pipeline !== 'number' || typeof source.stage !== 'number') {
        throw new Error('The seeded deal has no pipeline or stage');
    }
    const name = `Line snapshot ${Date.now()} ${testInfo.testId}`;
    const review = await seed.post('/api/duplicate-preflight/deals', { name, companyId: null });
    const created = await seed.post('/api/deals', {
        name, value: 100, currency: 'JPY', pipeline: source.pipeline, stage: source.stage,
        company: null, duplicateReviewToken: review.reviewToken,
    });
    if (typeof created.id !== 'number') throw new Error('The created deal has no id');
    const id = created.id;
    const lines = await seed.post(`/api/deals/${id}/line-items`, {
        name: 'Consulting', unitPrice: 100, quantity: 1, billingFrequency: 'one_time',
    });
    const line = Array.isArray(lines.items) ? lines.items.find(isRecord) : undefined;
    if (!line || typeof line.id !== 'number') throw new Error('The created line has no id');
    const lineId = line.id;
    const detailPath = `/records/deals/${id}`;
    await page.goto(detailPath);
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
        const itemName = page.getByRole('textbox', { name: message('en', 'deals', 'DealsLineItems.columnItem'), exact: true });
        await itemName.fill('Renamed consulting');
        const lineSaved = page.waitForResponse((response) => response.url().endsWith(`/api/deals/${id}/line-items/${lineId}`)
            && response.request().method() === 'PUT');
        await itemName.press('Tab');
        expect((await lineSaved).status()).toBe(200);
        await expect.poll(() => refreshStarted).toBe(true);
        const editor = await openEditor(page, 'detail', name, 'en');
        await editor.locator(`#deal-name-${id}`).fill(`${name} edited`);
        const saved = page.waitForResponse((response) => response.url().endsWith(`/api/deals/${id}`)
            && response.request().method() === 'PUT');
        await editor.getByRole('button', { name: message('en', 'deals', 'DealsQuickEditSheet.save'), exact: true }).click();
        expect((await saved).status()).toBe(200);
        await expect(editor).not.toBeVisible();
        const stored = await context.request.get(`/api/deals/${id}`);
        expect(await stored.json()).toMatchObject({ name: `${name} edited`, value: 100 });
    } finally {
        releaseRefresh.resolve();
        await page.unrouteAll({ behavior: 'wait' });
    }
});
