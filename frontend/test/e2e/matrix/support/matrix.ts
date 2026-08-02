/**
 * Shared machinery for the Wave 4 (#856) route/state matrix.
 *
 * Three behaviours here are load-bearing for whether a run is evidence or theatre, and each is
 * subtle enough to be worth stating once at the top of the file rather than re-deriving:
 *
 * **Landing verification.** A cell may only be recorded as a success if the browser is still on the
 * route that was requested *and* inside the authenticated app shell. Without that check a redirect to
 * `/auth/login` is indistinguishable from a healthy render: the final response is 200 and the login
 * page carries its own `<h1>`, so a protected route whose session had expired would be recorded as a
 * passing cell — the harness would manufacture the very claim it exists to test.
 *
 * **Failure allowlists are narrow on purpose.** A known failure is suppressed only when the exact
 * resource, the exact status, and (where it matters) the role all match. Suppressing an endpoint by
 * URL alone would mean a new 500 from that endpoint rides in under an old issue number. Suppressed
 * failures are annotated rather than dropped, so the manifest still shows them.
 *
 * **Capture settles before it shoots.** `networkidle` is not enough on chart-heavy surfaces —
 * recharts paints after its data arrives and entrance animations are frozen at their first frame by
 * `animations: "disabled"`, so an immediate capture yields a blank content area that misrepresents a
 * healthy page. The app shell is also `h-dvh overflow-hidden` with `<main>` as the scrolling element,
 * so the document is always exactly one viewport tall and `fullPage` would silently crop everything
 * below the fold; the fixed height is released immediately before the screenshot, never before an
 * assertion.
 */

import { appendFileSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import type { Browser, BrowserContext, Page } from '@playwright/test';

import {
    MATRIX_ARTIFACT_DIR,
    MATRIX_BASE_URL,
    MATRIX_FIXTURE_PATH,
    storageStateFor,
} from '../../../../playwright.matrix.config';
import type { RouteRole } from '../routes';

/** Viewports the matrix renders: desktop, tablet, and the reference mobile handset. */
export const VIEWPORTS = {
    desktop: { width: 1440, height: 900 },
    tablet: { width: 820, height: 1180 },
    mobile: { width: 390, height: 844 },
} as const;

export type ViewportName = keyof typeof VIEWPORTS;
export type Locale = 'en' | 'ja';
export type Theme = 'light' | 'dark';
export type Motion = 'reduce' | 'no-preference';

/** One axis combination a route is rendered under. */
export type Axes = {
    viewport: ViewportName;
    locale: Locale;
    theme: Theme;
    motion?: Motion;
    role?: RouteRole;
};

/** The tenant facts the seeder produced, resolved once by the setup project. */
export type MatrixFixture = {
    workspaceId: number;
    users: Record<RouteRole, string>;
};

/** Reads the fixture the setup project wrote. */
export function matrixFixture(): MatrixFixture {
    return JSON.parse(readFileSync(MATRIX_FIXTURE_PATH, 'utf8')) as MatrixFixture;
}

/**
 * Builds the artifact filename for one cell of the matrix. Encoding every axis in the name keeps the
 * evidence self-describing: a reviewer reading a filename knows exactly which cell produced it
 * without consulting the manifest.
 */
export function artifactName(routeId: string, state: string, axes: Axes): string {
    const motion = axes.motion === 'no-preference' ? '-motion' : '';
    return `${routeId}__${axes.viewport}-${axes.locale}-${axes.theme}${motion}__${state}.png`;
}

/**
 * Opens a browser context pinned to one axis combination.
 *
 * The locale is carried by the `NEXT_LOCALE` cookie rather than a UI toggle so a run never depends on
 * a settings control that might itself be broken, and the theme is pre-seeded into `localStorage`
 * before any script runs so next-themes applies the class on first paint — a screenshot taken after a
 * post-hydration theme flip would capture the flash rather than the theme.
 */
export async function matrixContext(browser: Browser, axes: Axes): Promise<BrowserContext> {
    const role = axes.role ?? 'member';
    const context = await browser.newContext({
        storageState: storageStateFor(role),
        viewport: VIEWPORTS[axes.viewport],
        isMobile: axes.viewport === 'mobile',
        hasTouch: axes.viewport !== 'desktop',
        locale: axes.locale === 'ja' ? 'ja-JP' : 'en-US',
        timezoneId: 'UTC',
        colorScheme: axes.theme,
        reducedMotion: axes.motion ?? 'reduce',
    });
    await context.addCookies([
        { name: 'NEXT_LOCALE', value: axes.locale, url: MATRIX_BASE_URL, sameSite: 'Lax' },
    ]);
    await context.addInitScript((theme: string) => {
        window.localStorage.setItem('theme', theme);
    }, axes.theme);
    return context;
}

/**
 * Blocks every request that leaves the application origin.
 *
 * The seeded fixtures carry `*.example.com` websites and logo URLs that cannot resolve, so without
 * this the console fills with `ERR_NAME_NOT_RESOLVED` and drowns out the real defects the sweep is
 * looking for. Aborting them also makes a run hermetic and materially faster. The blocked hosts are
 * returned and recorded in the manifest so the suppression is visible rather than silent — if a
 * surface genuinely depended on a third-party origin, it would show up here.
 * @param context the browser context to constrain
 * @returns the set of off-origin hosts that were blocked
 */
export function blockExternalRequests(context: BrowserContext): Set<string> {
    const blocked = new Set<string>();
    void context.route('**/*', (route) => {
        const url = new URL(route.request().url());
        if (url.hostname === 'localhost' || url.hostname === '127.0.0.1') {
            void route.continue();
            return;
        }
        blocked.add(url.hostname);
        void route.abort();
    });
    return blocked;
}

/**
 * Selects the authenticated app shell.
 *
 * `ContentShell` is rendered from exactly one place — the `(app)` layout — so its presence is proof
 * the page rendered as an authenticated surface. The login and onboarding pages use the root layout
 * and emit no `<main>` at all. The attribute is locale-independent, unlike the sidebar's `aria-label`.
 */
export const AUTHENTICATED_SHELL_SELECTOR = '[data-app-main]';

/** Where a navigation actually ended up, relative to where it was aimed. */
export type Landing = {
    requestedPath: string;
    finalPath: string;
    acceptedPaths: readonly string[];
    inAuthenticatedShell: boolean;
    /** True only when the browser is on an accepted path *and* inside the authenticated shell. */
    ok: boolean;
};

/** Extracts the pathname of a matrix target, which may be relative and may carry a query. */
export function pathnameOf(target: string): string {
    try {
        return new URL(target, MATRIX_BASE_URL).pathname;
    } catch {
        return target;
    }
}

/**
 * Resolves where a navigation landed and whether that is acceptable.
 *
 * Must be consulted before a cell is recorded as a success. `acceptedPaths` defaults to the requested
 * path; a route that legitimately redirects declares its destinations in the inventory, which turns a
 * redirect from an invisible fact into a reviewable one.
 * @param page the page that has finished navigating
 * @param requestedPath the path the navigation aimed at
 * @param landsOn pathnames the route may legitimately settle on instead
 */
export async function landingOf(
    page: Page,
    requestedPath: string,
    landsOn?: readonly string[],
): Promise<Landing> {
    const acceptedPaths = (landsOn ?? [requestedPath]).map(pathnameOf);
    const finalPath = pathnameOf(page.url());
    const inAuthenticatedShell = (await page.locator(AUTHENTICATED_SHELL_SELECTOR).count()) > 0;
    return {
        requestedPath: pathnameOf(requestedPath),
        finalPath,
        acceptedPaths,
        inAuthenticatedShell,
        ok: acceptedPaths.includes(finalPath) && inAuthenticatedShell,
    };
}

/** Renders a landing as a failure message a reviewer can act on without opening the trace. */
export function describeLanding(landing: Landing): string {
    const shell = landing.inAuthenticatedShell
        ? 'inside the authenticated shell'
        : `outside the authenticated shell (no ${AUTHENTICATED_SHELL_SELECTOR})`;
    return `requested ${landing.requestedPath}, landed on ${landing.finalPath} ${shell}; `
        + `accepted: ${landing.acceptedPaths.join(', ')}`;
}

/** The state a cell is recorded under when it did not land where it was aimed. */
export const UNEXPECTED_LANDING_STATE = 'unexpected-landing';

/**
 * Locates the failed-section states on a page.
 *
 * `SectionUnavailable` carries `role="status"`, but so does the app's toast region — an empty
 * `aria-live` container present on every authenticated page. Counting bare `role="status"` therefore
 * reports one phantom degraded section on a perfectly healthy page. The retry control is what
 * distinguishes a real failed section, and it is locale-independent, unlike matching the heading.
 * @param page the page to inspect
 */
export function degradedSections(page: Page) {
    return page.locator('[role="status"]:has(button)');
}

/** A console error or uncaught page error observed while rendering a route. */
export type PageFault = { kind: 'console' | 'pageerror'; text: string };

/**
 * Attaches console/page-error capture to a page.
 *
 * Returns a live array rather than a promise because the interesting faults are the ones that occur
 * during navigation and hydration, and the caller inspects them after the page settles.
 */
export function captureFaults(page: Page): PageFault[] {
    const faults: PageFault[] = [];
    page.on('console', (message) => {
        if (message.type() === 'error') faults.push({ kind: 'console', text: message.text() });
    });
    page.on('pageerror', (error) => {
        faults.push({ kind: 'pageerror', text: error.message });
    });
    return faults;
}

const IGNORED_FAULTS: readonly RegExp[] = [
    /favicon\.ico/i,
    /Download the React DevTools/i,
    /net::ERR_(NAME_NOT_RESOLVED|BLOCKED_BY_CLIENT|FAILED|INTERNET_DISCONNECTED)/i,
    /Failed to load resource/i,
];

const KNOWN_FILED_FAULTS: readonly { pattern: RegExp; issue: string }[] = [];

/** A response the page requested that came back at or above 400. */
export type ResponseFailure = {
    status: number;
    url: string;
    /** Set when the failure matches a filed, already-triaged suppression. */
    knownIssue?: string;
};

/** The cell context a suppression may be scoped to. */
export type ResponseContext = { role: RouteRole };

/**
 * One filed, already-triaged failing response.
 *
 * Every field narrows the suppression. `pattern` is anchored at the origin so a query string cannot
 * smuggle an unrelated URL past it, `statuses` pins the exact failure that was triaged, and `roles`
 * scopes it to the cells where it is expected. A suppression without an `issue` does not belong here.
 */
export type KnownResponseFailure = {
    pattern: RegExp;
    statuses: readonly number[];
    roles?: readonly RouteRole[];
    issue: string;
};

const KNOWN_FILED_RESPONSES: readonly KnownResponseFailure[] = [
    {
        pattern: /^https?:\/\/[^/]+\/_next\/static\/chunks\/[^?#]*\.js(\?|#|$)/i,
        statuses: [404],
        issue: '#972 lazy chunk never emitted by the build',
    },
    {
        pattern: /^https?:\/\/[^/]+\/api\/custom-fields(\?|#|$)/i,
        statuses: [403],
        roles: ['member'],
        issue: '#973 admin-only catalog probed as a member',
    },
    {
        pattern: /^https?:\/\/[^/]+\/api\/auth\/(me|csrf)(\?|#|$)/i,
        statuses: [401],
        issue: 'bootstrap probe before the session attaches; a genuinely dead session now fails the '
            + 'landing assertion instead, so this suppression can no longer hide one',
    },
];

/**
 * Records every response at or above 400, with its URL.
 *
 * The console stream alone cannot support precise triage here — Chromium's message for a failed
 * subresource is generic and carries no URL — so the sweep watches responses directly and keeps the
 * URL and status, which is what makes the allowlist narrow enough to be safe.
 * @param page the page to observe
 * @returns a live array of failing responses
 */
export function captureResponseFailures(page: Page): ResponseFailure[] {
    const failures: ResponseFailure[] = [];
    page.on('response', (response) => {
        if (response.status() >= 400) failures.push({ status: response.status(), url: response.url() });
    });
    return failures;
}

/**
 * De-duplicates failing responses and annotates the ones covered by a filed suppression.
 *
 * Annotating rather than dropping keeps the manifest honest: a reviewer sees every failure the run
 * produced, and sees which of them were already triaged and under which issue.
 * @param failures the raw captures
 * @param context the role the cell rendered as, which some suppressions are scoped to
 */
export function classifyResponseFailures(
    failures: readonly ResponseFailure[],
    context: ResponseContext,
): ResponseFailure[] {
    const seen = new Set<string>();
    const classified: ResponseFailure[] = [];
    for (const failure of failures) {
        const key = `${failure.status} ${failure.url}`;
        if (seen.has(key)) continue;
        seen.add(key);
        const known = KNOWN_FILED_RESPONSES.find(
            (candidate) =>
                candidate.statuses.includes(failure.status)
                && (candidate.roles === undefined || candidate.roles.includes(context.role))
                && candidate.pattern.test(failure.url),
        );
        classified.push(known === undefined ? failure : { ...failure, knownIssue: known.issue });
    }
    return classified;
}

/** The classified failures that no filed suppression covers — the ones that must fail a cell. */
export function unexpectedResponseFailures(classified: readonly ResponseFailure[]): ResponseFailure[] {
    return classified.filter((failure) => failure.knownIssue === undefined);
}

/** Filters environmental noise out of captured faults, keeping everything a reviewer should see. */
export function significantFaults(faults: readonly PageFault[]): PageFault[] {
    return faults.filter((fault) => !IGNORED_FAULTS.some((pattern) => pattern.test(fault.text)));
}

/**
 * Faults that should fail a cell: significant, and not already filed.
 *
 * Kept separate from {@link significantFaults} so the manifest can record the known ones while the
 * assertion stays sensitive to new regressions.
 */
export function unexpectedFaults(faults: readonly PageFault[]): PageFault[] {
    return significantFaults(faults).filter(
        (fault) => !KNOWN_FILED_FAULTS.some((known) => known.pattern.test(fault.text)),
    );
}

export type ManifestEntry = {
    routeId: string;
    path: string;
    state: string;
    axes: Axes;
    screenshot: string;
    faults: PageFault[];
    responseFailures?: ResponseFailure[];
    httpStatus: number | null;
    /** The pathname the browser was actually on when the cell was captured. */
    finalPath: string;
    notes?: string;
};

const MANIFEST_PATH = path.join(MATRIX_ARTIFACT_DIR, 'manifest.jsonl');

async function settleForCapture(page: Page): Promise<void> {
    let previous = -1;
    for (let attempt = 0; attempt < 6; attempt += 1) {
        const current = await page.locator('main svg').count().catch(() => 0);
        if (current === previous) break;
        previous = current;
        await page.waitForTimeout(400);
    }
    await page.waitForTimeout(300);
    await expandScrollContainers(page);
}

async function expandScrollContainers(page: Page): Promise<void> {
    await page
        .evaluate(() => {
            const shells = document.querySelectorAll<HTMLElement>('div.h-dvh, main');
            shells.forEach((element) => {
                element.style.height = 'auto';
                element.style.maxHeight = 'none';
                element.style.overflow = 'visible';
            });
            document.documentElement.style.height = 'auto';
            document.documentElement.style.overflow = 'visible';
            document.body.style.height = 'auto';
        })
        .catch(() => undefined);
    await page.waitForTimeout(250);
}

/**
 * Records one matrix cell as evidence: a full-page screenshot plus a manifest line binding it to its
 * route, state, axes and the path the browser was actually on. Written as JSON Lines so parallel
 * workers append without clobbering, and so a partial run still yields a readable manifest.
 */
export async function record(
    page: Page,
    entry: Omit<ManifestEntry, 'screenshot'> & { screenshot?: string },
): Promise<void> {
    const file = entry.screenshot ?? artifactName(entry.routeId, entry.state, entry.axes);
    const target = path.join(MATRIX_ARTIFACT_DIR, 'shots', file);
    mkdirSync(path.dirname(target), { recursive: true });
    await settleForCapture(page);
    await page.screenshot({ path: target, fullPage: true, animations: 'disabled' });
    mkdirSync(MATRIX_ARTIFACT_DIR, { recursive: true });
    appendFileSync(MANIFEST_PATH, `${JSON.stringify({ ...entry, screenshot: `shots/${file}` })}\n`);
}

/** Writes the run-level provenance file that the report cites. */
export function writeRunInfo(info: Record<string, unknown>): void {
    mkdirSync(MATRIX_ARTIFACT_DIR, { recursive: true });
    writeFileSync(path.join(MATRIX_ARTIFACT_DIR, 'run-info.json'), JSON.stringify(info, null, 2));
}

/** Rewrites the fault-proxy rules file, which the proxy re-reads per request. */
export function setFaultRules(rules: {
    fail?: string[];
    forbid?: string[];
    delay?: { prefix: string; ms: number }[];
}): void {
    const file = process.env.FAULT_RULES_FILE ?? '/tmp/ws12-fault-rules.json';
    writeFileSync(file, JSON.stringify({ fail: [], forbid: [], delay: [], ...rules }));
}

/** Clears every injected fault. */
export function clearFaultRules(): void {
    setFaultRules({});
}
