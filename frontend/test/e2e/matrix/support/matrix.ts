import { appendFileSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import type { Browser, BrowserContext, Page } from '@playwright/test';

import { MATRIX_ARTIFACT_DIR, MATRIX_FIXTURE_PATH, storageStateFor } from '../../../../playwright.matrix.config';
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

/** The tenant/record facts the seeder produced, resolved once by the setup project. */
export type MatrixFixture = {
    workspaceId: number;
    users: Record<RouteRole, string>;
    password: string;
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
        { name: 'NEXT_LOCALE', value: axes.locale, domain: 'localhost', path: '/' },
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

/**
 * Faults that are environmental rather than product defects.
 *
 * Kept deliberately short and specific: a broad filter here would silently swallow the very class of
 * defect the sweep exists to find, so each entry names a cause that cannot be a product bug.
 */
const IGNORED_FAULTS: readonly RegExp[] = [
    /favicon\.ico/i,
    /Download the React DevTools/i,
    /net::ERR_(NAME_NOT_RESOLVED|BLOCKED_BY_CLIENT|FAILED|INTERNET_DISCONNECTED)/i,
    /Failed to load resource/i,
];

/**
 * Faults that are real, already triaged, and filed — excluded from the pass/fail assertion so the
 * sweep keeps finding *new* defects, but still written to the manifest for every cell that produced
 * them. Suppressing these silently would be dishonest; leaving them un-suppressed would mean the
 * sweep reports the same two known issues several hundred times and hides anything else.
 *
 * Each entry must name the filed issue. An entry without one does not belong here.
 */
const KNOWN_FILED_FAULTS: readonly { pattern: RegExp; issue: string }[] = [];

/** A response the page requested that came back at or above 400. */
export type ResponseFailure = { status: number; url: string };

/**
 * Failing responses that are real, already filed, and expected on every load.
 *
 * Matched on URL rather than on console text: Chromium's console message for a failed subresource is
 * generic ("Failed to load resource: the server responded with a status of 404") and carries no URL,
 * so allowlisting by console text would mean suppressing *every* 404 — exactly the over-broad
 * suppression that would hide the next real defect.
 */
const KNOWN_FILED_RESPONSES: readonly { pattern: RegExp; issue: string }[] = [
    { pattern: /\/_next\/static\/chunks\/.*\.js$/i, issue: '#972 lazy chunk never emitted by the build' },
    { pattern: /\/api\/custom-fields(\?|$)/i, issue: '#973 admin-only catalog probed as a member' },
    { pattern: /\/api\/auth\/(me|csrf)(\?|$)/i, issue: 'unauthenticated bootstrap probe before session attaches' },
];

/**
 * Records every response at or above 400, with its URL.
 *
 * The console stream alone cannot support precise triage here, so the sweep watches responses
 * directly and keeps the URL, which is what makes the allowlist above narrow enough to be safe.
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

/** Failing responses that are not already filed, de-duplicated by status and URL. */
export function unexpectedResponseFailures(failures: readonly ResponseFailure[]): ResponseFailure[] {
    const seen = new Set<string>();
    return failures.filter((failure) => {
        if (KNOWN_FILED_RESPONSES.some((known) => known.pattern.test(failure.url))) return false;
        const key = `${failure.status} ${failure.url}`;
        if (seen.has(key)) return false;
        seen.add(key);
        return true;
    });
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
    notes?: string;
};

const MANIFEST_PATH = path.join(MATRIX_ARTIFACT_DIR, 'manifest.jsonl');

/**
 * Waits for the page to stop changing shape before a screenshot is taken.
 *
 * `networkidle` is not sufficient on chart-heavy surfaces: recharts paints after its data arrives,
 * and entrance animations are frozen at their first frame by `animations: "disabled"`, so capturing
 * immediately produces a blank content area that misrepresents a perfectly healthy page. Polling the
 * rendered SVG count until it stops growing is cheap and targets exactly that failure.
 * @param page the page about to be captured
 */
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

/**
 * Makes the app's inner scroll container capturable by a full-page screenshot.
 *
 * The shell is `h-dvh overflow-hidden` with `<main>` as the scrolling element, so the *document* is
 * always exactly one viewport tall no matter how long the page is. `fullPage: true` therefore
 * captures only the first screenful and silently crops everything below the fold — on a chart-heavy
 * surface that reads as a blank page rather than as a page that scrolls.
 *
 * Releasing the fixed height just before capture lets the document grow to the real content height.
 * This mutates layout and so runs only immediately before a screenshot, never before an assertion.
 * @param page the page about to be captured
 */
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
            document.body.style.height = 'auto';
        })
        .catch(() => undefined);
    await page.waitForTimeout(250);
}

/**
 * Records one matrix cell as evidence: a full-page screenshot plus a manifest line binding it to its
 * route, state and axes. Written as JSON Lines so parallel workers append without clobbering, and so
 * a partial run still yields a readable manifest.
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
