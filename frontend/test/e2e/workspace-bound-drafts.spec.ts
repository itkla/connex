import { expect, test, type APIRequestContext, type BrowserContext, type Page } from "@playwright/test";

import { activeWorkspaceId, csrfBootstrap, registerUser } from "./support/api";
import { message } from "./support/messages";

type Account = { username: string; password: string; email: string; displayName: string };
type Csrf = Awaited<ReturnType<typeof csrfBootstrap>>;

function notesCopy(key: string): string {
    return message("en", "activity", key);
}

function isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === "object" && value !== null;
}

async function installVirtualAuthenticator(page: Page): Promise<() => Promise<void>> {
    const session = await page.context().newCDPSession(page);
    await session.send("WebAuthn.enable");
    try {
        const { authenticatorId } = await session.send("WebAuthn.addVirtualAuthenticator", {
            options: {
                protocol: "ctap2",
                ctap2Version: "ctap2_1",
                transport: "internal",
                hasResidentKey: true,
                hasUserVerification: true,
                automaticPresenceSimulation: true,
                isUserVerified: true,
            },
        });
        return async () => {
            if (page.isClosed()) return;
            await session.send("WebAuthn.removeVirtualAuthenticator", { authenticatorId });
            await session.send("WebAuthn.disable");
            await session.detach();
        };
    } catch (error) {
        await session.send("WebAuthn.disable");
        await session.detach();
        throw error;
    }
}

/**
 * Enrols a passkey, which is what marks the session's recent authentication: issuing an invite link
 * and removing a member both demand it, and a password login never provides it.
 */
async function enrolPasskey(page: Page, password: string): Promise<void> {
    await page.goto("/settings/personal/security");
    await page.getByRole("button", { name: message("en", "account", "AccountSecurity.add") }).first().click();
    const dialog = page.getByRole("dialog", { name: message("en", "account", "AccountSecurity.passwordTitle") });
    await dialog.getByLabel(message("en", "account", "AccountSecurity.passwordLabel")).fill(password);
    await dialog.getByRole("button", { name: message("en", "account", "AccountSecurity.continue"), exact: true }).click();
    await expect(page.getByText(message("en", "account", "AccountSecurity.added"), { exact: true })).toBeVisible();
}

function writeHeaders(workspaceId: number, csrf: Csrf): Record<string, string> {
    return { "X-Workspace-Id": String(workspaceId), [csrf.headerName]: csrf.token };
}

async function currentUserId(api: APIRequestContext): Promise<number> {
    const response = await api.get("/api/auth/me");
    expect(response.status()).toBe(200);
    const body: unknown = await response.json();
    if (!isRecord(body) || typeof body.id !== "number") throw new Error("The session has no user id");
    return body.id;
}

/** Joins `member` to the owner's workspace through a real invite link: exchange, preview, accept. */
async function joinThroughInviteLink(
    owner: BrowserContext,
    ownerWorkspaceId: number,
    member: BrowserContext,
): Promise<void> {
    const ownerCsrf = await csrfBootstrap(owner.request);
    const link = await owner.request.post(`/api/workspaces/${ownerWorkspaceId}/invite-links`, {
        headers: writeHeaders(ownerWorkspaceId, ownerCsrf),
        data: { role: "member", expiresInDays: 1, maxUses: 1 },
    });
    expect(link.status(), await link.text()).toBe(200);
    const created: unknown = await link.json();
    if (!isRecord(created) || typeof created.token !== "string") throw new Error("The invite link has no token");

    const memberCsrf = await csrfBootstrap(member.request);
    const exchange = await member.request.post("/api/invite-links/exchange", {
        headers: { [memberCsrf.headerName]: memberCsrf.token },
        data: { token: created.token },
        maxRedirects: 0,
    });
    expect(exchange.status(), await exchange.text()).toBe(303);
    const preview = await member.request.get("/api/invite-links");
    expect(preview.status(), await preview.text()).toBe(200);
    const flow: unknown = await preview.json();
    if (!isRecord(flow) || typeof flow.flowId !== "string") throw new Error("The invite preview has no flow");
    const accept = await member.request.post("/api/invite-links/accept", {
        headers: { [memberCsrf.headerName]: memberCsrf.token },
        data: { flowId: flow.flowId },
    });
    expect(accept.status(), await accept.text()).toBe(200);
}

