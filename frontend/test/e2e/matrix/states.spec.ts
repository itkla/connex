/**
 * State-induction specs for the Wave 4 (#856) route/state matrix.
 *
 * Where {@link ./sweep.spec.ts} proves every route renders across the presentation axes, this file
 * proves each route behaves honestly when its sources misbehave. The states that matter are the ones
 * a happy-path pass never reaches: a slow source must show its skeleton rather than fabricate an
 * empty result, a failed section must say so rather than render as empty, a denial must read as a
 * denial rather than a 404, and a failed refresh must not silently present stale figures as current.
 *
 * Every state here is asserted, not merely labelled. A screenshot filed under `permission-denied`
 * that actually shows the protected content is worse than no screenshot at all, because the manifest
 * turns it into a coverage claim.
 *
 * Two things make the assertions locale-independent, which they must be because the matrix runs in
 * English and Japanese. First, `AccessDenied` and `NotFoundState` render *identical* markup — both
 * are a `PageState` with an icon tile, an `h2` and a paragraph — so the only signal that separates a
 * denial from a not-found is the heroicon they pass: a closed padlock versus a magnifying glass.
 * Second, these specs deliberately induce failing responses, so unlike the sweep they record
 * response failures as evidence without asserting they are absent.
 */

import { test, expect } from '@playwright/test';

import { MATRIX_ROUTES } from './routes';
import {
    blockExternalRequests,
    captureFaults,
    captureResponseFailures,
    classifyResponseFailures,
    clearFaultRules,
    degradedSections,
    describeLanding,
    landingOf,
    matrixContext,
    matrixFixture,
    record,
    setFaultRules,
    significantFaults,
    type Axes,
} from './support/matrix';

const DESKTOP: Axes = { viewport: 'desktop', locale: 'en', theme: 'light' };

const DENIED_MARKER =
    '[data-app-main] :is(div.size-14, span.size-10) svg path[d^="M16.5 10.5V6.75a4.5 4.5 0 1 0-9 0v3.75"]';
const NOT_FOUND_MARKER =
    '[data-app-main] :is(div.size-14, span.size-10) svg path[d^="m21 21-5.197-5.197"]';
const EMPTY_STATE_MARKER = '[data-app-main] div.py-20.text-center h2';
const SKELETON_MARKER = '[data-app-main] [data-slot="skeleton"]';
const STALE_DISCLOSURE = '[data-app-main] div.rounded-lg.bg-card.px-4.py-3';
const HARD_FAILURE_CARD = '[data-app-main] div.rounded-lg.bg-card.p-4';

test.describe('loading — a slow source must show its skeleton, not a fabricated empty result', () => {
    test.afterEach(() => {
        clearFaultRules();
    });

    test('the contacts collection renders its skeleton while its source is slow', async ({ browser }) => {
        setFaultRules({ delay: [{ prefix: '/api/saved-views', ms: 12_000 }] });
        const context = await matrixContext(browser, { ...DESKTOP, role: 'member' });
        blockExternalRequests(context);
        const page = await context.newPage();
        const faults = captureFaults(page);
        const responses = captureResponseFailures(page);

        await page.goto('/records/contacts', { waitUntil: 'commit' });
        const skeletons = page.locator(SKELETON_MARKER);
        await expect(
            skeletons.first(),
            'a collection route must render its shape-matched skeleton while its source is pending',
        ).toBeVisible({ timeout: 20_000 });
        const skeletonCount = await skeletons.count();
        const fabricatedEmpty = await page.locator(EMPTY_STATE_MARKER).count();
        const landing = await landingOf(page, '/records/contacts');

        await record(page, {
            routeId: 'contacts-browser',
            path: '/records/contacts',
            state: 'loading',
            axes: { ...DESKTOP, role: 'member' },
            faults: significantFaults(faults),
            responseFailures: classifyResponseFailures(responses, { role: 'member' }),
            httpStatus: 200,
            finalPath: landing.finalPath,
            notes: `skeletons=${skeletonCount} empty-states-while-loading=${fabricatedEmpty}`,
        });

        expect(landing.inAuthenticatedShell, 'the shell must render while the collection is pending').toBe(true);
        expect(skeletonCount, 'the skeleton must stand in for the pending collection').toBeGreaterThan(0);
        expect(
            fabricatedEmpty,
            'a pending collection must not render an empty state before its data arrives',
        ).toBe(0);
        await context.close();
    });
});

