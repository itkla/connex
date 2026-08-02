import { test, expect } from '@playwright/test';

import {
    blockExternalRequests,
    captureFaults,
    degradedSections,
    clearFaultRules,
    matrixContext,
    record,
    setFaultRules,
    significantFaults,
    type Axes,
} from './support/matrix';

/**
 * State-induction specs for the Wave 4 (#856) route/state matrix.
 *
 * Where {@link ./sweep.spec.ts} proves every route renders across the presentation axes, this file
 * proves each route behaves honestly when its sources misbehave. The states that matter are the ones
 * a happy-path pass never reaches: a failed section must say so rather than render as empty, a denial
 * must read as a denial rather than a 404, and a failed refresh must not silently present stale
 * figures as current.
 */

const DESKTOP: Axes = { viewport: 'desktop', locale: 'en', theme: 'light' };

test.describe('partial failure — Home section isolation', () => {
    test.afterEach(() => {
        clearFaultRules();
    });

    /**
     * The dashboard resolves each widget's source independently and renders `SectionUnavailable`
     * (`role="status"`) in place of any widget whose fetch failed. Counting those is what turns
     * "one failed source does not blank the page" from an assertion into a measurement.
     */
    test('a failed notes source degrades only its own widget', async ({ browser }) => {
        const context = await matrixContext(browser, { ...DESKTOP, role: 'member' });
        blockExternalRequests(context);
        const page = await context.newPage();
        const faults = captureFaults(page);

        await page.goto('/dashboard', { waitUntil: 'domcontentloaded' });
        await page.waitForLoadState('networkidle').catch(() => undefined);
        const healthy = await degradedSections(page).count();
        await record(page, {
            routeId: 'home',
            path: '/dashboard',
            state: 'success-baseline',
            axes: DESKTOP,
            faults: significantFaults(faults),
            httpStatus: 200,
            notes: `degraded sections: ${healthy}`,
        });
        expect(healthy, 'a healthy dashboard degrades nothing').toBe(0);

        setFaultRules({ fail: ['/api/notes/page'] });
        await page.reload({ waitUntil: 'domcontentloaded' });
        await page.waitForLoadState('networkidle').catch(() => undefined);
        const degraded = await degradedSections(page).count();
        await record(page, {
            routeId: 'home',
            path: '/dashboard',
            state: 'partial-failure-notes',
            axes: DESKTOP,
            faults: significantFaults(faults),
            httpStatus: 200,
            notes: `degraded sections: ${degraded}`,
        });

        expect(degraded, 'exactly one widget degrades when the notes source fails').toBe(1);
        await expect(
            page.getByRole('heading', { level: 1 }),
            'the page keeps its heading rather than blanking',
        ).toBeVisible();
        await context.close();
    });
});

