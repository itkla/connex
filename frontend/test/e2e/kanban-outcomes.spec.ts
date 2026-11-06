import { randomUUID } from 'node:crypto';
import { expect, test, type Locator, type Page } from '@playwright/test';
import { csrfBootstrap, seeder } from './support/api';
import { runFixture } from './support/fixtures';
import { useLocale } from './support/locale';
import { message } from './support/messages';
import type { DealRisk } from '@/app/lib/types';

async function keyboardMove(page: Page, handle: Locator, direction: 'ArrowRight' | 'ArrowLeft', sourceAnnouncement: string, destinationAnnouncement: string) {
    await handle.focus();
    await expect(handle).toBeFocused();
    await page.keyboard.press('Space');
    await expect(page.locator('[id^="DndLiveRegion"]')).toHaveText(sourceAnnouncement);
    await page.keyboard.press(direction);
    await expect(page.locator('[id^="DndLiveRegion"]')).toHaveText(destinationAnnouncement);
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
            const over = (column: string) => message(locale, 'deals', 'DealsKanban.a11yOver').replace('{name}', name).replace('{column}', column);
            const sourceColumn = page.locator('[data-kanban-board] > section').filter({
                has: page.getByRole('heading', { name: openStage.name, exact: true, includeHidden: true }),
            });
            const risk: DealRisk = { dealId: id, level: 'high', score: 90, factors: [{ code: 'no_stakeholders', severity: 'high', params: {} }], assessedAt: '2026-11-05T00:00:00Z', value: 100, currency: 'JPY' };
            await page.route(/\/api\/deals\/risk\?/, (route) => route.fulfill({ json: [risk] }));
            await page.goto(`/records/deals?q=${encodeURIComponent(name)}`);
            await page.getByRole('button', { name: message(locale, 'deals', 'DealsBrowser.kanbanView'), exact: true }).click();
            const handleName = message(locale, 'deals', 'DealsKanban.moveCard').replace('{name}', name);
            const handle = page.getByRole('button', { name: handleName, exact: true });
            await expect(handle).toBeVisible();
            const riskTrigger = page.locator('[data-kanban-card] [data-slot="tooltip-trigger"]').filter({ hasText: message(locale, 'deals', 'DealRisk.levelHigh') });
            await expect(riskTrigger).toHaveAttribute('tabindex', '0');
            await riskTrigger.hover();
            await expect(page.getByRole('tooltip')).toContainText(message(locale, 'deals', 'DealRisk.factorNoStakeholders'));
            await handle.focus();
            await riskTrigger.focus();
            await expect(riskTrigger).toBeFocused();
            await expect(page.getByRole('tooltip')).toContainText(message(locale, 'deals', 'DealRisk.factorNoStakeholders'));
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
            await keyboardMove(page, handle, 'ArrowRight', over(openStage.name), over(terminalStage.name));
            const dialog = page.getByRole('dialog');
            await expect(dialog).toBeVisible();
            await expect(sourceColumn.locator('[data-kanban-card]')).toContainText(name);
            await expect(dialog).toContainText(won ? message(locale, 'deals', 'DealsKanban.confirmValueFreshWin').replace('{current}', '100.00 JPY').replace('{currency}', 'JPY') : message(locale, 'deals', 'DealsKanban.confirmValueLost').replace('{current}', '0.00 JPY').replace('{amount}', '0.00 JPY'));
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

            await keyboardMove(page, handle, 'ArrowRight', over(openStage.name), over(terminalStage.name));
            const [saved] = await Promise.all([
                page.waitForResponse((response) => response.request().method() === 'POST' && response.url().endsWith(`/api/deals/${id}/move`)),
                dialog.getByRole('button', { name: message(locale, 'deals', 'DealsKanban.confirmMove'), exact: true }).click(),
            ]);
            expect(saved.ok()).toBe(true);
            expect(await saved.json()).toMatchObject({ stage: terminalStage.id, won, position: 0 });
            await expect(handle).toBeFocused();
            const boardStatus = page.getByRole('status').filter({ hasText: name }).filter({ hasText: terminalStage.name });
            await expect(boardStatus.first()).toContainText(message(locale, 'deals', `DealsKanban.outcome.${won ? 'won' : 'lost'}`));
            await expect(boardStatus.first()).toContainText(locale === 'en' ? 'position 1' : '1番目');

            const [back] = await Promise.all([
                page.waitForResponse((response) => response.request().method() === 'POST' && response.url().endsWith(`/api/deals/${id}/move`)),
                keyboardMove(page, handle, 'ArrowLeft', over(terminalStage.name), over(openStage.name)),
            ]);
            expect(back.ok()).toBe(true);
            expect(await back.json()).toMatchObject({ stage: openStage.id, won });
            await expect(dialog).not.toBeVisible();
            const retained = message(locale, 'deals', 'DealsKanban.movedRetained')
                .replace('{name}', name).replace('{column}', openStage.name).replace('{outcome}', message(locale, 'deals', `DealsKanban.outcome.${won ? 'won' : 'lost'}`)).replace('{position}', '1');
            await expect(page.getByRole('status').filter({ hasText: retained }).first()).toBeVisible();
            const [reopened] = await Promise.all([
                page.waitForResponse((response) => response.request().method() === 'POST' && response.url().endsWith(`/api/deals/${id}/reopen`)),
                page.getByRole('button', { name: message(locale, 'deals', 'DealsKanban.reopen'), exact: true }).click(),
            ]);
            expect(reopened.ok()).toBe(true);
            const reopenedDeal: unknown = await reopened.json();
            expect(reopenedDeal).toMatchObject({ stage: openStage.id });
            if (typeof reopenedDeal !== 'object' || reopenedDeal === null || !('position' in reopenedDeal) || typeof reopenedDeal.position !== 'number') throw new Error('Invalid reopened deal position');
            const reopenedMessage = message(locale, 'deals', 'DealsKanban.moved').replace('{name}', name).replace('{column}', openStage.name)
                .replace('{position}', String(reopenedDeal.position + 1)).replace('{outcome}', message(locale, 'deals', 'DealsKanban.outcome.open'));
            await expect(page.getByRole('status').filter({ hasText: reopenedMessage }).first()).toBeVisible();
            await expect(sourceColumn.locator('[data-kanban-card]')).toContainText(message(locale, 'deals', 'DealsCard.statusOpen'));
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
        await page.goto('/activity/tasks');
        await page.getByRole('button', { name: message(locale, 'activity', 'ActivityTasks.viewBoard'), exact: true }).click();
        await page.getByPlaceholder(message(locale, 'activity', 'ActivityTasks.searchPlaceholder'), { exact: true }).fill(name);
        await expect(page.locator('[data-kanban-card]')).toHaveCount(2);
        const handle = page.getByRole('button', { name: message(locale, 'activity', 'ActivityTasks.moveCard').replace('{name}', name), exact: true });
        await expect(handle).toBeVisible();
        const over = (column: string) => message(locale, 'activity', 'ActivityTasks.a11yOver').replace('{name}', name).replace('{column}', column);
        const [saved] = await Promise.all([
            page.waitForResponse((response) => response.request().method() === 'POST' && response.url().endsWith(`/api/tasks/${task.id}/move`)),
            keyboardMove(page, handle, 'ArrowRight', over(message(locale, 'activity', 'ActivityTasks.statusTodo')), over(message(locale, 'activity', 'ActivityTasks.statusInProgress'))),
        ]);
        expect(saved.ok()).toBe(true);
        const savedTask: unknown = await saved.json();
        expect(savedTask).toMatchObject({ status: 'in_progress' });
        if (typeof savedTask !== 'object' || savedTask === null || !('position' in savedTask) || typeof savedTask.position !== 'number') throw new Error('Invalid saved task position');
        const savedMessage = message(locale, 'activity', 'ActivityTasks.a11ySaved').replace('{name}', name)
            .replace('{column}', message(locale, 'activity', 'ActivityTasks.statusInProgress')).replace('{position}', String(savedTask.position + 1));
        await expect(page.getByRole('status').filter({ hasText: savedMessage }).first()).toBeVisible();
        await expect(handle).toBeEnabled();
        await expect(handle).toBeFocused();
        const card = page.locator('[data-kanban-card]').filter({ has: handle });
        await expect(card).not.toHaveAttribute('role', 'button');
        await card.getByRole('button', { name: message(locale, 'activity', 'ActivityTasks.openTask'), exact: true }).click();
        await expect(page.getByRole('dialog')).toBeVisible();
    });
}
