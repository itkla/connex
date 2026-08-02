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
    /Failed to load resource: the server responded with a status of 401/i,
    /favicon\.ico/i,
    /Download the React DevTools/i,
    /net::ERR_(NAME_NOT_RESOLVED|BLOCKED_BY_CLIENT|FAILED|INTERNET_DISCONNECTED)/i,
];

/** Filters environmental noise out of captured faults. */
export function significantFaults(faults: readonly PageFault[]): PageFault[] {
    return faults.filter((fault) => !IGNORED_FAULTS.some((pattern) => pattern.test(fault.text)));
}

export type ManifestEntry = {
    routeId: string;
    path: string;
    state: string;
    axes: Axes;
    screenshot: string;
    faults: PageFault[];
    httpStatus: number | null;
    notes?: string;
};

const MANIFEST_PATH = path.join(MATRIX_ARTIFACT_DIR, 'manifest.jsonl');

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
