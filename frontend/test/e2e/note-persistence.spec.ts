import { randomUUID } from "node:crypto";
import { expect, test, type Page, type Response } from "@playwright/test";

import { activeWorkspaceId, csrfBootstrap, registerUser } from "./support/api";
import { runFixture } from "./support/fixtures";
import { message } from "./support/messages";
import { acceptEmailInvitation } from "./support/invitations";

function savesContent(response: Response, content: string): boolean {
    if (!["POST", "PUT"].includes(response.request().method())) return false;
    const body: unknown = response.request().postDataJSON();
    return typeof body === "object" && body !== null && "content" in body && body.content === content;
}

function nullableLinkId(value: unknown): number | null {
    if (value == null) return null;
    if (typeof value !== "number" || !Number.isInteger(value)) throw new Error("Invalid note link id");
    return value;
}

function noteLinks(value: unknown) {
    if (typeof value !== "object" || value === null) throw new Error("Invalid note response");
    return {
        person: nullableLinkId("person" in value ? value.person : undefined),
        deal: nullableLinkId("deal" in value ? value.deal : undefined),
    };
}

async function selectMention(page: Page, label: string) {
    const editor = page.locator('.tiptap[contenteditable="true"]');
    await editor.fill("Owner: ");
    await editor.press("End");
    await editor.pressSequentially(`@${label.split(" ")[0]}`);
    await page.getByRole("option").filter({ hasText: label }).click();
    return editor;
}

for (const surface of ["dialog", "page"] as const) {
    for (const typeAfter of [false, true]) {
        test(`${surface} saves a contact mention ${typeAfter ? "after typing" : "immediately"}`, async ({ page, request }, testInfo) => {
            const fixture = runFixture(testInfo.project.name);
            const contact = fixture.contacts.activity;
            const errors: string[] = [];
            page.on("pageerror", (error) => errors.push(error.message));
            if (surface === "dialog") {
                await page.goto(`/records/contacts/${contact.id}`);
                await page.getByRole("main").getByRole("button", { name: "New", exact: true }).click();
                await page.getByRole("menuitem", { name: "Add note" }).click();
            } else {
                await page.goto("/activity/notes/new");
            }
            const expected = `Owner: [${contact.name}](person:${contact.id})${typeAfter ? " and more text" : " "}`;
            const saved = page.waitForResponse((response) =>
                /\/api\/notes(?:\/\d+)?$/.test(new URL(response.url()).pathname)
                && savesContent(response, expected));
            const editor = await selectMention(page, contact.name);
            if (typeAfter) await editor.pressSequentially("and more text");
            if (surface === "dialog") {
                await page.getByRole("button", { name: "Create", exact: true }).click();
            } else {
                await page.evaluate(() => window.dispatchEvent(new Event("beforeunload")));
            }
            const response = await saved;
            expect(response.status(), await response.text()).toBe(200);
            const note: unknown = await response.json();
            if (typeof note !== "object" || note === null || !("id" in note) || typeof note.id !== "number") {
                throw new Error("Note response has no id");
            }
            expect(note).toMatchObject({ content: expected });
            const persisted = await request.get(`/api/notes/${note.id}`);
            expect(await persisted.json()).toMatchObject({ content: expected });
            await page.goto(`/activity/notes/${note.id}`);
            await expect(page.locator(`.tiptap [data-ref-type="person"][data-ref-id="${contact.id}"]`)).toBeVisible();
            expect(errors).toEqual([]);
        });
    }
}

test("full-page autosave preserves links and reconciles its chip with the response", async ({ page, request }, testInfo) => {
    const fixture = runFixture(testInfo.project.name);
    const csrf = await csrfBootstrap(request);
    const headers = { "X-Workspace-Id": String(fixture.workspaceId), [csrf.headerName]: csrf.token };
    const created = await request.post("/api/notes", { headers, data: {
        content: "Original linked note", person: fixture.contacts.activity.id, deal: fixture.deals.primary.id,
    } });
    const note: unknown = await created.json();
    if (typeof note !== "object" || note === null || !("id" in note) || typeof note.id !== "number") throw new Error("Missing note id");
    await page.goto(`/activity/notes/${note.id}`);
    const save = page.waitForResponse((response) => response.url().endsWith(`/api/notes/${note.id}`) && response.request().method() === "PUT");
    await page.locator('.tiptap[contenteditable="true"]').fill("Edited linked note");
    const response = await save;
    expect(response.request().postDataJSON()).toMatchObject({ person: fixture.contacts.activity.id, deal: fixture.deals.primary.id });
    expect(await response.json()).toMatchObject({ person: fixture.contacts.activity.id, deal: fixture.deals.primary.id });
    await page.reload();
    await expect(page.getByRole("link", { name: fixture.contacts.activity.name, exact: true })).toBeVisible();
    const persisted = await request.get(`/api/notes/${note.id}`);
    expect(await persisted.json()).toMatchObject({ person: fixture.contacts.activity.id, deal: fixture.deals.primary.id });

    await page.route(`**/api/notes/${note.id}`, async (route) => {
        if (route.request().method() !== "PUT") return route.continue();
        const response = await route.fetch();
        const body: unknown = await response.json();
        if (typeof body !== "object" || body === null) throw new Error("Invalid note response");
        const withoutLinks = Object.fromEntries(Object.entries(body).filter(([key]) => key !== "person" && key !== "deal"));
        await route.fulfill({ response, json: withoutLinks });
    });
    await page.locator('.tiptap[contenteditable="true"]').fill("Response reconciliation");
    await expect(page.getByRole("link", { name: fixture.contacts.activity.name, exact: true })).toHaveCount(0);
});

