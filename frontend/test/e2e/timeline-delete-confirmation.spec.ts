import { csrfBootstrap, seeder } from "./support/api";
import { expect, test } from "./support/cleanup";
import { runFixture } from "./support/fixtures";
import { useLocale } from "./support/locale";
import { message } from "./support/messages";

function isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === "object" && value !== null;
}

for (const locale of ["en", "ja"] as const) {
    test(`deal Open tasks deletion requires confirmation (${locale}) @mobile`, async ({ page, cleanup }, testInfo) => {
        const fixture = runFixture(testInfo.project.name);
        const csrf = await csrfBootstrap(page.request);
        const headers = { "X-Workspace-Id": String(fixture.workspaceId), [csrf.headerName]: csrf.token };
        const response = await page.request.get("/api/auth/me");
        expect(response.ok()).toBe(true);
        const user: unknown = await response.json();
        if (!isRecord(user) || typeof user.id !== "number") throw new Error("Missing current user");
        const name = `Deal task deletion ${locale} ${Date.now()}`;
        const created = await seeder(page.request, fixture.workspaceId, csrf).post("/api/tasks", {
            description: name, completed: false, assignedToId: user.id, dealId: fixture.deals.primary.id,
        });
        if (typeof created.id !== "number") throw new Error("Missing created task id");
        const path = `/api/tasks/${created.id}`;
        cleanup.add(async (api) => {
            const remaining = await api.get(path, { headers });
            if (remaining.status() === 404) return;
            expect(remaining.ok()).toBe(true);
            expect((await api.delete(path, { headers })).ok()).toBe(true);
        });
        await useLocale(page, locale);
        await page.goto(`/records/deals/${fixture.deals.primary.id}`);
        const section = page.getByRole("heading", { name: message(locale, "deals", "DealsTaskList.openTasks") })
            .locator("../..");
        const row = section.getByRole("listitem").filter({ hasText: name });
        await expect(row).toHaveCount(1);
        const actions = row.getByRole("button", { expanded: false });
        let deletes = 0;
        page.on("request", (request) => {
            if (request.method() === "DELETE" && new URL(request.url()).pathname === path) deletes++;
        });
        await actions.click();
        const remove = message(locale, "deals", "DealsTaskList.delete");
        await page.getByRole("menuitem", { name: remove, exact: true }).click();
        const dialog = page.getByRole("dialog");
        await expect(dialog).toBeVisible();
        await expect(dialog).toContainText(name);
        expect(deletes).toBe(0);
        await dialog.getByRole("button", { name: message(locale, "records", "RecordsDeleteDialog.cancel"), exact: true }).click();
        await expect(dialog).toBeHidden();
        await expect(row).toHaveCount(1);
        await expect(actions).toBeFocused();
        expect(deletes).toBe(0);
        expect((await page.request.get(path, { headers })).ok()).toBe(true);
        await actions.click();
        await page.getByRole("menuitem", { name: remove, exact: true }).click();
        await dialog.getByRole("button", { name: remove, exact: true }).click();
        await expect(dialog).toBeHidden();
        await expect(row).toHaveCount(0);
        expect(deletes).toBe(1);
        expect((await page.request.get(path, { headers })).status()).toBe(404);
    });

    for (const kind of ["task", "activity", "note"] as const) {
        test(`timeline ${kind} deletion requires confirmation (${locale}) @mobile`, async ({ page, cleanup }, testInfo) => {
            const fixture = runFixture(testInfo.project.name);
            const csrf = await csrfBootstrap(page.request);
            const headers = { "X-Workspace-Id": String(fixture.workspaceId), [csrf.headerName]: csrf.token };
            const me = await page.request.get("/api/auth/me");
            expect(me.ok()).toBe(true);
            const user: unknown = await me.json();
            if (!isRecord(user) || typeof user.id !== "number") throw new Error("Missing current user");
            const name = `Timeline deletion ${kind} ${locale} ${Date.now()}`;
            const endpoint = kind === "activity" ? "activities" : kind === "task" ? "tasks" : "notes";
            const payload = kind === "task"
                ? { description: name, completed: false, assignedToId: user.id, personId: fixture.contacts.watch.id }
                : kind === "activity"
                    ? { type: "call", subject: name, personId: fixture.contacts.watch.id, timestamp: new Date().toISOString().slice(0, 19).replace("T", " ") }
                    : { title: name, content: name, visibility: "workspace", person: fixture.contacts.watch.id };
            const created = await seeder(page.request, fixture.workspaceId, csrf).post(`/api/${endpoint}`, payload);
            if (typeof created.id !== "number") throw new Error("Missing created entry id");
            const path = `/api/${endpoint}/${created.id}`;
            cleanup.add(async (api) => {
                const remaining = await api.get(path, { headers });
                if (remaining.status() === 404) return;
                expect(remaining.ok()).toBe(true);
                expect((await api.delete(path, { headers })).ok()).toBe(true);
            });
            await useLocale(page, locale);
            await page.goto(`/records/contacts/${fixture.contacts.watch.id}?${kind}=${created.id}`);
            const row = page.getByRole("listitem").filter({ hasText: name });
            await expect(row).toHaveCount(1);
            const remove = message(locale, "me", "MeTimeline.delete");
            const actions = message(locale, "me", "MeTimeline.actionsAria");
            let deletes = 0;
            page.on("request", (request) => {
                if (request.method() === "DELETE" && new URL(request.url()).pathname === path) deletes++;
            });
            await row.getByRole("button", { name: actions, exact: true }).click();
            await page.getByRole("menuitem", { name: remove, exact: true }).click();
            const dialog = page.getByRole("dialog");
            await expect(dialog).toContainText(name);
            await expect(dialog).toContainText(locale === "en" ? "This can't be undone." : "この操作は取り消せません。");
            expect(deletes).toBe(0);
            await dialog.getByRole("button", { name: message(locale, "records", "RecordsDeleteDialog.cancel"), exact: true }).click();
            await expect(dialog).toBeHidden();
            await expect(row.getByRole("button", { name: actions, exact: true })).toBeFocused();
            expect((await page.request.get(path, { headers })).ok()).toBe(true);
            await row.getByRole("button", { name: actions, exact: true }).click();
            await page.getByRole("menuitem", { name: remove, exact: true }).click();
            await dialog.getByRole("button", { name: remove, exact: true }).click();
            await expect(dialog).toBeHidden();
            await expect(row).toHaveCount(0);
            expect(deletes).toBe(1);
            expect((await page.request.get(path, { headers })).status()).toBe(404);
        });
    }
}