test.describe('partial failure — Home section isolation', () => {
    test.afterEach(() => {
        clearFaultRules();
    });

    test('a failed notes source degrades only its own widget', async ({ browser }) => {
        const context = await matrixContext(browser, { ...DESKTOP, role: 'member' });
        blockExternalRequests(context);
        const page = await context.newPage();
        const faults = captureFaults(page);
        const responses = captureResponseFailures(page);

        await page.goto('/dashboard', { waitUntil: 'domcontentloaded' });
        await page.waitForLoadState('networkidle').catch(() => undefined);
        const baseline = await landingOf(page, '/dashboard');
        expect(baseline.ok, `the dashboard baseline must render as itself — ${describeLanding(baseline)}`).toBe(true);
        const healthy = await degradedSections(page).count();
        await record(page, {
            routeId: 'home',
            path: '/dashboard',
            state: 'success-baseline',
            axes: DESKTOP,
            faults: significantFaults(faults),
            responseFailures: classifyResponseFailures(responses, { role: 'member' }),
            httpStatus: 200,
            finalPath: baseline.finalPath,
            notes: `degraded sections: ${healthy}`,
        });
        expect(healthy, 'a healthy dashboard degrades nothing').toBe(0);

        setFaultRules({ fail: ['/api/notes/page'] });
        await page.reload({ waitUntil: 'domcontentloaded' });
        await page.waitForLoadState('networkidle').catch(() => undefined);
        const faulted = await landingOf(page, '/dashboard');
        const degraded = await degradedSections(page).count();
        await record(page, {
            routeId: 'home',
            path: '/dashboard',
            state: 'partial-failure-notes',
            axes: DESKTOP,
            faults: significantFaults(faults),
            responseFailures: classifyResponseFailures(responses, { role: 'member' }),
            httpStatus: 200,
            finalPath: faulted.finalPath,
            notes: `degraded sections: ${degraded}`,
        });

        expect(faulted.ok, `a faulted source must not move the page — ${describeLanding(faulted)}`).toBe(true);
        expect(degraded, 'exactly one widget degrades when the notes source fails').toBe(1);
        await expect(
            page.getByRole('heading', { level: 1 }),
            'the page keeps its heading rather than blanking',
        ).toBeVisible();
        await context.close();
    });
});

test.describe('permission denied — real RBAC, not injected', () => {
    for (const route of MATRIX_ROUTES.filter((candidate) => candidate.deniesMember)) {
        test(`${route.id} denies the seeded member`, async ({ browser }) => {
            const context = await matrixContext(browser, { ...DESKTOP, role: 'member' });
            blockExternalRequests(context);
            const page = await context.newPage();
            const faults = captureFaults(page);
            const responses = captureResponseFailures(page);

            const response = await page.goto(route.path, { waitUntil: 'domcontentloaded' });
            await page.waitForLoadState('networkidle').catch(() => undefined);
            const landing = await landingOf(page, route.path, route.landsOn);
            const denied = await page.locator(DENIED_MARKER).count();
            const notFound = await page.locator(NOT_FOUND_MARKER).count();
            const body = ((await page.locator('main').first().textContent()) ?? '').replace(/\s+/g, ' ').trim();

            await record(page, {
                routeId: route.id,
                path: route.path,
                state: 'permission-denied',
                axes: { ...DESKTOP, role: 'member' },
                faults: significantFaults(faults),
                responseFailures: classifyResponseFailures(responses, { role: 'member' }),
                httpStatus: response?.status() ?? null,
                finalPath: landing.finalPath,
                notes: `denial-markers=${denied} not-found-markers=${notFound} :: ${body.slice(0, 200)}`,
            });

            expect(
                landing.inAuthenticatedShell,
                `a denial must render inside the app shell, not bounce the member out — ${describeLanding(landing)}`,
            ).toBe(true);
            expect(
                landing.acceptedPaths.includes(landing.finalPath),
                `a denied member must not be redirected somewhere undeclared — ${describeLanding(landing)}`,
            ).toBe(true);
            expect(denied, `${route.path} must present the denial grammar to a member`).toBeGreaterThan(0);
            expect(notFound, `${route.path} must not disguise a denial as a not-found state`).toBe(0);
            await context.close();
        });
    }
});

