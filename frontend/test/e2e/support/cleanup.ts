import { test as base, type APIRequestContext } from "@playwright/test";

/** Collects the undo steps for records a test seeds into a shared project tenant. */
export type Cleanup = {
    add: (step: (api: APIRequestContext) => Promise<void>) => void;
};

/**
 * The Playwright `test` with a `cleanup` fixture. Every step a test adds runs after the test body,
 * pass or fail, with the page's own request context, before the page and its session close, so a
 * seeded record never outlives the run that made it. A failed step fails the test rather than
 * leaving the tenant silently polluted.
 */
export const test = base.extend<{ cleanup: Cleanup }>({
    cleanup: async ({ page }, runTest) => {
        const steps: ((api: APIRequestContext) => Promise<void>)[] = [];
        try {
            await runTest({ add: (step) => steps.push(step) });
        } finally {
            const results = await Promise.allSettled(steps.map((step) => step(page.request)));
            const failures: unknown[] = results.flatMap((result) => (result.status === "rejected" ? [result.reason] : []));
            if (failures.length > 0) {
                throw new AggregateError(failures, "Failed to remove the records this test seeded");
            }
        }
    },
});

export { expect } from "@playwright/test";
