import { expect, test } from '@playwright/test';

import { useLocale } from './support/locale';
import { message } from './support/messages';

test.describe('calendar', () => {
    test('uses the canonical page header and an in-flow create action @mobile', async ({ page }) => {
        await useLocale(page, 'en');
        await page.goto('/activity/calendar');

        const heading = page.getByRole('heading', {
            level: 1,
            name: message('en', 'calendar', 'Calendar.title'),
        });
        await expect(heading).toBeVisible();
        await expect(heading).toHaveClass(/text-4xl/);

        const create = page.getByRole('button', {
            name: message('en', 'calendar', 'Calendar.quickCreate'),
            exact: true,
        });
        await expect(create).toBeVisible();
        await expect(create).toHaveCSS('position', 'static');
        await create.click();
        await expect(
            page.getByRole('menuitem', { name: message('en', 'calendar', 'Calendar.newTask') }),
        ).toBeVisible();
        await expect(
            page.getByRole('menuitem', { name: message('en', 'calendar', 'Calendar.newActivity') }),
        ).toBeVisible();
        await expect(
            page.getByRole('menuitem', { name: message('en', 'calendar', 'Calendar.newDeal') }),
        ).toBeVisible();

        await useLocale(page, 'ja');
        await page.reload();
        await expect(
            page.getByRole('heading', {
                level: 1,
                name: message('ja', 'calendar', 'Calendar.title'),
            }),
        ).toBeVisible();
        await expect(
            page.getByRole('button', {
                name: message('ja', 'calendar', 'Calendar.quickCreate'),
                exact: true,
            }),
        ).toBeVisible();
    });
});
