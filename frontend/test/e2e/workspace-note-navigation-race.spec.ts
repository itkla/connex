import { expect, test } from "@playwright/test";

import { activeWorkspaceId, csrfBootstrap, registerUser } from "./support/api";
import { message } from "./support/messages";

function deferred() {
    let resolve!: () => void;
    const promise = new Promise<void>((done) => { resolve = done; });
    return { promise, resolve };
}

test("a first note save cannot replace a pending workspace navigation URL", async ({ browser }, testInfo) => {
    test.setTimeout(120_000);
    const baseURL = testInfo.project.use.baseURL;
    if (typeof baseURL !== "string") throw new Error("The E2E project requires a base URL");
    const context = await browser.newContext({ baseURL, locale: "en-US", timezoneId: "UTC", reducedMotion: "reduce", storageState: { cookies: [], origins: [] } });
    const releaseNote = deferred();
    const releaseDashboard = deferred();
    try {
        const run = Date.now().toString(36) + Math.random().toString(36).slice(2, 8);
        await registerUser(context.request, { username: "chain_" + run, password: "ChainHarness!" + run + "A1", email: "chain_" + run + "@example.test", displayName: "Chain origin " + run });
        const origin = await activeWorkspaceId(context.request);
        const csrf = await csrfBootstrap(context.request);
        const nextName = "Chain destination " + run;
        const created = await context.request.post("/api/workspaces", {
            headers: { "X-Workspace-Id": String(origin), [csrf.headerName]: csrf.token }, data: { name: nextName },
        });
        expect(created.status(), await created.text()).toBe(200);
        const workspace: unknown = await created.json();
        if (typeof workspace !== "object" || workspace === null || !("id" in workspace) || typeof workspace.id !== "number") throw new Error("Workspace creation returned no id");
        const next = workspace.id;
        const switched = await context.request.post("/api/workspaces/" + origin + "/switch", { headers: { [csrf.headerName]: csrf.token } });
        expect(switched.status(), await switched.text()).toBe(200);
        const page = await context.newPage();
        await page.addInitScript(() => {
            const replace = window.history.replaceState.bind(window.history);
            window.history.replaceState = (state: unknown, unused: string, url?: string | URL | null) => {
                if (url && /\/activity\/notes\/\d+/.test(String(url))) document.documentElement.dataset.noteUrlWrite = "true";
                replace(state, unused, url);
            };
        });
        const noteStarted = deferred();
        const navigationStarted = deferred();
        await page.route("**/dashboard*", async (route) => {
            if (route.request().headers()["next-router-prefetch"]) { await route.abort(); return; }
            if (route.request().headers().rsc !== "1") { await route.continue(); return; }
            navigationStarted.resolve();
            await releaseDashboard.promise;
            await route.continue();
        });
        await page.goto("/activity/notes/new");
        await page.route("**/api/notes", async (route) => {
            if (route.request().method() !== "POST") { await route.continue(); return; }
            expect(route.request().headers()["x-workspace-id"]).toBe(String(origin));
            const response = await route.fetch();
            expect(response.status()).toBe(200);
            noteStarted.resolve();
            await releaseNote.promise;
            await route.fulfill({ response });
        });
        await page.locator('.tiptap[contenteditable="true"]').fill("Origin note " + run);
        await noteStarted.promise;
        await page.getByRole("button", { name: "Switch workspace" }).click();
        const selection = page.waitForResponse((response) => new URL(response.url()).pathname === "/api/workspaces/" + next + "/switch" && response.request().method() === "POST");
        await page.getByRole("menuitem").filter({ hasText: nextName }).click();
        expect((await selection).status()).toBe(200);
        await navigationStarted.promise;
        releaseNote.resolve();
        await expect(page.getByText(message("en", "activity", "ActivityNotesEditor.saved"), { exact: true })).toBeVisible();
        await expect(page.locator("html")).not.toHaveAttribute("data-note-url-write", "true");
        releaseDashboard.resolve();
        await expect(page).toHaveURL(/\/dashboard$/);
        await expect(page.getByRole("button", { name: "Switch workspace" })).toContainText(nextName);
    } finally {
        releaseNote.resolve();
        releaseDashboard.resolve();
        await context.close();
    }
});
