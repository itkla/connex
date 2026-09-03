import { expect, test, type APIRequestContext, type Page } from "@playwright/test";

import { activeWorkspaceId, csrfBootstrap } from "./support/api";
import { runFixture } from "./support/fixtures";
import { message } from "./support/messages";

/**
 * The Ask Connex command centre and typed watches, exercised against a provider-less stack.
 *
 * Watch creation, listing, pausing, and deletion are entirely deterministic: no model is involved in
 * deciding whether a watch fired, and the command-centre read is a plain authorized projection. That
 * is exactly why this journey is testable without an AI provider configured, while the *content* of a
 * brief is not — generating one requires real provider egress and is left to live evaluation.
 */
function copy(key: string): string {
    return message("en", "common", `AskConnex.commandCenter.${key}`);
}

/** Creates one watch through the same endpoint the typed dialog posts to. */
async function createWatch(
    api: APIRequestContext,
    workspaceId: number,
    subjectId: number,
): Promise<number> {
    const csrf = await csrfBootstrap(api);
    const response = await api.post("/api/ai/assistant/watches", {
        timeout: 120_000,
        headers: {
            "X-Workspace-Id": String(workspaceId),
            [csrf.headerName]: csrf.token,
        },
        data: {
            watchType: "relationship_cooling",
            subjectKind: "person",
            subjectId,
            thresholdBand: "cold",
        },
    });
    expect(response.status(), await response.text()).toBe(201);
    const body = (await response.json()) as { id: number };
    expect(body.id).toBeGreaterThan(0);
    return body.id;
}

/** Removes one watch, tolerating an already-deleted row so cleanup never fails a passing run. */
async function deleteWatch(api: APIRequestContext, workspaceId: number, id: number) {
    const csrf = await csrfBootstrap(api);
    await api.delete(`/api/ai/assistant/watches/${id}`, {
        timeout: 120_000,
        headers: {
            "X-Workspace-Id": String(workspaceId),
            [csrf.headerName]: csrf.token,
        },
    });
}

/** The watch row for one record, addressed by the trigger sentence the surface states. */
function watchRow(page: Page, subjectName: string) {
    return page.getByRole("listitem").filter({ hasText: subjectName });
}

test.describe("Ask Connex command centre", () => {
    test("states a watch's exact trigger and lets its owner pause and delete it", async ({
        page,
    }, testInfo) => {
        const fixture = runFixture(testInfo.project.name);
        const contact = fixture.contacts.watch;
        const api = page.request;
        const workspaceId = await activeWorkspaceId(api);
        const watchId = await createWatch(api, workspaceId, contact.id);

        try {
            await page.goto("/ask-connex");

            await expect(page.getByRole("heading", { name: copy("watchesTitle") }))
                .toBeVisible();
            await expect(page.getByRole("heading", { name: copy("briefsTitle") }))
                .toBeVisible();

            const row = watchRow(page, contact.name);
            await expect(row).toBeVisible();
            // The trigger is stated verbatim, not summarized: this is the inspectability contract.
            await expect(row).toContainText("Warmth reaches cold");
            await expect(row).toContainText(copy("statusActive"));
            await expect(row).toContainText(copy("neverFired"));

            await row.getByRole("button", { name: copy("watchActions") }).click();
            await page.getByRole("menuitem", { name: copy("pause") }).click();
            await expect(watchRow(page, contact.name)).toContainText(copy("statusPaused"));

            await watchRow(page, contact.name)
                .getByRole("button", { name: copy("watchActions") })
                .click();
            await page.getByRole("menuitem", { name: copy("delete") }).click();
            // Deleting a watch is destructive and irreversible, so it is confirmed like any other.
            const confirmation = page.getByRole("dialog");
            await expect(confirmation.getByRole("heading", { name: copy("deleteTitle") }))
                .toBeVisible();
            await confirmation.getByRole("button", { name: copy("delete") }).click();
            await expect(watchRow(page, contact.name)).toHaveCount(0);
        } finally {
            await deleteWatch(api, workspaceId, watchId);
        }
    });

    test("offers the typed watch contract from the record before anything is saved", async ({
        page,
    }, testInfo) => {
        const fixture = runFixture(testInfo.project.name);
        const contact = fixture.contacts.watch;

        await page.goto(`/records/contacts/${contact.id}`);
        await page.getByRole("button", { name: message("en", "common", "AskConnex.entryPoint.recordMenu.person") })
            .click();
        await page.getByRole("menuitem", { name: copy("createMenuItem") }).click();

        const dialog = page.getByRole("dialog");
        await expect(dialog.getByRole("heading", { name: copy("createTitle") })).toBeVisible();
        await expect(dialog).toContainText(contact.name);
        // The preview restates the condition the server will evaluate, before it is applied.
        await expect(dialog).toContainText(copy("createPreviewLabel"));
        await expect(dialog).toContainText("Warmth reaches cold");
        // The terms that bind without being chosen are stated too, before anything is applied.
        await expect(dialog).toContainText("At most once every 7 days");
        await expect(dialog).toContainText(copy("expiresNever"));
        await dialog.getByRole("button", { name: copy("createCancel") }).click();
        await expect(dialog).toHaveCount(0);
    });
});
