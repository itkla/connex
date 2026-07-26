import path from "node:path";
import { defineConfig, devices } from "@playwright/test";

/** Base URL of the running frontend the suite drives; the backend must be reachable through its /api proxy. */
export const E2E_BASE_URL = process.env.E2E_BASE_URL ?? "http://localhost:3000";

/** Directory for per-run bootstrap artifacts (storage state + seeded-record fixture). Never committed. */
export const E2E_ARTIFACT_DIR = path.resolve(import.meta.dirname, "test/e2e/.artifacts");

export const STORAGE_STATE_PATH = path.join(E2E_ARTIFACT_DIR, "storage-state.json");
export const RUN_FIXTURE_PATH = path.join(E2E_ARTIFACT_DIR, "run.json");

export default defineConfig({
    testDir: "./test/e2e",
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
            name: "setup",
            testMatch: /global\.setup\.ts/,
        },
        {
            name: "chromium",
            use: {
                ...devices["Desktop Chrome"],
                storageState: STORAGE_STATE_PATH,
            },
            dependencies: ["setup"],
        },
    ],
});
