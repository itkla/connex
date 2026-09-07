import path from "node:path";
import { defineConfig, devices } from "@playwright/test";

/** Base URL of the running frontend the suite drives; the backend must be reachable through its /api proxy. */
export const E2E_BASE_URL = process.env.E2E_BASE_URL ?? "http://localhost:3000";

/** Directory for per-run bootstrap artifacts (storage state + seeded-record fixture). Never committed. */
export const E2E_ARTIFACT_DIR = path.resolve(__dirname, "test/e2e/.artifacts");

export type E2ETenantScope = "desktop" | "mobile";

/** Browser storage state for one project-isolated tenant. */
export function storageStatePath(scope: E2ETenantScope): string {
    return path.join(E2E_ARTIFACT_DIR, scope, "storage-state.json");
}

/** Seeded-record fixture for one project-isolated tenant. */
export function runFixturePath(scope: E2ETenantScope): string {
    return path.join(E2E_ARTIFACT_DIR, scope, "run.json");
}

/** Maps a setup or browser project to the tenant scope it exclusively owns. */
export function tenantScopeForProject(projectName: string): E2ETenantScope {
    if (projectName === "setup-mobile" || projectName === "mobile-chromium") {
        return "mobile";
    }
    if (projectName === "setup-desktop" || projectName === "chromium") {
        return "desktop";
    }
    throw new Error(`Project ${projectName} has no E2E tenant scope`);
}

/** Gradle console log of the deterministic volume-seeder run that populated the stack under test. */
export const SEED_LOG_PATH = process.env.E2E_SEED_LOG ?? path.join(E2E_ARTIFACT_DIR, "seeder.log");

/** Tests whose title carries this tag also run in the phone-viewport project. */
export const MOBILE_TAG = /@mobile/;

/** Tests whose title carries this tag run *only* in the phone-viewport project. */
export const MOBILE_ONLY_TAG = /@mobile-only/;

export default defineConfig({
    testDir: "./test/e2e",
    testIgnore: "**/matrix/**",
    fullyParallel: true,
    forbidOnly: !!process.env.CI,
    retries: process.env.CI ? 2 : 0,
    workers: 2,
    reporter: process.env.CI ? [["list"], ["html", { open: "never" }]] : [["list"]],
    timeout: 45_000,
    expect: { timeout: 10_000 },
    use: {
        baseURL: E2E_BASE_URL,
        trace: "retain-on-failure",
        screenshot: "only-on-failure",
        locale: "en-US",
        timezoneId: "UTC",
        contextOptions: { reducedMotion: "reduce" },
    },
    projects: [
        {
            name: "setup-desktop",
            testMatch: /global\.setup\.ts/,
        },
        {
            name: "setup-mobile",
            testMatch: /global\.setup\.ts/,
        },
        {
            name: "chromium",
            use: {
                ...devices["Desktop Chrome"],
                storageState: storageStatePath("desktop"),
            },
            grepInvert: MOBILE_ONLY_TAG,
            dependencies: ["setup-desktop"],
        },
        {
            name: "mobile-chromium",
            use: {
                ...devices["Pixel 7"],
                storageState: storageStatePath("mobile"),
            },
            grep: MOBILE_TAG,
            dependencies: ["setup-mobile"],
        },
    ],
});