/** Counts the caller's notes in one workspace whose title or body contains `marker`. */
async function notesMatching(api: APIRequestContext, workspaceId: number, marker: string): Promise<number> {
    const response = await api.get(`/api/notes/page?q=${encodeURIComponent(marker)}&size=50`, {
        headers: { "X-Workspace-Id": String(workspaceId) },
    });
    expect(response.status(), await response.text()).toBe(200);
    const page: unknown = await response.json();
    if (!isRecord(page) || typeof page.total !== "number") throw new Error("The notes page has no total");
    return page.total;
}

async function workspaceCookie(context: BrowserContext): Promise<string | undefined> {
    return (await context.cookies()).find((cookie) => cookie.name === "connex_workspace")?.value;
}

/** Chooses a workspace in the sidebar switcher, the explicit reselection a member makes. */
async function switchWorkspace(page: Page, name: string): Promise<void> {
    const switcher = page.getByRole("button", { name: "Switch workspace" });
    await switcher.click();
    await page.getByRole("menuitem").filter({ hasText: name }).click();
    await expect(page).toHaveURL(/\/dashboard$/);
    await expect(switcher).toContainText(name);
}

async function openNewNote(page: Page) {
    await page.getByRole("main").getByRole("button", { name: notesCopy("ActivityNotes.new"), exact: true }).first().click();
    const dialog = page.getByRole("dialog", { name: notesCopy("ActivityNotesDialog.titleCreate") });
    await expect(dialog).toBeVisible();
    return dialog;
}

/**
 * #1732: a draft composed while a workspace was selected must never be written into another
 * workspace. The first half lets the full-page note editor's first autosave settle, then switches
 * workspaces explicitly without a reload: the saved text must land in the workspace it was written
 * in. The unit lifecycle test pins the unmount-flush ordering itself, which a browser cannot time
 * deterministically. The second half revokes the membership server-side and lets a safe read heal the selection
 * cookie to the member's other workspace, the exact two-step path the backend cannot close on its own.
 */
