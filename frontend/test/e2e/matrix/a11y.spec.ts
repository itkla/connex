import { test, expect } from '@playwright/test';

import { routesForTier, type MatrixRoute } from './routes';
import { blockExternalRequests, matrixContext, record, type Axes } from './support/matrix';

/**
 * Accessibility axes of the Wave 4 (#856) route/state matrix: keyboard navigation, focus order,
 * screen-reader names, and 200% zoom / large text.
 *
 * These are the axes #856 names that no existing suite covers. They are asserted rather than
 * eyeballed because "we tabbed through it once" is not evidence anyone can re-run.
 */

const DESKTOP: Axes = { viewport: 'desktop', locale: 'en', theme: 'light' };

/** How many Tab stops to walk before concluding the order is sane. */
const TAB_DEPTH = 25;

/** One observed keyboard stop. */
type FocusStop = {
    index: number;
    tag: string;
    name: string;
    visible: boolean;
    outsideViewport: boolean;
};

/**
 * Walks the focus order from the top of the document, recording what a screen-reader user would
 * hear at each stop.
 *
 * The accessible name is computed the way assistive technology resolves it — `aria-label`, then
 * `aria-labelledby`, then `title`/`alt`, then trimmed text — because an interactive control whose
 * name resolves to empty is announced as bare "button", which is the defect this looks for.
 */
async function walkFocusOrder(page: import('@playwright/test').Page, depth: number): Promise<FocusStop[]> {
    await page.evaluate(() => {
        const first = document.body;
        first.setAttribute('tabindex', '-1');
        first.focus();
    });
    const stops: FocusStop[] = [];
    for (let index = 0; index < depth; index += 1) {
        await page.keyboard.press('Tab');
        const stop = await page.evaluate(() => {
            const el = document.activeElement as HTMLElement | null;
            if (!el || el === document.body) return null;
            const labelledBy = el.getAttribute('aria-labelledby');
            const labelledText = labelledBy
                ? labelledBy
                      .split(/\s+/)
                      .map((id) => document.getElementById(id)?.textContent ?? '')
                      .join(' ')
                : '';
            const name = (
                el.getAttribute('aria-label')
                ?? (labelledText.trim() || null)
                ?? el.getAttribute('title')
                ?? (el as HTMLImageElement).alt
                ?? el.textContent
                ?? ''
            )
                .replace(/\s+/g, ' ')
                .trim();
            const rect = el.getBoundingClientRect();
            const style = window.getComputedStyle(el);
            return {
                tag: el.tagName.toLowerCase(),
                name,
                visible: style.visibility !== 'hidden' && style.display !== 'none' && rect.width > 0 && rect.height > 0,
                outsideViewport: rect.bottom < 0 || rect.top > window.innerHeight * 4,
            };
        });
        if (stop === null) break;
        stops.push({ index, ...stop });
    }
    return stops;
}

test.describe('keyboard navigation and focus order', () => {
    for (const route of routesForTier(1)) {
        test(`${route.id} exposes a usable focus order`, async ({ browser }) => {
            const context = await matrixContext(browser, { ...DESKTOP, role: route.role });
            blockExternalRequests(context);
            const page = await context.newPage();
            await page.goto(route.path, { waitUntil: 'domcontentloaded' });
            await page.waitForLoadState('networkidle').catch(() => undefined);

            const stops = await walkFocusOrder(page, TAB_DEPTH);
            const unnamed = stops.filter((stop) => stop.name.length === 0);
            const invisible = stops.filter((stop) => !stop.visible);

            await record(page, {
                routeId: route.id,
                path: route.path,
                state: 'keyboard-focus-order',
                axes: { ...DESKTOP, role: route.role },
                faults: [],
                httpStatus: 200,
                notes: `stops=${stops.length} unnamed=${unnamed.length} invisible=${invisible.length} :: `
                    + stops.slice(0, 12).map((stop) => `${stop.index}:${stop.tag}"${stop.name.slice(0, 24)}"`).join(' | '),
            });

            expect(stops.length, `${route.path} must expose keyboard-reachable controls`).toBeGreaterThan(0);
            expect(
                unnamed.map((stop) => `${stop.index}:${stop.tag}`),
                `${route.path} has focusable controls with no accessible name`,
            ).toEqual([]);
            expect(
                invisible.map((stop) => `${stop.index}:${stop.tag}"${stop.name}"`),
                `${route.path} focuses controls that are not visible`,
            ).toEqual([]);
            await context.close();
        });
    }
});