test.describe('403 vs 404 — the split must be honest', () => {
    test.afterEach(() => {
        clearFaultRules();
    });

    test('a forbidden record reads as denied, a missing record as not found', async ({ browser }) => {
        const context = await matrixContext(browser, { ...DESKTOP, role: 'member' });
        blockExternalRequests(context);
        const page = await context.newPage();
        const faults = captureFaults(page);
        const responses = captureResponseFailures(page);

        setFaultRules({ forbid: ['/api/persons/1'] });
        const forbiddenResponse = await page.goto('/records/contacts/1', { waitUntil: 'domcontentloaded' });
        await page.waitForLoadState('networkidle').catch(() => undefined);
        const forbiddenLanding = await landingOf(page, '/records/contacts/1');
        const deniedMarkers = await page.locator(DENIED_MARKER).count();
        const denied = ((await page.locator('main').first().textContent()) ?? '').replace(/\s+/g, ' ').trim();
        await record(page, {
            routeId: 'contact-detail',
            path: '/records/contacts/1',
            state: 'forbidden',
            axes: DESKTOP,
            faults: significantFaults(faults),
            responseFailures: classifyResponseFailures(responses, { role: 'member' }),
            httpStatus: forbiddenResponse?.status() ?? null,
            finalPath: forbiddenLanding.finalPath,
            notes: `denial-markers=${deniedMarkers} :: ${denied.slice(0, 200)}`,
        });
        expect(
            forbiddenLanding.ok,
            `a forbidden record must render in place, not bounce — ${describeLanding(forbiddenLanding)}`,
        ).toBe(true);
        expect(deniedMarkers, 'a forbidden record must present the denial grammar').toBeGreaterThan(0);

        clearFaultRules();
        const missingResponse = await page.goto('/records/contacts/99999', { waitUntil: 'domcontentloaded' });
        await page.waitForLoadState('networkidle').catch(() => undefined);
        const missingStatus = missingResponse?.status() ?? null;
        const missingLanding = await landingOf(page, '/records/contacts/99999');
        const notFoundMarkers = await page.locator(NOT_FOUND_MARKER).count();
        const missing = ((await page.locator('main').first().textContent()) ?? '').replace(/\s+/g, ' ').trim();
        await record(page, {
            routeId: 'contact-detail',
            path: '/records/contacts/99999',
            state: 'not-found',
            axes: DESKTOP,
            faults: significantFaults(faults),
            responseFailures: classifyResponseFailures(responses, { role: 'member' }),
            httpStatus: missingStatus,
            finalPath: missingLanding.finalPath,
            notes: `not-found-markers=${notFoundMarkers} :: ${missing.slice(0, 200)}`,
        });

        expect(missingStatus, 'a missing record must answer 404, not 200').toBe(404);
        expect(notFoundMarkers, 'a missing record must present the not-found grammar').toBeGreaterThan(0);
        expect(
            await page.locator(DENIED_MARKER).count(),
            'a missing record must not present the denial grammar',
        ).toBe(0);
        expect(denied, 'the denial and the not-found state must not be the same copy').not.toBe(missing);
        await context.close();
    });
});

