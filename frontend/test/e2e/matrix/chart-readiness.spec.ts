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
    ['empty area curve', '<path class="recharts-area-curve" d="" stroke="black" />'],
    ['unstroked flat area', '<path class="recharts-area-curve" d="M0 50L50 50" fill="none" />'],
    ['unpainted bar', '<g class="recharts-bar-rectangles"><rect width="30" height="50" fill="none" /></g>'],
    ['unpainted bar path', '<g class="recharts-bar-rectangles"><path d="M0 0L50 0L50 50L0 50Z" fill="none" /></g>'],
    ['transparent fill', '<g class="recharts-bar-rectangles"><rect width="30" height="50" fill="transparent" /></g>'],
    ['transparent line stroke', '<path class="recharts-line-curve" d="M0 0L25 50L50 0" fill="none" stroke="transparent" />'],
    ['zero-alpha fill color', '<g class="recharts-bar-rectangles"><rect width="30" height="50" fill="rgba(10, 20, 30, 0)" /></g>'],
    ['zero-alpha flat stroke color', '<path class="recharts-area-curve" d="M0 50L50 50" fill="none" stroke="rgba(10, 20, 30, 0)" />'],
    ['zero-alpha wide-gamut color', '<g class="recharts-bar-rectangles"><rect width="30" height="50" fill="color(display-p3 0.1 0.2 0.3 / 0)" /></g>'],
    ['zero fill opacity', '<g class="recharts-bar-rectangles"><rect width="30" height="50" fill-opacity="0" /></g>'],
    ['zero stroke opacity', '<path class="recharts-line-curve" d="M0 0L25 50L50 0" fill="none" stroke="black" stroke-opacity="0" />'],
    ['zero stroke width', '<path class="recharts-line-curve" d="M0 0L25 50L50 0" fill="none" stroke="black" stroke-width="0" />'],
    ['zero mark opacity', '<g class="recharts-bar-rectangles"><rect width="30" height="50" opacity="0" /></g>'],
    ['zero ancestor opacity', '<g class="recharts-bar-rectangles" opacity="0"><rect width="30" height="50" /></g>'],
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
    ['flat area', `<g class="recharts-area">
        <path class="recharts-area-area" d="M0 50L50 50L50 50L0 50Z" />
        <path class="recharts-area-curve" d="M0 50L50 50" fill="none" stroke="black" />
    </g>`],
    ['line dot', '<g class="recharts-line-dots"><circle cx="30" cy="30" r="3" /></g>'],
    ['stroke-only bar', '<g class="recharts-bar-rectangles"><rect width="30" height="50" fill="none" stroke="black" /></g>'],
    ['fill-only bar with transparent stroke', '<g class="recharts-bar-rectangles"><rect width="30" height="50" stroke="transparent" /></g>'],
    ['partially transparent bar', '<g class="recharts-bar-rectangles" opacity="0.5"><rect width="30" height="50" fill="rgba(10, 20, 30, 0.5)" fill-opacity="0.5" opacity="0.5" /></g>'],
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