test.describe('200% zoom / large text reflow', () => {
    /**
     * Browser zoom to 200% halves the CSS viewport, so a 1440x900 desktop reflows to 720x450. WCAG
     * 1.4.10 requires content to reflow without a horizontal scrollbar; a page that overflows
     * sideways at this size is unusable for a low-vision user.
     */
    for (const route of routesForTier(1)) {
        test(`${route.id} reflows at 200% zoom`, async ({ browser }) => {
            const context = await matrixContext(browser, { ...DESKTOP, role: route.role });
            blockExternalRequests(context);
            const page = await context.newPage();
            await page.setViewportSize({ width: 720, height: 450 });
            await page.goto(route.path, { waitUntil: 'domcontentloaded' });
            await page.waitForLoadState('networkidle').catch(() => undefined);

            const overflow = await page.evaluate(() => ({
                scrollWidth: document.documentElement.scrollWidth,
                clientWidth: document.documentElement.clientWidth,
            }));

            await record(page, {
                routeId: route.id,
                path: route.path,
                state: 'zoom-200',
                axes: { ...DESKTOP, role: route.role },
                faults: [],
                httpStatus: 200,
                notes: `scrollWidth=${overflow.scrollWidth} clientWidth=${overflow.clientWidth}`,
            });

            expect(
                overflow.scrollWidth,
                `${route.path} overflows horizontally at 200% zoom (${overflow.scrollWidth} > ${overflow.clientWidth})`,
            ).toBeLessThanOrEqual(overflow.clientWidth + 2);
            await context.close();
        });
    }
});

test.describe('reduced motion and the animated path', () => {
    /**
     * The critical-flow suite pins `reducedMotion: "reduce"` globally, so the animated branch has
     * never been exercised by an automated run. Both branches are rendered here; a route that only
     * settles under `reduce` is a route whose animation can strand content.
     */
    const MOTION_ROUTES: readonly MatrixRoute[] = routesForTier(1).slice(0, 6);

    for (const route of MOTION_ROUTES) {
        for (const motion of ['reduce', 'no-preference'] as const) {
            test(`${route.id} settles with motion=${motion}`, async ({ browser }) => {
                const context = await matrixContext(browser, { ...DESKTOP, role: route.role, motion });
                blockExternalRequests(context);
                const page = await context.newPage();
                await page.goto(route.path, { waitUntil: 'domcontentloaded' });
                await page.waitForLoadState('networkidle').catch(() => undefined);
                await page.waitForTimeout(1_200);

                const heading = page.locator('h1').first();
                const opacity = await heading
                    .evaluate((el) => window.getComputedStyle(el).opacity)
                    .catch(() => '1');

                await record(page, {
                    routeId: route.id,
                    path: route.path,
                    state: `motion-${motion}`,
                    axes: { ...DESKTOP, role: route.role, motion },
                    faults: [],
                    httpStatus: 200,
                    notes: `h1 opacity after settle: ${opacity}`,
                });

                await expect(heading, `${route.path} heading must be visible with motion=${motion}`).toBeVisible();
                expect(
                    Number(opacity),
                    `${route.path} heading is still transparent after animations settle (motion=${motion})`,
                ).toBeGreaterThan(0.9);
                await context.close();
            });
        }
    }
});
