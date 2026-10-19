import { expect, test } from '@playwright/test';

import { message } from '../support/messages';
import { expectChartsSettled } from './support/matrix';

for (const [name, series] of [
    ['empty pie group', '<g class="recharts-pie"></g>'],
    ['empty bar group', '<g class="recharts-bar-rectangles"></g>'],
    ['zero-sized pie path', '<g class="recharts-pie"><path d="M0 0Z" /></g>'],
    ['zero-height bar', '<g class="recharts-bar-rectangles"><rect width="30" height="0" /></g>'],
    ['empty dot group', '<g class="recharts-line-dots"></g>'],
    ['axes without series', '<path d="M0 0L50 50" />'],
] as const) {
    test(`chart readiness rejects ${name}`, async ({ page }) => {
        await page.setContent(`<main><div class="recharts-responsive-container" style="width:100px;height:100px">
            <svg class="recharts-surface" width="100" height="100">${series}</svg>
        </div></main>`);

        await expect(expectChartsSettled(page.locator('main'), 150)).rejects.toThrow('stable painted series');
    });
}

for (const [name, series] of [
    ['pie path', '<g class="recharts-pie"><path d="M0 0L50 0L0 50Z" /></g>'],
    ['bar path', '<g class="recharts-bar-rectangles"><path d="M0 0L50 0L50 50L0 50Z" /></g>'],
    ['bar rect', '<g class="recharts-bar-rectangles"><rect width="30" height="50" /></g>'],
    ['flat line', '<path class="recharts-line-curve" d="M0 50L50 50" stroke="black" />'],
    ['line dot', '<g class="recharts-line-dots"><circle cx="30" cy="30" r="3" /></g>'],
] as const) {
    test(`chart readiness accepts a painted ${name}`, async ({ page }) => {
        await page.setContent(`<main><div class="recharts-responsive-container" style="width:100px;height:100px">
            <svg class="recharts-surface" width="100" height="100">${series}</svg>
        </div></main>`);

        await expectChartsSettled(page.locator('main'));
    });
}

for (const locale of ['en', 'ja'] as const) {
    test(`chart readiness accepts the shipped ${locale} empty state without masking an unpainted chart`, async ({ page }) => {
        await page.setContent(`<main><div class="flex h-56 items-center justify-center text-sm text-muted-foreground">
            ${message(locale, 'analytics', 'AnalyticsTaskStatus.empty')}
        </div></main>`);
        await expectChartsSettled(page.locator('main'));

        await page.locator('main').evaluate((main) => {
            main.insertAdjacentHTML('beforeend', `<div class="recharts-responsive-container" style="width:100px;height:100px">
                <svg class="recharts-surface" width="100" height="100"><g class="recharts-pie"></g></svg>
            </div>`);
        });
        await expect(expectChartsSettled(page.locator('main'), 150)).rejects.toThrow('stable painted series');
    });
}
