import { randomUUID } from 'node:crypto';
import { expect, test, type Locator, type Page } from '@playwright/test';
import { csrfBootstrap, seeder } from './support/api';
import { runFixture } from './support/fixtures';
import { useLocale } from './support/locale';
import { message } from './support/messages';

async function keyboardMove(page: Page, handle: Locator, direction: 'ArrowRight' | 'ArrowLeft', destination: string) {
    await handle.focus();
    await expect(handle).toBeFocused();
    await page.keyboard.press('Space');
    await page.keyboard.press(direction);
    await expect(page.locator('[id^="DndLiveRegion"]')).toContainText(destination);
    await page.keyboard.press('Space');
}

for (const locale of ['en', 'ja'] as const) {
    for (const won of [true, false]) {
        test(`${locale}: keyboard terminal move confirms, retains ${won ? 'won' : 'lost'}, and explicitly reopens`, async ({ page, context }, testInfo) => {
            await page.setViewportSize({ width: 1600, height: 1000 });
            await useLocale(context, locale);
            const fixture = runFixture(testInfo.project.name);
            const csrf = await csrfBootstrap(context.request);
            const seed = seeder(context.request, fixture.workspaceId, csrf);
            const suffix = randomUUID();
            const name = `Board ${suffix}`;
            const pipeline = await seed.post('/api/pipelines', { name });
            const openStage = await seed.post(`/api/pipelines/${pipeline.id}/stages`, { name: `Proposal ${suffix}`, position: 0, success: false, failure: false });
            const terminalStage = await seed.post(`/api/pipelines/${pipeline.id}/stages`, { name: `${won ? 'Signed' : 'Declined'} ${suffix}`, position: 1, success: won, failure: !won });
            const review = await seed.post('/api/duplicate-preflight/deals', { name, companyId: null });
            const deal = await seed.post('/api/deals', { name, pipeline: pipeline.id, stage: openStage.id, value: 100, currency: 'JPY', duplicateReviewToken: review.reviewToken });
            if (typeof deal.id !== 'number' || typeof terminalStage.name !== 'string' || typeof openStage.name !== 'string') throw new Error('Invalid board fixture');
            const id = deal.id;
            await page.goto(`/records/deals?q=${encodeURIComponent(name)}`);
            await page.getByRole('button', { name: message(locale, 'deals', 'DealsBrowser.kanbanView'), exact: true }).click();
            const handleName = message(locale, 'deals', 'DealsKanban.moveCard').replace('{name}', name);
            const handle = page.getByRole('button', { name: handleName, exact: true });
            await expect(handle).toBeVisible();
            await expect(page.locator('[data-kanban-card][role="button"]')).toHaveCount(0);
            const outline = await handle.evaluate((button) => {
                button.focus();
                const card = button.closest('[data-kanban-card]');
                return card ? getComputedStyle(card).outlineStyle : '';
            });
            expect(outline).not.toBe('none');
            await page.emulateMedia({ forcedColors: 'active' });
            expect(await handle.evaluate((button) => {
                const card = button.closest('[data-kanban-card]');
                return card ? getComputedStyle(card).outlineWidth : '';
            })).not.toBe('0px');
            await page.emulateMedia({ forcedColors: 'none' });

            let moves = 0;
            page.on('request', (request) => { if (request.url().endsWith(`/api/deals/${id}/move`)) moves++; });
            await keyboardMove(page, handle, 'ArrowRight', terminalStage.name);
            const dialog = page.getByRole('dialog');
            await expect(dialog).toBeVisible();
            expect(moves).toBe(0);
            await dialog.getByRole('button', { name: message(locale, 'deals', 'DealsKanban.cancelMove'), exact: true }).click();
            await expect(dialog).not.toBeVisible();
            await expect(handle).toBeFocused();
            expect(moves).toBe(0);
            const unchanged = await context.request.get(`/api/deals/${id}`);
            const original: unknown = await unchanged.json();
            expect(original).toMatchObject({ stage: openStage.id });
            if (typeof original !== 'object' || original === null) throw new Error('Invalid deal response');
            expect('won' in original ? original.won : null).toBeNull();

            await keyboardMove(page, handle, 'ArrowRight', terminalStage.name);
            const saved = page.waitForResponse((response) => response.url().endsWith(`/api/deals/${id}/move`));
            await dialog.getByRole('button', { name: message(locale, 'deals', 'DealsKanban.confirmMove'), exact: true }).click();
            expect(await (await saved).json()).toMatchObject({ stage: terminalStage.id, won });
            await expect(handle).toBeFocused();
            const boardStatus = page.getByRole('status').filter({ hasText: name }).filter({ hasText: terminalStage.name });
            await expect(boardStatus.first()).toContainText(message(locale, 'deals', `DealsKanban.outcome.${won ? 'won' : 'lost'}`));

            const back = page.waitForResponse((response) => response.url().endsWith(`/api/deals/${id}/move`));
            await keyboardMove(page, handle, 'ArrowLeft', openStage.name);
            expect(await (await back).json()).toMatchObject({ stage: openStage.id, won });
            await expect(dialog).not.toBeVisible();
            const retained = message(locale, 'deals', 'DealsKanban.movedRetained')
                .replace('{name}', name).replace('{column}', openStage.name).replace('{outcome}', message(locale, 'deals', `DealsKanban.outcome.${won ? 'won' : 'lost'}`));
            await expect(page.getByRole('status').filter({ hasText: retained }).first()).toBeVisible();
            const reopened = page.waitForResponse((response) => response.url().endsWith(`/api/deals/${id}/reopen`));
            await page.getByRole('button', { name: message(locale, 'deals', 'DealsKanban.reopen'), exact: true }).click();
            expect((await reopened).ok()).toBe(true);
            const stored = await context.request.get(`/api/deals/${id}`);
            const body: unknown = await stored.json();
            expect(body).toMatchObject({ stage: openStage.id });
            if (typeof body !== 'object' || body === null) throw new Error('Invalid reopened deal response');
            expect('won' in body ? body.won : null).toBeNull();
            await page.reload();
            await expect(handle).toBeVisible();
            await expect(page.locator('[data-kanban-card]').filter({ hasText: name })).toContainText(message(locale, 'deals', 'DealsCard.statusOpen'));
        });
    }
}

