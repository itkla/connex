import path from 'node:path';
import { defineConfig, devices } from '@playwright/test';

/**
 * Playwright configuration for the Wave 4 (#856) route/state matrix.
 *
 * Kept separate from `playwright.config.ts` so the evidence pass cannot destabilise the eight
 * critical-flow specs that gate CI, and so the matrix can pin axes those specs deliberately fix — the
 * flow suite pins `reducedMotion: "reduce"` globally, which means the animated path is never
 * exercised there; the matrix runs both.
 */

/** Base URL of the running frontend. The matrix drives a production build, not `next dev`. */
export const MATRIX_BASE_URL = process.env.E2E_BASE_URL ?? 'http://localhost:3000';

/** Root for matrix artifacts: screenshots, the evidence manifest, and run provenance. */
export const MATRIX_ARTIFACT_DIR = path.resolve(__dirname, 'test/e2e/.artifacts/matrix');

/** Tenant and credential facts resolved by the matrix setup project. */
export const MATRIX_FIXTURE_PATH = path.join(MATRIX_ARTIFACT_DIR, 'fixture.json');

/** Storage state for one seeded role. */
export function storageStateFor(role: 'owner' | 'admin' | 'member'): string {
    return path.join(MATRIX_ARTIFACT_DIR, `storage-${role}.json`);
}

export default defineConfig({
    testDir: './test/e2e/matrix',
    fullyParallel: false,
    forbidOnly: !!process.env.CI,
    retries: 0,
    workers: 1,
    reporter: [['list'], ['html', { open: 'never', outputFolder: 'test/e2e/.artifacts/matrix/report' }]],
    timeout: 120_000,
    expect: { timeout: 15_000 },
    outputDir: 'test/e2e/.artifacts/matrix/test-results',
    use: {
        baseURL: MATRIX_BASE_URL,
        trace: 'retain-on-failure',
        screenshot: 'off',
        video: 'off',
        timezoneId: 'UTC',
    },
    projects: [
        { name: 'setup', testMatch: /matrix\.setup\.ts/ },
        {
            name: 'matrix',
            use: { ...devices['Desktop Chrome'] },
            dependencies: ['setup'],
            testIgnore: /matrix\.setup\.ts/,
        },
    ],
});
