/**
 * Accessibility axes of the Wave 4 (#856) route/state matrix: keyboard navigation, focus order,
 * screen-reader names, visible focus, 200% zoom / large text, and the animated branch.
 *
 * These are the axes #856 names that no existing suite covers. They are asserted rather than
 * eyeballed because "we tabbed through it once" is not evidence anyone can re-run. Each context here
 * exercises conditions the ordinary sweep never reaches — tab interaction, a 720px viewport,
 * `motion=no-preference` — so each captures its own console and response faults rather than
 * recording an empty fault list it never looked for.
 *
 * Three measurement details are easy to get wrong and silently produce a test that cannot fail:
 *
 * **Overflow must be measured on `<main>`, not the document.** `ContentShell` sets
 * `document.documentElement.style.overflow = "hidden"` on mount, which pins the document's
 * `scrollWidth` to the viewport on every `(app)` route regardless of how far the content overflows.
 * `<main>` is the real scroll container, and because a nested `overflow-x-auto` region clips its own
 * overflow, measuring `<main>` also excludes the table and kanban scrollers that scroll sideways by
 * design.
 *
 * **A visible focus indicator is drawn two different ways in this codebase.** Buttons and inputs use
 * Tailwind `ring-*`, which compiles to `box-shadow`; records rows use a real `outline`. Neither
 * signal alone is sufficient, and `outline-hidden` compiles to a *transparent* 2px solid outline, so
 * a non-`none` outline style proves nothing without checking the colour's alpha. Where a control
 * delegates its ring to a wrapper — the input-group pattern — the indicator is found by walking a
 * short way up the ancestor chain, because what matters is that the user sees a ring around the
 * focused control.
 *
 * **The heading is not the content.** Every one of these routes renders its `<h1>` inside the first
 * `Rise` wrapper and its records, charts and controls inside later ones, so a heading that reaches
 * opacity 1 says nothing about whether an entrance animation stranded the rest of the page. The
 * route's top-level content sections are asserted instead.
 */

import { test, expect } from '@playwright/test';

import { routesForTier, type MatrixRoute } from './routes';
import {
    AUTHENTICATED_SHELL_SELECTOR,
    blockExternalRequests,
    captureFaults,
    captureResponseFailures,
    classifyResponseFailures,
    describeLanding,
    landingOf,
    matrixContext,
    record,
    significantFaults,
    unexpectedFaults,
    unexpectedResponseFailures,
    UNEXPECTED_LANDING_STATE,
    type Axes,
    type Landing,
    type PageFault,
    type ResponseFailure,
} from './support/matrix';

const DESKTOP: Axes = { viewport: 'desktop', locale: 'en', theme: 'light' };

const TAB_DEPTH = 25;

const CONTENT_SECTIONS = 'main [data-slot="page-shell"] > div > *';

type FocusIndicator = {
    boxShadow: string;
    outlineStyle: string;
    outlineWidth: string;
    outlineColor: string;
    present: boolean;
};

type FocusStop = {
    index: number;
    tag: string;
    name: string;
    visible: boolean;
    outsideViewport: boolean;
    indicator: FocusIndicator;
};

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
            const el = document.activeElement;
            if (!(el instanceof HTMLElement) || el === document.body) return null;
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
                ?? (el instanceof HTMLImageElement ? el.alt : null)
                ?? el.textContent
                ?? ''
            )
                .replace(/\s+/g, ' ')
                .trim();
            const rect = el.getBoundingClientRect();
            const style = window.getComputedStyle(el);
            const indicatorOn = (node: Element) => {
                const nodeStyle = window.getComputedStyle(node);
                const ring = nodeStyle.boxShadow !== 'none' && nodeStyle.boxShadow.trim().length > 0;
                const width = Number.parseFloat(nodeStyle.outlineWidth);
                const transparent = /rgba?\([^)]*,\s*0\s*\)/.test(nodeStyle.outlineColor);
                const outline = nodeStyle.outlineStyle !== 'none' && width > 0 && !transparent;
                return ring || outline;
            };
            let ancestor: Element | null = el;
            let present = false;
            for (let hop = 0; hop < 3 && ancestor !== null; hop += 1) {
                if (indicatorOn(ancestor)) {
                    present = true;
                    break;
                }
                ancestor = ancestor.parentElement;
            }
            return {
                tag: el.tagName.toLowerCase(),
                name,
                visible: style.visibility !== 'hidden' && style.display !== 'none' && rect.width > 0 && rect.height > 0,
                outsideViewport: rect.bottom < 0 || rect.top > window.innerHeight * 4,
                indicator: {
                    boxShadow: style.boxShadow,
                    outlineStyle: style.outlineStyle,
                    outlineWidth: style.outlineWidth,
                    outlineColor: style.outlineColor,
                    present,
                },
            };
        });
        if (stop === null) break;
        stops.push({ index, ...stop });
    }
    return stops;
}