test.describe('stale — a failed refresh must not present stale figures as current', () => {
    test.afterEach(() => {
        clearFaultRules();
    });

    test('diagnostics discloses a failed refresh', async ({ browser }) => {
        const { workspaceId } = matrixFixture();
        const diagnosticsPath = `/api/workspaces/${workspaceId}/diagnostics`;
        const context = await matrixContext(browser, { ...DESKTOP, role: 'admin' });
        blockExternalRequests(context);
        const page = await context.newPage();
        const faults = captureFaults(page);
        const responses = captureResponseFailures(page);

        await page.goto('/settings/diagnostics', { waitUntil: 'domcontentloaded' });
        await page.waitForLoadState('networkidle').catch(() => undefined);
        const healthy = await landingOf(page, '/settings/diagnostics');
        expect(healthy.ok, `diagnostics must render as itself — ${describeLanding(healthy)}`).toBe(true);
        const headingsBefore = await page.locator('[data-app-main] h2').allTextContents();
        await record(page, {
            routeId: 'settings-diagnostics',
            path: '/settings/diagnostics',
            state: 'success',
            axes: { ...DESKTOP, role: 'admin' },
            faults: significantFaults(faults),
            responseFailures: classifyResponseFailures(responses, { role: 'admin' }),
            httpStatus: 200,
            finalPath: healthy.finalPath,
            notes: `sections: ${headingsBefore.length}`,
        });
        expect(headingsBefore.length, 'the healthy panel must render its report before it is faulted').toBeGreaterThan(0);
        expect(await page.locator(STALE_DISCLOSURE).count(), 'a healthy panel discloses nothing stale').toBe(0);

        setFaultRules({ fail: [diagnosticsPath] });
        const refresh = page.getByRole('button', { name: /refresh|再読み込み|更新/i }).first();
        await expect(refresh, 'the diagnostics panel must expose a refresh control').toBeVisible();
        const failedRefresh = page.waitForResponse(
            (response) => response.url().includes(diagnosticsPath) && response.status() >= 500,
            { timeout: 20_000 },
        );
        await refresh.click();
        await failedRefresh;

        const stale = page.locator(STALE_DISCLOSURE);
        await expect(
            stale.first(),
            'a failed refresh must disclose that the figures on screen are the last good ones',
        ).toBeVisible({ timeout: 15_000 });
        const headingsAfter = await page.locator('[data-app-main] h2').allTextContents();
        const staleLanding = await landingOf(page, '/settings/diagnostics');
        const body = ((await page.locator('main').first().textContent()) ?? '').replace(/\s+/g, ' ').trim();
        await record(page, {
            routeId: 'settings-diagnostics',
            path: '/settings/diagnostics',
            state: 'stale-after-failed-refresh',
            axes: { ...DESKTOP, role: 'admin' },
            faults: significantFaults(faults),
            responseFailures: classifyResponseFailures(responses, { role: 'admin' }),
            httpStatus: 200,
            finalPath: staleLanding.finalPath,
            notes: `sections before=${headingsBefore.length} after=${headingsAfter.length} :: ${body.slice(0, 300)}`,
        });

        expect(
            await page.locator(HARD_FAILURE_CARD).count(),
            'a refresh failure over a good payload must not collapse into the hard-failure card',
        ).toBe(0);
        expect(
            headingsBefore.filter((heading) => !headingsAfter.includes(heading)),
            'the previously loaded report must remain on screen behind the stale disclosure',
        ).toEqual([]);
        await context.close();
    });
});

test.describe('no results — a filtered miss must not read as a broken page', () => {
    /**
     * The seeded `small` profile carries 50 contacts, so this row is a *filtered* miss, not a
     * first-run empty workspace. It is labelled `no-results` for that reason: the run-info caveats
     * record that the first-run empty state is not covered, rather than letting a filtered miss stand
     * in for it.
     *
     * The two are distinguishable in the DOM: `ContactsBrowser` passes `filtersActive`, so
     * `RecordsRenderView` renders the muted no-results state with a clear-filters escape hatch
     * rather than the brand first-run state that would claim the workspace is empty.
     */
    test('a search that matches nothing renders an empty state', async ({ browser }) => {
        const context = await matrixContext(browser, { ...DESKTOP, role: 'owner' });
        blockExternalRequests(context);
        const page = await context.newPage();
        const faults = captureFaults(page);
        const responses = captureResponseFailures(page);

        const path = '/records/contacts?q=zzz-no-such-contact-zzz';
        const response = await page.goto(path, { waitUntil: 'domcontentloaded' });
        await page.waitForLoadState('networkidle').catch(() => undefined);
        const landing = await landingOf(page, path);
        const emptyStates = await page.locator(EMPTY_STATE_MARKER).count();
        const clearFilters = await page
            .locator('[data-app-main] div.py-20.text-center button')
            .count();
        const body = ((await page.locator('main').first().textContent()) ?? '').replace(/\s+/g, ' ').trim();

        await record(page, {
            routeId: 'contacts-browser',
            path,
            state: 'no-results',
            axes: { ...DESKTOP, role: 'owner' },
            faults: significantFaults(faults),
            responseFailures: classifyResponseFailures(responses, { role: 'owner' }),
            httpStatus: response?.status() ?? null,
            finalPath: landing.finalPath,
            notes: `empty-states=${emptyStates} clear-filters-actions=${clearFilters} :: ${body.slice(0, 200)}`,
        });

        expect(landing.ok, `a filtered miss must stay on the collection — ${describeLanding(landing)}`).toBe(true);
        expect(emptyStates, 'a search with no matches must render an empty state, not a blank page').toBeGreaterThan(0);
        expect(
            clearFilters,
            'a filtered miss must offer a way back to the unfiltered list',
        ).toBeGreaterThan(0);
        expect(
            await page.locator(NOT_FOUND_MARKER).count(),
            'a filtered miss must not read as a missing page',
        ).toBe(0);
        await context.close();
    });
});