test("HTTP updates distinguish omitted links from explicit null", async ({ request }, testInfo) => {
    const fixture = runFixture(testInfo.project.name);
    const csrf = await csrfBootstrap(request);
    const headers = { "X-Workspace-Id": String(fixture.workspaceId), [csrf.headerName]: csrf.token };
    const created = await request.post("/api/notes", { headers, data: {
        content: "Linked note", person: fixture.contacts.activity.id, deal: fixture.deals.primary.id,
    } });
    expect(created.status()).toBe(200);
    const note: unknown = await created.json();
    if (typeof note !== "object" || note === null || !("id" in note) || typeof note.id !== "number") throw new Error("Missing note id");
    const omitted = await request.put(`/api/notes/${note.id}`, { headers, data: { content: "Omitted links" } });
    expect(omitted.status()).toBe(200);
    expect(await omitted.json()).toMatchObject({ person: fixture.contacts.activity.id, deal: fixture.deals.primary.id });
    const clearPerson = await request.put(`/api/notes/${note.id}`, { headers, data: { content: "Clear contact", person: null } });
    expect(clearPerson.status()).toBe(200);
    expect(noteLinks(await clearPerson.json())).toEqual({ person: null, deal: fixture.deals.primary.id });
    const clearDeal = await request.put(`/api/notes/${note.id}`, { headers, data: { content: "Clear deal", deal: null } });
    expect(clearDeal.status()).toBe(200);
    expect(noteLinks(await clearDeal.json())).toEqual({ person: null, deal: null });
    expect(noteLinks(await (await request.get(`/api/notes/${note.id}`)).json())).toEqual({ person: null, deal: null });
});

test("metadata edits preserve body bytes and the dialog can explicitly remove links", async ({ page, request }, testInfo) => {
    const fixture = runFixture(testInfo.project.name);
    const csrf = await csrfBootstrap(request);
    const headers = { "X-Workspace-Id": String(fixture.workspaceId), [csrf.headerName]: csrf.token };
    const marker = `Roundtrip${randomUUID().slice(0, 8)}`;
    const content = `  Plain text  with spacing\n\nExisting [${fixture.contacts.activity.name}](person:${fixture.contacts.activity.id})\n`;
    const response = await request.post("/api/notes", { headers, data: {
        title: marker, content, person: fixture.contacts.activity.id, deal: fixture.deals.primary.id,
    } });
    expect(response.status()).toBe(200);
    const note: unknown = await response.json();
    if (typeof note !== "object" || note === null || !("id" in note) || typeof note.id !== "number") throw new Error("Missing note id");
    await page.goto(`/activity/notes/${note.id}`);
    const fullPageSave = page.waitForResponse((response) => response.url().endsWith(`/api/notes/${note.id}`) && response.request().method() === "PUT");
    await page.locator("#note-title").fill(`${marker} revised`);
    expect(await (await fullPageSave).json()).toMatchObject({ content });

    await page.goto(`/activity/notes?q=${marker}`);
    await page.getByRole("button", { name: "Note actions" }).click();
    await page.getByRole("menuitem", { name: "Edit", exact: true }).click();
    const dialog = page.getByRole("dialog", { name: "Edit note", exact: true });
    await expect(dialog).toBeVisible();
    await expect(dialog.getByRole("combobox", { name: "Contact", exact: true })).toHaveValue(fixture.contacts.activity.name);
    await expect(dialog.getByRole("combobox", { name: "Deal", exact: true })).toHaveValue(fixture.deals.primary.name);
    await dialog.getByRole("combobox", { name: "Contact", exact: true }).fill("");
    await dialog.getByRole("combobox", { name: "Deal", exact: true }).fill("");
    await dialog.getByRole("combobox", { name: "Deal", exact: true }).press("Tab");
    await expect(dialog.getByRole("combobox", { name: "Contact", exact: true })).toHaveValue("");
    await expect(dialog.getByRole("combobox", { name: "Deal", exact: true })).toHaveValue("");
    const dialogSave = page.waitForResponse((response) => response.url().endsWith(`/api/notes/${note.id}`) && response.request().method() === "PUT");
    await dialog.getByRole("button", { name: "Save", exact: true }).click();
    expect((await dialogSave).request().postDataJSON()).toMatchObject({ person: null, deal: null, content });
    const persisted = await request.get(`/api/notes/${note.id}`);
    const saved: unknown = await persisted.json();
    expect(saved).toMatchObject({ content });
    expect(noteLinks(saved)).toEqual({ person: null, deal: null });
});

