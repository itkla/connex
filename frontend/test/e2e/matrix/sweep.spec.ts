/**
 * Breadth sweep for the Wave 4 (#856) route/state matrix.
 *
 * Every route is rendered under a tier-dependent slice of the presentation axes. Tier 1 earns the
 * full cross-product of viewport × locale × theme because those are the routes the release loop runs
 * through every day; tier 2 earns a slice that covers each axis at least once without re-running the
 * whole product; tier 3 earns the two combinations most likely to expose a layout or translation
 * break — desktop English and mobile Japanese.
 *
 * The landing assertion runs before any other, and a cell that did not land where it was aimed is
 * recorded under {@link UNEXPECTED_LANDING_STATE} rather than as a success. That ordering is the
 * point: a redirect to `/auth/login` answers 200 and carries an `<h1>`, so every other assertion here
 * would pass while the manifest recorded a protected route as successfully rendered.
 */

import { test, expect } from '@playwright/test';

import { MATRIX_ROUTES, type MatrixRoute } from './routes';
import type { Landing, PageFault, ResponseFailure } from './support/matrix';
import {
    blockExternalRequests,
    captureFaults,
    captureResponseFailures,
    classifyResponseFailures,
    clearFaultRules,
    describeLanding,
    landingOf,
    matrixContext,
    record,
    significantFaults,
    unexpectedFaults,
    unexpectedResponseFailures,
    UNEXPECTED_LANDING_STATE,
    type Axes,
} from './support/matrix';

const TIER_AXES: Record<1 | 2 | 3, readonly Axes[]> = {
    1: (['desktop', 'tablet', 'mobile'] as const).flatMap((viewport) =>
        (['en', 'ja'] as const).flatMap((locale) =>
            (['light', 'dark'] as const).map((theme) => ({ viewport, locale, theme })),
        ),
    ),
    2: [
        { viewport: 'desktop', locale: 'en', theme: 'light' },
        { viewport: 'desktop', locale: 'en', theme: 'dark' },
        { viewport: 'tablet', locale: 'ja', theme: 'light' },
        { viewport: 'mobile', locale: 'ja', theme: 'light' },
    ],
    3: [
        { viewport: 'desktop', locale: 'en', theme: 'light' },
        { viewport: 'mobile', locale: 'ja', theme: 'light' },
    ],
};

type SweepResult = {
    faults: PageFault[];
    badResponses: ResponseFailure[];
    status: number | null;
    heading: string | null;
    landing: Landing;
};

async function sweepCell(
    browser: Parameters<typeof matrixContext>[0],
    route: MatrixRoute,
    axes: Axes,
): Promise<SweepResult> {
    const context = await matrixContext(browser, { ...axes, role: route.role });
    const blocked = blockExternalRequests(context);
    const page = await context.newPage();
    const faults = captureFaults(page);
    const responseFailures = captureResponseFailures(page);
    try {
        const response = await page.goto(route.path, { waitUntil: 'domcontentloaded' });
        const status = response?.status() ?? null;
        await page.waitForLoadState('networkidle', { timeout: 20_000 }).catch(() => undefined);
        const landing = await landingOf(page, route.path, route.landsOn);
        const heading = await page.locator('h1').first().textContent({ timeout: 5_000 }).catch(() => null);
        const classified = classifyResponseFailures(responseFailures, { role: route.role });
        const blockedNote = blocked.size > 0 ? `blocked off-origin hosts: ${[...blocked].sort().join(', ')}` : '';
        const landingNote = landing.ok ? '' : describeLanding(landing);
        await record(page, {
            routeId: route.id,
            path: route.path,
            state: landing.ok ? 'success' : UNEXPECTED_LANDING_STATE,
            axes: { ...axes, role: route.role },
            faults: significantFaults(faults),
            responseFailures: classified,
            httpStatus: status,
            finalPath: landing.finalPath,
            notes: [landingNote, blockedNote].filter((note) => note.length > 0).join(' :: ') || undefined,
        });
        return {
            faults: unexpectedFaults(faults),
            badResponses: unexpectedResponseFailures(classified),
            status,
            heading: heading?.trim() ?? null,
            landing,
        };
    } finally {
        await context.close();
    }
}

test.describe('route/state matrix — breadth sweep', () => {
    test.beforeAll(() => {
        clearFaultRules();
    });

    for (const route of MATRIX_ROUTES) {
        for (const axes of TIER_AXES[route.tier]) {
            const label = `${route.id} @ ${axes.viewport}/${axes.locale}/${axes.theme}`;
            test(label, async ({ browser }) => {
                const { faults, badResponses, status, heading, landing } = await sweepCell(browser, route, axes);
                expect(
                    landing.ok,
                    `${route.path} was not rendered as itself — ${describeLanding(landing)}`,
                ).toBe(true);
                expect(status, `${route.path} should not be a server error`).not.toBe(500);
                expect(
                    faults.map((fault) => `${fault.kind}: ${fault.text}`),
                    `${route.path} rendered with console/page errors`,
                ).toEqual([]);
                expect(
                    badResponses.map((failure) => `${failure.status} ${failure.url}`),
                    `${route.path} issued requests that failed`,
                ).toEqual([]);
                expect(heading, `${route.path} must expose a level-1 heading as its accessible name`).not.toBeNull();
            });
        }
    }
});
