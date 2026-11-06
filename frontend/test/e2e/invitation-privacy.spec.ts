import { randomUUID } from "node:crypto";
import { expect, test, type Locator, type Page } from "@playwright/test";

import { activeWorkspaceId, registerUser } from "./support/api";
import { invitationToken } from "./support/invitations";
import { useLocale } from "./support/locale";
import { message } from "./support/messages";

/** Sends the email form and retains only the uniformly shaped invitation response. */
async function invite(page: Page, section: Locator, email: string, copy: (key: string) => string): Promise<unknown> {
    await section.getByRole("textbox", { name: copy("emailLabel"), exact: true }).fill(email);
    const response = page.waitForResponse((candidate) => candidate.request().method() === "POST"
        && /\/api\/workspaces\/\d+\/invites$/.test(new URL(candidate.url()).pathname));
    await section.getByRole("button", { name: copy("sendInvite"), exact: true }).click();
    const saved = await response;
    expect(saved.status(), await saved.text()).toBe(200);
    const result: unknown = await saved.json();
    if (typeof result !== "object" || result === null || !("invite" in result)) throw new Error("Missing invitation");
    expect(Object.keys(result)).toEqual(["invite"]);
    expect(result).not.toHaveProperty("member");
    expect(result.invite).toMatchObject({ email, role: "member", status: "pending" });
    expect(result.invite).not.toHaveProperty("username");
    expect(result.invite).not.toHaveProperty("displayName");
    expect(result.invite).not.toHaveProperty("profilePictureUrl");
    await expect(page.getByText(copy("invitationCreated"), { exact: true }).last()).toBeVisible();
    return result;
}

for (const locale of ["en", "ja"] as const) {
    test(`email invitations withhold account identity until acceptance in ${locale}`, async ({ browser, baseURL }) => {
        test.setTimeout(180_000);
        const owner = await browser.newContext({ baseURL, storageState: { cookies: [], origins: [] } });
        const recipient = await browser.newContext({ baseURL, storageState: { cookies: [], origins: [] } });
        const marker = randomUUID().replaceAll("-", "").slice(0, 16);
        const password = `Invitation!${marker}Aa1`;
        const email = `privacy_member_${marker}@example.test`;
        const unknownEmail = `privacy_new_${marker}@example.test`;
        const label = `Private recipient ${marker}`;
        const page = await owner.newPage();
        const authenticator = await owner.newCDPSession(page);
        try {
            await registerUser(owner.request, {
                username: `privacy_owner_${marker}`, password, email: `privacy_owner_${marker}@example.test`,
                displayName: `Invitation owner ${marker}`,
            });
            const workspaceId = await activeWorkspaceId(owner.request);
            await registerUser(recipient.request, {
                username: `privacy_member_${marker}`, password, email, displayName: label,
            });
            await authenticator.send("WebAuthn.enable");
            await authenticator.send("WebAuthn.addVirtualAuthenticator", { options: {
                protocol: "ctap2", ctap2Version: "ctap2_1", transport: "internal",
                hasResidentKey: true, hasUserVerification: true, automaticPresenceSimulation: true, isUserVerified: true,
            } });
            await page.goto("/settings/personal/security");
            const security = (key: string) => message("en", "account", `AccountSecurity.${key}`);
            await page.getByRole("button", { name: security("add") }).first().click();
            const passwordDialog = page.getByRole("dialog", { name: security("passwordTitle") });
            await passwordDialog.getByLabel(security("passwordLabel")).fill(password);
            await passwordDialog.getByRole("button", { name: security("continue"), exact: true }).click();
            await expect(page.getByText(security("added"), { exact: true })).toBeVisible();
            await useLocale(owner, locale);
            await useLocale(recipient, locale);
            const copy = (key: string) => message(locale, "workspace", `WorkspaceMembers.${key}`);
            await page.goto("/settings/workspace/people#members");
            const section = page.locator("#members");
            const oneMember = copy("count").replace("{count}", "1");
            await expect(section.getByText(oneMember, { exact: true })).toBeVisible();

            await invite(page, section, unknownEmail, copy);
            await invite(page, section, email, copy);
            await expect(section.getByText(oneMember, { exact: true })).toBeVisible();
            await expect(section.getByText(label, { exact: true })).toHaveCount(0);
            await expect(section.locator("li").filter({ hasText: email })).toHaveCount(1);
            const pending = await owner.request.get(`/api/workspaces/${workspaceId}/invites`);
            expect(pending.status()).toBe(200);
            const invitations: unknown = await pending.json();
            expect(invitations).toEqual(expect.arrayContaining([
                expect.objectContaining({ email }), expect.objectContaining({ email: unknownEmail }),
            ]));
            expect(JSON.stringify(invitations)).not.toContain(label);
            expect(JSON.stringify(invitations)).not.toContain(`privacy_member_${marker}"`);

            const repeated = await invite(page, section, email, copy);
            await expect(section.locator("li").filter({ hasText: email })).toHaveCount(1);
            await page.reload();
            await expect(section.getByText(oneMember, { exact: true })).toBeVisible();
            await expect(section.getByText(label, { exact: true })).toHaveCount(0);
            const recipientPage = await recipient.newPage();
            await recipientPage.goto(`/invite#token=${encodeURIComponent(invitationToken(repeated))}`);
            const acceptButton = recipientPage.getByRole("button", {
                name: message(locale, "workspace", "InviteAccept.accept"), exact: true,
            });
            await expect(acceptButton).toBeVisible();
            const awaitingAcceptance = await owner.request.get(`/api/workspaces/${workspaceId}/members`);
            expect(awaitingAcceptance.status()).toBe(200);
            const awaitingRoster: unknown = await awaitingAcceptance.json();
            expect(awaitingRoster).toEqual([expect.objectContaining({ status: "active" })]);
            expect(JSON.stringify(awaitingRoster)).not.toContain(label);
            const exchangedInvitations = await owner.request.get(`/api/workspaces/${workspaceId}/invites`);
            expect(exchangedInvitations.status()).toBe(200);
            const awaitingInvitations: unknown = await exchangedInvitations.json();
            expect(awaitingInvitations).toEqual(expect.arrayContaining([expect.objectContaining({ email, status: "pending" })]));
            expect(JSON.stringify(awaitingInvitations)).not.toContain(label);
            expect(JSON.stringify(awaitingInvitations)).not.toContain('"username"');
            const accepted = recipientPage.waitForResponse((response) => response.request().method() === "POST"
                && new URL(response.url()).pathname === "/api/invites/accept");
            await acceptButton.click();
            expect((await accepted).status()).toBe(200);
            await expect(recipientPage).toHaveURL(/\/dashboard/);

            await page.reload();
            await expect(section.getByText(copy("count").replace("{count}", "2"), { exact: true })).toBeVisible();
            await expect(section.getByText(label, { exact: true })).toBeVisible();
            const unknownRow = section.locator("li").filter({ hasText: unknownEmail });
            await unknownRow.hover();
            await unknownRow.getByRole("button", { name: copy("inviteActions"), exact: true }).click();
            await page.getByRole("menuitem", { name: copy("revoke"), exact: true }).click();
            await expect(section.getByText(unknownEmail, { exact: true })).toHaveCount(0);
            await expect(section.getByText(copy("count").replace("{count}", "2"), { exact: true })).toBeVisible();
        } finally {
            await authenticator.send("WebAuthn.disable");
            await authenticator.detach();
            await recipient.close();
            await owner.close();
        }
    });
}