type OpenedRoute = {
    status: number | null;
    landing: Landing;
    faults: PageFault[];
    responses: ResponseFailure[];
};

async function openRoute(
    page: import('@playwright/test').Page,
    route: MatrixRoute,
    faults: PageFault[],
    responses: ResponseFailure[],
): Promise<OpenedRoute> {
    const response = await page.goto(route.path, { waitUntil: 'domcontentloaded' });
    await page.waitForLoadState('networkidle').catch(() => undefined);
    const landing = await landingOf(page, route.path, route.landsOn);
    return {
        status: response?.status() ?? null,
        landing,
        faults,
        responses: classifyResponseFailures(responses, { role: route.role }),
    };
}

function assertClean(opened: OpenedRoute, route: MatrixRoute, context: string): void {
    expect(
        opened.landing.ok,
        `${route.path} was not rendered as itself (${context}) — ${describeLanding(opened.landing)}`,
    ).toBe(true);
    expect(
        unexpectedFaults(opened.faults).map((fault) => `${fault.kind}: ${fault.text}`),
        `${route.path} rendered with console/page errors (${context})`,
    ).toEqual([]);
    expect(
        unexpectedResponseFailures(opened.responses).map((failure) => `${failure.status} ${failure.url}`),
        `${route.path} issued requests that failed (${context})`,
    ).toEqual([]);
}

test.describe('keyboard navigation and focus order', () => {
    for (const route of routesForTier(1)) {
        test(`${route.id} exposes a usable focus order`, async ({ browser }) => {
            const context = await matrixContext(browser, { ...DESKTOP, role: route.role });
            blockExternalRequests(context);
            const page = await context.newPage();
            const faults = captureFaults(page);
            const responses = captureResponseFailures(page);
            const opened = await openRoute(page, route, faults, responses);

            const stops = opened.landing.ok ? await walkFocusOrder(page, TAB_DEPTH) : [];
            const unnamed = stops.filter((stop) => stop.name.length === 0);
            const invisible = stops.filter((stop) => !stop.visible);
            const unindicated = stops.filter((stop) => !stop.indicator.present);

            await record(page, {
                routeId: route.id,
                path: route.path,
                state: opened.landing.ok ? 'keyboard-focus-order' : UNEXPECTED_LANDING_STATE,
                axes: { ...DESKTOP, role: route.role },
                faults: significantFaults(opened.faults),
                responseFailures: opened.responses,
                httpStatus: opened.status,
                finalPath: opened.landing.finalPath,
                notes: `stops=${stops.length} unnamed=${unnamed.length} invisible=${invisible.length} `
                    + `unindicated=${unindicated.length} :: `
                    + stops.slice(0, 12).map((stop) => `${stop.index}:${stop.tag}"${stop.name.slice(0, 24)}"`).join(' | '),
            });

            assertClean(opened, route, 'keyboard walk');
            expect(stops.length, `${route.path} must expose keyboard-reachable controls`).toBeGreaterThan(0);
            expect(
                unnamed.map((stop) => `${stop.index}:${stop.tag}`),
                `${route.path} has focusable controls with no accessible name`,
            ).toEqual([]);
            expect(
                invisible.map((stop) => `${stop.index}:${stop.tag}"${stop.name}"`),
                `${route.path} focuses controls that are not visible`,
            ).toEqual([]);
            expect(
                unindicated.map(
                    (stop) => `${stop.index}:${stop.tag}"${stop.name.slice(0, 24)}" `
                        + `outline=${stop.indicator.outlineStyle}/${stop.indicator.outlineWidth}/${stop.indicator.outlineColor} `
                        + `shadow=${stop.indicator.boxShadow}`,
                ),
                `${route.path} focuses controls with no visible focus indicator`,
            ).toEqual([]);
            await context.close();
        });
    }
});