test.describe("drafts stay in the workspace they were started in", () => {
    test("a draft from a revoked workspace is refused after the heal, and a new draft lands where it was reselected", async ({ browser }, testInfo) => {
        test.setTimeout(240_000);
        const baseURL = testInfo.project.use.baseURL;
        if (typeof baseURL !== "string") throw new Error("The E2E project requires a base URL");
        const runId = `wsd${Date.now().toString(36)}${testInfo.retry}`;
        const owner: Account = {
            username: `${runId}own`,
            password: `Owner!${runId}A1`,
            email: `${runId}own@example.com`,
            displayName: `Owner ${runId}`,
        };
        const member: Account = {
            username: `${runId}mem`,
            password: `Member!${runId}A1`,
            email: `${runId}mem@example.com`,
            displayName: `Member ${runId}`,
        };
        const revokedName = `${owner.displayName}'s Workspace`;
        const homeName = `${member.displayName}'s Workspace`;
        const contextOptions = {
            baseURL,
            locale: "en-US",
            timezoneId: "UTC",
            reducedMotion: "reduce" as const,
            storageState: { cookies: [], origins: [] },
        };
        const ownerContext = await browser.newContext(contextOptions);
        const memberContext = await browser.newContext(contextOptions);
        let removeVirtualAuthenticator: (() => Promise<void>) | null = null;
        try {
            await registerUser(ownerContext.request, owner);
            const revokedWorkspaceId = await activeWorkspaceId(ownerContext.request);
            const ownerPage = await ownerContext.newPage();
            removeVirtualAuthenticator = await installVirtualAuthenticator(ownerPage);
            await enrolPasskey(ownerPage, owner.password);

            await registerUser(memberContext.request, member);
            const homeWorkspaceId = await activeWorkspaceId(memberContext.request);
            const memberId = await currentUserId(memberContext.request);
            await joinThroughInviteLink(ownerContext, revokedWorkspaceId, memberContext);
            expect(await workspaceCookie(memberContext)).toBe(String(revokedWorkspaceId));

            const page = await memberContext.newPage();
            const switcher = page.getByRole("button", { name: "Switch workspace" });
            const flushedBody = `Switch flush ${runId}`;
            await page.goto("/activity/notes/new");
            await expect(switcher).toContainText(revokedName);
            await page.locator('.tiptap[contenteditable="true"]').fill(flushedBody);
            await expect(page).toHaveURL(/\/activity\/notes\/\d+$/);
            await switchWorkspace(page, homeName);
            await expect.poll(() => notesMatching(memberContext.request, revokedWorkspaceId, flushedBody)).toBe(1);
            expect(await notesMatching(memberContext.request, homeWorkspaceId, flushedBody)).toBe(0);

            await switchWorkspace(page, revokedName);
            await page.goto("/activity/notes");
            await expect(switcher).toContainText(revokedName);

            const draftTitle = `Revoked draft ${runId}`;
            const draftBody = `Pricing notes for the board ${runId}`;
            const draft = await openNewNote(page);
            await draft.getByLabel(notesCopy("ActivityNotesDialog.titleLabel")).fill(draftTitle);
            await draft.locator('.tiptap[contenteditable="true"]').fill(draftBody);

            const ownerCsrf = await csrfBootstrap(ownerContext.request);
            const removal = await ownerContext.request.delete(
                `/api/workspaces/${revokedWorkspaceId}/members/${memberId}`,
                { headers: writeHeaders(revokedWorkspaceId, ownerCsrf) },
            );
            expect(removal.status(), await removal.text()).toBe(204);

            const healStatus = await page.evaluate(async () => {
                const response = await fetch("/api/workspaces", { credentials: "same-origin", cache: "no-store" });
                return response.status;
            });
            expect(healStatus).toBe(200);
            await expect.poll(() => workspaceCookie(memberContext)).toBe(String(homeWorkspaceId));

            const submission = page.waitForResponse((response) =>
                new URL(response.url()).pathname === "/api/notes" && response.request().method() === "POST");
            await draft.getByRole("button", { name: notesCopy("ActivityNotesDialog.create"), exact: true }).click();
            const refused = await submission;
            expect(refused.request().headers()["x-workspace-id"]).toBe(String(revokedWorkspaceId));
            expect(refused.status()).toBe(403);

            const explanation = message("en", "errors", "ApiErrors.workspaceUnavailableNamed")
                .replace("{workspace}", revokedName);
            await expect(page.getByText(explanation).first()).toBeVisible();
            await expect(draft).toBeVisible();
            await expect(draft.getByLabel(notesCopy("ActivityNotesDialog.titleLabel"))).toHaveValue(draftTitle);
            await expect(draft.locator('.tiptap[contenteditable="true"]')).toContainText(draftBody);
            expect(await notesMatching(memberContext.request, homeWorkspaceId, runId)).toBe(0);

            page.on("dialog", (dialog) => {
                void dialog.accept();
            });
            await page.goto("/activity/notes");
            await expect(switcher).toContainText(homeName);
            const draftKeys = await page.evaluate(() =>
                Object.keys(window.sessionStorage).filter((key) => key.startsWith("connex:draft:")));
            expect(draftKeys).toContain(`connex:draft:${memberId}:${revokedWorkspaceId}:note:global`);
            expect(draftKeys.filter((key) => key.startsWith(`connex:draft:${memberId}:${homeWorkspaceId}:`))).toEqual([]);

            const reselectedTitle = `Reselected note ${runId}`;
            const reselected = await openNewNote(page);
            await reselected.getByLabel(notesCopy("ActivityNotesDialog.titleLabel")).fill(reselectedTitle);
            await reselected.locator('.tiptap[contenteditable="true"]').fill(`Follow-up plan ${runId}`);
            const accepted = page.waitForResponse((response) =>
                new URL(response.url()).pathname === "/api/notes" && response.request().method() === "POST");
            await reselected.getByRole("button", { name: notesCopy("ActivityNotesDialog.create"), exact: true }).click();
            const created = await accepted;
            expect(created.request().headers()["x-workspace-id"]).toBe(String(homeWorkspaceId));
            expect(created.status(), await created.text()).toBe(200);
            await expect(page.getByText(notesCopy("ActivityNotesDialog.toastCreated")).first()).toBeVisible();

            expect(await notesMatching(memberContext.request, homeWorkspaceId, reselectedTitle)).toBe(1);
            expect(await notesMatching(memberContext.request, homeWorkspaceId, draftTitle)).toBe(0);
            await expect(page.getByText(draftBody)).toHaveCount(0);
        } finally {
            if (removeVirtualAuthenticator) await removeVirtualAuthenticator();
            await memberContext.close();
            await ownerContext.close();
        }
    });
});