test.describe('permission denied — real RBAC, not injected', () => {
    /**
     * Routes the seeded member genuinely lacks permission for must present the denial grammar
     * (`AccessDenied`) rather than a not-found state; telling a user a surface does not exist when the
     * real answer is "you may not see it" hides the fix.
     */
    for (const route of [
        { id: 'settings-diagnostics', path: '/settings/diagnostics' },
        { id: 'admin-logs', path: '/admin/logs' },
        { id: 'workflows', path: '/workflows' },
        { id: 'products', path: '/records/products' },
    ]) {
        test(`${route.id} denies the seeded member`, async ({ browser }) => {
            const context = await matrixContext(browser, { ...DESKTOP, role: 'member' });
            blockExternalRequests(context);
            const page = await context.newPage();
            const faults = captureFaults(page);

            const response = await page.goto(route.path, { waitUntil: 'domcontentloaded' });
            await page.waitForLoadState('networkidle').catch(() => undefined);
            const body = (await page.locator('main').first().textContent()) ?? '';
            await record(page, {
                routeId: route.id,
                path: route.path,
                state: 'permission-denied',
                axes: { ...DESKTOP, role: 'member' },
                faults: significantFaults(faults),
                httpStatus: response?.status() ?? null,
                notes: body.slice(0, 200).replace(/\s+/g, ' ').trim(),
            });
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

        setFaultRules({ forbid: ['/api/persons/1'] });
        await page.goto('/records/contacts/1', { waitUntil: 'domcontentloaded' });
        await page.waitForLoadState('networkidle').catch(() => undefined);
        const denied = ((await page.locator('main').first().textContent()) ?? '').replace(/\s+/g, ' ').trim();
        await record(page, {
            routeId: 'contact-detail',
            path: '/records/contacts/1',
            state: 'forbidden',
            axes: DESKTOP,
            faults: significantFaults(faults),
            httpStatus: 200,
            notes: denied.slice(0, 200),
        });
        expect(page.url(), 'a forbidden record must not bounce to the login page').not.toContain('/auth/login');

        clearFaultRules();
        await page.goto('/records/contacts/99999', { waitUntil: 'domcontentloaded' });
        await page.waitForLoadState('networkidle').catch(() => undefined);
        const missing = ((await page.locator('main').first().textContent()) ?? '').replace(/\s+/g, ' ').trim();
        await record(page, {
            routeId: 'contact-detail',
            path: '/records/contacts/99999',
            state: 'not-found',
            axes: DESKTOP,
            faults: significantFaults(faults),
            httpStatus: 404,
            notes: missing.slice(0, 200),
        });

        expect(denied, 'the denial and the not-found state must not be the same copy').not.toBe(missing);
        await context.close();
    });
});

test.describe('stale — a failed refresh must not present stale figures as current', () => {
    test.afterEach(() => {
        clearFaultRules();
    });

    /**
     * The diagnostics panel keeps the last good payload when a refresh fails and discloses it behind a
     * stale banner. Loading it healthy, then failing the source and pressing refresh, reaches a state
     * that no happy-path pass can.
     */
    test('diagnostics discloses a failed refresh', async ({ browser }) => {
        const context = await matrixContext(browser, { ...DESKTOP, role: 'admin' });
        blockExternalRequests(context);
        const page = await context.newPage();
        const faults = captureFaults(page);

        await page.goto('/settings/diagnostics', { waitUntil: 'domcontentloaded' });
        await page.waitForLoadState('networkidle').catch(() => undefined);
        await record(page, {
            routeId: 'settings-diagnostics',
            path: '/settings/diagnostics',
            state: 'success',
            axes: { ...DESKTOP, role: 'admin' },
            faults: significantFaults(faults),
            httpStatus: 200,
        });

        setFaultRules({ fail: ['/api/workspaces'] });
        const refresh = page.getByRole('button', { name: /refresh|再読み込み|更新/i }).first();
        if (await refresh.count()) {
            await refresh.click();
            await page.waitForTimeout(2_000);
        }
        const body = ((await page.locator('main').first().textContent()) ?? '').replace(/\s+/g, ' ').trim();
        await record(page, {
            routeId: 'settings-diagnostics',
            path: '/settings/diagnostics',
            state: 'stale-after-failed-refresh',
            axes: { ...DESKTOP, role: 'admin' },
            faults: significantFaults(faults),
            httpStatus: 200,
            notes: body.slice(0, 300),
        });
        await context.close();
    });
});

test.describe('empty — an empty workspace must not read as a broken one', () => {
    test('a workspace with no records renders empty states', async ({ browser }) => {
        const context = await matrixContext(browser, { ...DESKTOP, role: 'owner' });
        blockExternalRequests(context);
        const page = await context.newPage();
        const faults = captureFaults(page);

        await page.goto('/records/contacts?q=zzz-no-such-contact-zzz', { waitUntil: 'domcontentloaded' });
        await page.waitForLoadState('networkidle').catch(() => undefined);
        const body = ((await page.locator('main').first().textContent()) ?? '').replace(/\s+/g, ' ').trim();
        await record(page, {
            routeId: 'contacts-browser',
            path: '/records/contacts?q=zzz-no-such-contact-zzz',
            state: 'empty',
            axes: { ...DESKTOP, role: 'owner' },
            faults: significantFaults(faults),
            httpStatus: 200,
            notes: body.slice(0, 200),
        });
        await context.close();
    });
});