test("a saved member mention creates a note.mention notification", async ({ browser }) => {
    test.setTimeout(120_000);
    const owner = await browser.newContext({ storageState: { cookies: [], origins: [] } });
    const page = await owner.newPage();
    const request = owner.request;
    const marker = randomUUID().replace(/-/g, "").slice(0, 12);
    const password = `MentionOwner!${marker}A1`;
    const member = await browser.newContext({ storageState: { cookies: [], origins: [] } });
    const authenticator = await page.context().newCDPSession(page);
    try {
        await registerUser(request, { username: `owner_${marker}`, password, email: `owner_${marker}@example.test` });
        const workspaceId = await activeWorkspaceId(request);
        await authenticator.send("WebAuthn.enable");
        await authenticator.send("WebAuthn.addVirtualAuthenticator", { options: {
            protocol: "ctap2", ctap2Version: "ctap2_1", transport: "internal",
            hasResidentKey: true, hasUserVerification: true, automaticPresenceSimulation: true, isUserVerified: true,
        } });
        await page.goto("/settings/personal/security");
        const copy = (key: string) => message("en", "account", `AccountSecurity.${key}`);
        await page.getByRole("button", { name: copy("add") }).first().click();
        const passwordDialog = page.getByRole("dialog", { name: copy("passwordTitle") });
        await passwordDialog.getByLabel(copy("passwordLabel")).fill(password);
        await passwordDialog.getByRole("button", { name: copy("continue"), exact: true }).click();
        await expect(page.getByText(copy("added"), { exact: true })).toBeVisible();
        const label = `MentionRecipient${marker}`;
        const email = `mention_${marker}@example.test`;
        await registerUser(member.request, { username: `mention_${marker}`, password: `MentionHarness!${marker}A1`, email, displayName: label });
        const csrf = await csrfBootstrap(request);
        const invitation = await request.post(`/api/workspaces/${workspaceId}/members`, {
            headers: { "X-Workspace-Id": String(workspaceId), [csrf.headerName]: csrf.token },
            data: { email, role: "member" },
        });
        expect(invitation.status(), await invitation.text()).toBe(200);
        const recipientId = await acceptEmailInvitation(member.request, await invitation.json());
        const created = await request.post("/api/notes", {
            headers: { "X-Workspace-Id": String(workspaceId), [csrf.headerName]: csrf.token },
            data: { content: "Original", visibility: "workspace" },
        });
        expect(created.status()).toBe(200);
        const original: unknown = await created.json();
        if (typeof original !== "object" || original === null || !("id" in original) || typeof original.id !== "number") throw new Error("Missing note id");
        await page.goto(`/activity/notes/${original.id}`);
        const expected = `Owner: [${label}](user:${recipientId}) please review`;
        const save = page.waitForResponse((response) => response.url().endsWith(`/api/notes/${original.id}`)
            && response.request().method() === "PUT" && savesContent(response, expected));
        const editor = await selectMention(page, label);
        await editor.pressSequentially("please review");
        await page.evaluate(() => window.dispatchEvent(new Event("beforeunload")));
        const response = await save;
        expect(response.status()).toBe(200);
        const note: unknown = await response.json();
        if (typeof note !== "object" || note === null || !("id" in note) || typeof note.id !== "number") throw new Error("Missing note id");
        expect(note).toMatchObject({ content: expected });
        const notifications = await member.request.get("/api/notifications?type=note.mention");
        expect(notifications.status()).toBe(200);
        expect(await notifications.json()).toMatchObject({ items: expect.arrayContaining([
            expect.objectContaining({ type: "note.mention", sourceId: note.id }),
        ]) });
    } finally {
        await authenticator.send("WebAuthn.disable");
        await authenticator.detach();
        await member.close();
        await owner.close();
    }
});