test.describe('200% zoom / large text reflow', () => {
    for (const route of routesForTier(1)) {
        test(`${route.id} reflows at 200% zoom`, async ({ browser }) => {
            const context = await matrixContext(browser, { ...DESKTOP, role: route.role });
            blockExternalRequests(context);
            const page = await context.newPage();
            const faults = captureFaults(page);
            const responses = captureResponseFailures(page);
            await page.setViewportSize({ width: 720, height: 450 });
            const opened = await openRoute(page, route, faults, responses);

            const overflow = await page.evaluate((selector) => {
                const main = document.querySelector(selector);
                if (!(main instanceof HTMLElement)) return null;
                return {
                    scrollWidth: main.scrollWidth,
                    clientWidth: main.clientWidth,
                    documentScrollWidth: document.documentElement.scrollWidth,
                    documentClientWidth: document.documentElement.clientWidth,
                };
            }, AUTHENTICATED_SHELL_SELECTOR);

            await record(page, {
                routeId: route.id,
                path: route.path,
                state: opened.landing.ok ? 'zoom-200' : UNEXPECTED_LANDING_STATE,
                axes: { ...DESKTOP, role: route.role },
                faults: significantFaults(opened.faults),
                responseFailures: opened.responses,
                httpStatus: opened.status,
                finalPath: opened.landing.finalPath,
                notes: overflow === null
                    ? 'app main was not present'
                    : `main scrollWidth=${overflow.scrollWidth} clientWidth=${overflow.clientWidth} `
                        + `document scrollWidth=${overflow.documentScrollWidth} clientWidth=${overflow.documentClientWidth}`,
            });

            assertClean(opened, route, '200% zoom');
            if (overflow === null) {
                throw new Error(`${route.path} rendered no ${AUTHENTICATED_SHELL_SELECTOR} to measure reflow against`);
            }
            expect(
                overflow.scrollWidth,
                `${route.path} overflows horizontally at 200% zoom `
                    + `(main ${overflow.scrollWidth} > ${overflow.clientWidth})`,
            ).toBeLessThanOrEqual(overflow.clientWidth + 2);
            await context.close();
        });
    }
});

test.describe('reduced motion and the animated path', () => {
    const MOTION_ROUTES: readonly MatrixRoute[] = routesForTier(1).slice(0, 6);

    for (const route of MOTION_ROUTES) {
        for (const motion of ['reduce', 'no-preference'] as const) {
            test(`${route.id} settles with motion=${motion}`, async ({ browser }) => {
                const context = await matrixContext(browser, { ...DESKTOP, role: route.role, motion });
                blockExternalRequests(context);
                const page = await context.newPage();
                const faults = captureFaults(page);
                const responses = captureResponseFailures(page);
                const opened = await openRoute(page, route, faults, responses);
                await page.waitForTimeout(1_200);

                const heading = page.locator('h1').first();
                const opacity = await heading
                    .evaluate((el) => window.getComputedStyle(el).opacity)
                    .catch(() => '1');

                const shellSections = page.locator(CONTENT_SECTIONS);
                const sections = (await shellSections.count()) > 0 ? shellSections : page.locator('main > *');
                const settled = await sections.evaluateAll((elements) =>
                    elements.map((element) => ({
                        tag: element.tagName.toLowerCase(),
                        opacity: window.getComputedStyle(element).opacity,
                        rendered: element.getBoundingClientRect().height > 0,
                    })),
                );
                const stranded = settled.filter(
                    (section) => section.rendered && Number(section.opacity) < 0.9,
                );

                await record(page, {
                    routeId: route.id,
                    path: route.path,
                    state: opened.landing.ok ? `motion-${motion}` : UNEXPECTED_LANDING_STATE,
                    axes: { ...DESKTOP, role: route.role, motion },
                    faults: significantFaults(opened.faults),
                    responseFailures: opened.responses,
                    httpStatus: opened.status,
                    finalPath: opened.landing.finalPath,
                    notes: `h1 opacity=${opacity} sections=${settled.length} stranded=${stranded.length}`,
                });

                assertClean(opened, route, `motion=${motion}`);
                await expect(heading, `${route.path} heading must be visible with motion=${motion}`).toBeVisible();
                expect(
                    Number(opacity),
                    `${route.path} heading is still transparent after animations settle (motion=${motion})`,
                ).toBeGreaterThan(0.9);
                expect(
                    settled.length,
                    `${route.path} exposed no content sections to check for stranded animations`,
                ).toBeGreaterThan(0);
                expect(
                    stranded.map((section) => `${section.tag}@${section.opacity}`),
                    `${route.path} left content sections transparent after animations settle (motion=${motion})`,
                ).toEqual([]);
                await context.close();
            });
        }
    }
});
