import { randomUUID } from 'node:crypto';
import { expect, test } from '@playwright/test';
import { activeWorkspaceId, csrfBootstrap, registerUser, seeder } from './support/api';
import { useLocale } from './support/locale';
import { message } from './support/messages';

test.use({ storageState: { cookies: [], origins: [] } });
test.describe.configure({ timeout: 240_000 });

function id(body: Record<string, unknown>): number {
    if (typeof body.id !== 'number') throw new Error('Expected a created record id');
    return body.id;
}

for (const locale of ['en', 'ja'] as const) {
    test(`record activity and task pages retain context and readable tablet rows (${locale}) @mobile`, async ({ page }, testInfo) => {
        const key = randomUUID();
        await registerUser(page.request, {
            username: `list_${key.replaceAll('-', '')}`,
            password: `Lists!${key}A1`,
            email: `list-${key}@example.invalid`,
        });
        await useLocale(page, locale);
        const workspaceId = await activeWorkspaceId(page.request);
        const seed = seeder(page.request, workspaceId, await csrfBootstrap(page.request));
        const company = await seed.post('/api/companies', { name: `List company ${key}` });
        const contact = await seed.post('/api/persons', { name: `List contact ${key}`, companyId: id(company) });
        const unrelated = await seed.post('/api/persons', { name: `Unrelated contact ${key}` });
        const meResponse = await page.request.get('/api/auth/me');
        const me: unknown = await meResponse.json();
        if (me === null || typeof me !== 'object' || !('id' in me) || typeof me.id !== 'number') throw new Error('Missing current user');
        const descriptions = Array.from({ length: 26 }, (_, index) => `Follow up on the proposal and confirm delivery ${index} ${key}`);
        for (const description of descriptions) {
            await seed.post('/api/activities', { subject: description, type: 'Call', personId: id(contact), timestamp: '2026-01-01 12:00:00' });
            await seed.post('/api/tasks', { description, personId: id(contact), assignedToId: me.id, dueDate: '2026-01-01' });
        }
        const unrelatedTitle = `Unrelated activity ${key}`;
        await seed.post('/api/activities', { subject: unrelatedTitle, type: 'Call', personId: id(unrelated) });
        await seed.post('/api/tasks', { description: unrelatedTitle, personId: id(unrelated), assignedToId: me.id });

        for (const [recordPath, parameter, recordId, filter] of [
            ['companies', 'companyId', id(company), 'companies'],
            ['contacts', 'contactId', id(contact), 'persons'],
        ] as const) {
            for (const [path, endpoint] of [['all', 'activities'], ['tasks', 'tasks']] as const) {
                await page.goto(`/records/${recordPath}/${recordId}`);
                const start = Date.now();
                const response = page.waitForResponse((result) => {
                    const url = new URL(result.url());
                    return url.pathname === `/api/${endpoint}/page` && url.searchParams.get(filter) === String(recordId);
                });
                await page.locator(`a[href="/activity/${path}?${parameter}=${recordId}"]`).click();
                expect((await response).ok()).toBe(true);
                await expect(page.getByText(unrelatedTitle, { exact: true })).toBeHidden();
                await expect(page.getByText(descriptions[endpoint === 'tasks' ? 0 : 25], { exact: true })).toBeVisible();
                await testInfo.attach(`${endpoint}-${recordPath}-timing`, {
                    body: JSON.stringify({ readyMs: Date.now() - start, records: 26, pageSize: 25, reportBaselineMs: [61700, 91900] }),
                    contentType: 'application/json',
                });
                await page.getByRole('button', { name: message(locale, 'activity', 'ActivityList.next'), exact: true }).click();
                await expect.poll(() => new URL(page.url()).searchParams.get('page')).toBe('2');
                await expect(page.getByText(descriptions[endpoint === 'tasks' ? 25 : 0], { exact: true })).toBeVisible();
                await page.reload();
                await expect.poll(() => new URL(page.url()).searchParams.get(filter)).toBe(String(recordId));
                await expect(page.getByText(descriptions[endpoint === 'tasks' ? 25 : 0], { exact: true })).toBeVisible();
                await expect(page.getByText(unrelatedTitle, { exact: true })).toBeHidden();
            }
        }

        await page.goto(`/activity/tasks?companyId=${id(company)}`);
        const title = page.locator('li').getByText(descriptions[0], { exact: true });
        await expect(title).toBeVisible();
        const titleBounds = await title.boundingBox();
        if (!titleBounds) throw new Error('Task title has no rendered bounds');
        await page.mouse.click(titleBounds.x + Math.min(20, titleBounds.width / 2), titleBounds.y + titleBounds.height / 2);
        await expect(page.getByRole('dialog')).toBeVisible();
        await page.getByRole('dialog').getByRole('button', { name: message(locale, 'activity', 'ActivityEditTaskSheet.cancel'), exact: true }).click();
        await expect(page.getByRole('dialog')).toBeHidden();
        for (const width of [768, 834, 960, 1050]) {
            await page.setViewportSize({ width, height: 1194 });
            for (const dark of [false, true]) {
                await page.evaluate((enabled) => document.documentElement.classList.toggle('dark', enabled), dark);
                const bounds = await title.boundingBox();
                expect(bounds?.width).toBeGreaterThan(100);
                const row = title.locator('xpath=ancestor::li[1]');
                const actions = row.getByRole('button').last();
                const actionBounds = await actions.boundingBox();
                expect(actionBounds).not.toBeNull();
                expect((actionBounds?.x ?? width) + (actionBounds?.width ?? width)).toBeLessThanOrEqual(width);
                await actions.click();
                await expect(page.getByRole('menu')).toBeVisible();
                await page.keyboard.press('Escape');
            }
        }
    });
}
