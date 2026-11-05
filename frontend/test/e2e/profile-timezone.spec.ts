import { randomUUID } from 'node:crypto';
import { expect, test } from '@playwright/test';
import { activeWorkspaceId, csrfBootstrap, registerUser, seeder } from './support/api';
import { useLocale } from './support/locale';
import { message } from './support/messages';

function idFrom(value: unknown): number {
    if (typeof value !== 'object' || value === null || !('id' in value) || typeof value.id !== 'number') {
        throw new Error('Expected an API record id');
    }
    return value.id;
}

for (const locale of ['en', 'ja'] as const) {
    test(`Japan profile preserves due dates and activity times in ${locale} @mobile`, async ({ browser }) => {
        test.setTimeout(120_000);
        const context = await browser.newContext({
            storageState: { cookies: [], origins: [] },
            timezoneId: 'America/Los_Angeles',
        });
        try {
            const unique = randomUUID();
            await registerUser(context.request, {
                username: `tz-${unique}`, password: `Tz-${randomUUID()}!`,
                email: `tz-${unique}@example.test`, displayName: '佐藤 美咲',
            });
            const csrf = await csrfBootstrap(context.request);
            const headers = { [csrf.headerName]: csrf.token };
            expect((await context.request.patch('/api/users/me', { headers, data: { timezone: 'Asia/Tokyo' } })).ok()).toBe(true);
            const me = await context.request.get('/api/auth/me');
            expect(me.ok()).toBe(true);
            const userId = idFrom(await me.json());
            const workspaceId = await activeWorkspaceId(context.request);
            const write = seeder(context.request, workspaceId, csrf);
            const dueDate = new Date(Date.now() + (72 + 9) * 3_600_000).toISOString().slice(0, 10);
            const taskTitle = `Timezone task ${unique}`;
            await write.post('/api/tasks', { description: taskTitle, assignedToId: userId, dueDate });
            const activityTitle = `Timezone activity ${unique}`;
            const activity = await write.post('/api/activities', {
                type: 'Call', subject: activityTitle, createdById: userId, timestamp: '2026-06-01 23:30:00',
            });
            const activityId = idFrom(activity);
            const page = await context.newPage();
            await useLocale(page, locale);
            await page.goto('/me');
            await expect(page.getByText(taskTitle, { exact: true }).first()).toBeVisible();
            const day = new Intl.DateTimeFormat(locale, { timeZone: 'UTC', month: 'short', day: 'numeric' })
                .format(new Date(`${dueDate}T00:00:00Z`));
            const dueLabel = message(locale, 'me', 'MeTimeline.due').replace('{date}', day);
            await expect(page.getByText(dueLabel, { exact: true }).first()).toBeVisible();

            await page.goto(`/activity/activities/${activityId}`);
            const instant = new Date('2026-06-01T23:30:00Z');
            const tokyoTime = new Intl.DateTimeFormat(locale, { timeZone: 'Asia/Tokyo', dateStyle: 'medium', timeStyle: 'short' }).format(instant);
            await expect(page.getByText(tokyoTime, { exact: true }).first()).toBeVisible();
            expect((await context.request.patch('/api/users/me', { headers, data: { timezone: 'UTC' } })).ok()).toBe(true);
            await page.reload();
            const utcTime = new Intl.DateTimeFormat(locale, { timeZone: 'UTC', dateStyle: 'medium', timeStyle: 'short' }).format(instant);
            await expect(page.getByText(utcTime, { exact: true }).first()).toBeVisible();
            await page.goto('/me');
            await expect(page.getByText(dueLabel, { exact: true }).first()).toBeVisible();
            await page.goto('/settings/personal/profile');
            const picker = page.getByRole('combobox', { name: message(locale, 'account', 'AccountProfile.timezoneLabel') });
            await picker.fill(locale === 'ja' ? '日本' : 'Japan');
            await expect(page.getByRole('option', { name: /Asia\/Tokyo/ })).toBeVisible();
            await expect(page.getByRole('option')).toHaveCount(1);
            await page.keyboard.press('Escape');
            await page.goto('/dashboard');
            if (locale === 'ja') await expect(page.getByRole('heading', { level: 1 })).toContainText('佐藤 美咲');
        } finally {
            await context.close();
        }
    });
}
