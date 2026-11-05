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
            test(`${surface} ${locale}: stale ${openBeforeWrite ? 'open form' : 'page before opening form'} preserves the draft across two tabs`, async ({ page, context }, testInfo) => {
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