for (const locale of ['en', 'ja'] as const) {
    test(`${locale}: task keyboard move announces its live destination and keeps a separate open control`, async ({ page, context }, testInfo) => {
        await page.setViewportSize({ width: 1600, height: 1000 });
        await useLocale(context, locale);
        const fixture = runFixture(testInfo.project.name);
        const csrf = await csrfBootstrap(context.request);
        const seed = seeder(context.request, fixture.workspaceId, csrf);
        const me = await context.request.get('/api/auth/me');
        const user: unknown = await me.json();
        if (typeof user !== 'object' || user === null || !('id' in user) || typeof user.id !== 'number') throw new Error('Invalid current user');
        const name = `Task board ${randomUUID()}`;
        const task = await seed.post('/api/tasks', { description: name, assignedToId: user.id, completed: false });
        const other = await seed.post('/api/tasks', { description: `${name} target`, assignedToId: user.id, completed: false });
        await seed.post(`/api/tasks/${other.id}/move`, { status: 'in_progress', position: 0 });
        await page.goto(`/activity/tasks?q=${encodeURIComponent(name)}`);
        await page.getByRole('button', { name: message(locale, 'activity', 'ActivityTasks.viewBoard'), exact: true }).click();
        const handle = page.getByRole('button', { name: message(locale, 'activity', 'ActivityTasks.moveCard').replace('{name}', name), exact: true });
        await expect(handle).toBeVisible();
        const saved = page.waitForResponse((response) => response.url().endsWith(`/api/tasks/${task.id}/move`));
        await keyboardMove(page, handle, 'ArrowRight', message(locale, 'activity', 'ActivityTasks.statusInProgress'));
        expect(await (await saved).json()).toMatchObject({ status: 'in_progress' });
        await expect(handle).toBeFocused();
        const card = page.locator('[data-kanban-card]').filter({ has: handle });
        await expect(card).not.toHaveAttribute('role', 'button');
        await card.getByRole('button', { name: message(locale, 'activity', 'ActivityTasks.openTask'), exact: true }).click();
        await expect(page.getByRole('dialog')).toBeVisible();
    });
}
