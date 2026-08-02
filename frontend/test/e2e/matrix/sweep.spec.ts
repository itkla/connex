import { test, expect } from '@playwright/test';

import { MATRIX_ROUTES, type MatrixRoute } from './routes';
import type { PageFault, ResponseFailure } from './support/matrix';
import {
    blockExternalRequests,
    captureFaults,
    captureResponseFailures,
    clearFaultRules,
    matrixContext,
    record,
    significantFaults,
    unexpectedFaults,
    unexpectedResponseFailures,
    type Axes,
} from './support/matrix';

/**
 * Axis combinations per tier.
 *
 * Tier 1 earns the full cross-product of viewport × locale × theme because those are the routes the
 * release loop runs through every day. Tier 2 earns a slice chosen to cover each axis at least once
 * without re-running the whole product, and tier 3 earns the two combinations most likely to expose a
 * layout or translation break: desktop English and mobile Japanese.
 */
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

/**
 * Renders one route under one axis combination and records the evidence.
 * @returns the significant faults observed, so the caller can assert on them
 */
async function sweepCell(
    browser: Parameters<typeof matrixContext>[0],
    route: MatrixRoute,
    axes: Axes,
): Promise<{
    faults: PageFault[];
    badResponses: ResponseFailure[];
    status: number | null;
    heading: string | null;
}> {
    const context = await matrixContext(browser, { ...axes, role: route.role });
    const blocked = blockExternalRequests(context);
    const page = await context.newPage();
    const faults = captureFaults(page);
    const responseFailures = captureResponseFailures(page);
    let status: number | null = null;
    try {
        const response = await page.goto(route.path, { waitUntil: 'domcontentloaded' });
        status = response?.status() ?? null;
        await page.waitForLoadState('networkidle', { timeout: 20_000 }).catch(() => undefined);
        const heading = await page.locator('h1').first().textContent({ timeout: 5_000 }).catch(() => null);
        await record(page, {
            routeId: route.id,
            path: route.path,
            state: 'success',
            axes: { ...axes, role: route.role },
            faults: significantFaults(faults),
            responseFailures: unexpectedResponseFailures(responseFailures),
            httpStatus: status,
            notes: blocked.size > 0 ? `blocked off-origin hosts: ${[...blocked].sort().join(', ')}` : undefined,
        });
        return {
            faults: unexpectedFaults(faults),
            badResponses: unexpectedResponseFailures(responseFailures),
            status,
            heading: heading?.trim() ?? null,
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
                const { faults, badResponses, status, heading } = await sweepCell(browser, route, axes);
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
