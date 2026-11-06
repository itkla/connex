import { randomUUID } from "node:crypto";
import { expect, test } from "@playwright/test";

import { activeWorkspaceId, csrfBootstrap, registerUser } from "./support/api";
import { useLocale } from "./support/locale";
import { message } from "./support/messages";

for (const locale of ["en", "ja"] as const) {
    test(`invitation registration, copy recovery and member removal in ${locale}`, async ({ browser, baseURL }) => {
        test.setTimeout(180_000);
        const owner = await browser.newContext({ baseURL, storageState: { cookies: [], origins: [] } });
        const recipient = await browser.newContext({ baseURL, storageState: { cookies: [], origins: [] } });
        const marker = randomUUID().replaceAll("-", "").slice(0, 16);
        const password = `MemberFlow!${marker}Aa1`;
        const page = await owner.newPage();
        const authenticator = await owner.newCDPSession(page);
        const copy = (key: string) => message(locale, "workspace", `WorkspaceMembers.${key}`);
        try {
            await registerUser(owner.request, {
                username: `flow_owner_${marker}`, password, email: `flow_owner_${marker}@example.test`,
                displayName: `Flow owner ${marker}`,
            });
            const workspaceId = await activeWorkspaceId(owner.request);
            await authenticator.send("WebAuthn.enable");
            await authenticator.send("WebAuthn.addVirtualAuthenticator", { options: {
                protocol: "ctap2", ctap2Version: "ctap2_1", transport: "internal", hasResidentKey: true,
                hasUserVerification: true, automaticPresenceSimulation: true, isUserVerified: true,
            } });
            await page.goto("/settings/personal/security");
            const security = (key: string) => message("en", "account", `AccountSecurity.${key}`);
            await page.getByRole("button", { name: security("add") }).first().click();
            const passwordDialog = page.getByRole("dialog", { name: security("passwordTitle") });
            await passwordDialog.getByLabel(security("passwordLabel")).fill(password);
            await passwordDialog.getByRole("button", { name: security("continue"), exact: true }).click();
            await expect(page.getByText(security("added"), { exact: true })).toBeVisible();
            const csrf = await csrfBootstrap(owner.request);
            const headers = { [csrf.headerName]: csrf.token };
            const created = await owner.request.post(`/api/workspaces/${workspaceId}/invite-links`, {
                headers, data: { role: "member", maxUses: 5 },
            });
            expect(created.status(), await created.text()).toBe(200);
            const link: unknown = await created.json();
            if (typeof link !== "object" || link === null || !("token" in link) || typeof link.token !== "string"
                || !("id" in link) || typeof link.id !== "number") throw new Error("Missing shareable invitation");

            await useLocale(recipient, locale);
            const join = await recipient.newPage();
            await join.goto(`/invite-link#token=${encodeURIComponent(link.token)}`);
            await expect(join).toHaveURL(/\/auth\/login\?redirect=%2Finvite-link/);
            await join.getByRole("link", { name: message(locale, "auth", "AuthLogin.altLabel"), exact: true }).click();
            await expect(join).toHaveURL(/\/auth\/register\?redirect=%2Finvite-link/);
            await join.locator("#register-username").fill(`flow_member_${marker}`);
            await join.locator("#register-email").fill(`flow_member_${marker}@example.test`);
            await join.locator("#register-displayName").fill(`Flow teammate ${marker}`);
            await join.locator("#register-password").fill(password);
            await join.locator('button[type="submit"]').click();
            await expect(join).toHaveURL(/\/invite-link$/);
            const beforeAcceptance = await recipient.request.get("/api/workspaces");
            expect(beforeAcceptance.status()).toBe(200);
            expect(await beforeAcceptance.json()).toMatchObject({ workspaces: [] });
            await join.getByRole("button", { name: message(locale, "workspace", "InviteLinkAccept.accept"), exact: true }).click();
            await expect(join).toHaveURL(/\/dashboard/);
            const joined = await recipient.request.get("/api/workspaces");
            expect(await joined.json()).toMatchObject({ workspaces: [expect.objectContaining({ id: workspaceId })] });

            await useLocale(owner, locale);
            await page.goto("/settings/workspace/people#members");
            const section = page.locator("#members");
            const member = section.locator("li").filter({ hasText: `Flow teammate ${marker}` });
            await member.hover();
            await member.getByRole("button", { name: copy("memberActions"), exact: true }).click();
            await page.getByRole("menuitem", { name: copy("remove"), exact: true }).click();
            let removal = page.getByRole("dialog");
            await expect(removal.getByText(copy("removeOwnershipConsequences"), { exact: true })).toBeVisible();
            await expect(removal.getByText(copy("removeRejoinConsequences"), { exact: true })).toBeVisible();
            await removal.getByRole("button", { name: copy("reviewInvitations"), exact: true }).click();
            await expect(section.getByRole("tab", { name: copy("inviteTabLink"), exact: true })).toHaveAttribute("aria-selected", "true");
            await expect(section.getByText(copy("revokeLinkConsequences"), { exact: true })).toBeVisible();
            const stillActive = await owner.request.get(`/api/workspaces/${workspaceId}/invite-links`);
            expect(await stillActive.json()).toEqual([expect.objectContaining({ id: link.id })]);
            await member.hover();
            await member.getByRole("button", { name: copy("memberActions"), exact: true }).click();
            await page.getByRole("menuitem", { name: copy("remove"), exact: true }).click();
            removal = page.getByRole("dialog");
            await removal.getByRole("button", { name: copy("remove"), exact: true }).click();
            await expect(page.getByText(copy("removedDescription"), { exact: true })).toBeVisible();
            await expect(member).toHaveCount(0);
            const unchanged = await owner.request.get(`/api/workspaces/${workspaceId}/invite-links`);
            expect(await unchanged.json()).toEqual([expect.objectContaining({ id: link.id })]);
            await section.getByRole("button", { name: copy("linkActions"), exact: true }).click();
            await page.getByRole("menuitem", { name: copy("revoke"), exact: true }).click();
            await expect(page.getByText(copy("linkRevoked"), { exact: true })).toBeVisible();

            await section.getByRole("tab", { name: copy("inviteTabEmail"), exact: true }).click();
            await page.evaluate(() => Object.defineProperty(navigator, "clipboard", {
                configurable: true, value: { writeText: () => Promise.reject(new Error("Clipboard unavailable")) },
            }));
            await section.getByRole("textbox", { name: copy("emailLabel"), exact: true }).fill(`flow_invited_${marker}@example.test`);
            const emailResponse = page.waitForResponse((response) => response.request().method() === "POST"
                && /\/api\/workspaces\/\d+\/invites$/.test(new URL(response.url()).pathname));
            await section.getByRole("button", { name: copy("sendInvite"), exact: true }).click();
            const emailResult: unknown = await (await emailResponse).json();
            if (typeof emailResult !== "object" || emailResult === null || !("emailDelivery" in emailResult)) {
                throw new Error("Missing mail submission outcome");
            }
            const outcomeKey = emailResult.emailDelivery === "ACCEPTED" ? "emailAccepted"
                : emailResult.emailDelivery === "SKIPPED" ? "emailSkipped"
                    : emailResult.emailDelivery === "FAILED" ? "emailFailed" : "emailUnconfirmed";
            await expect(section.getByRole("status")).toHaveText(copy(outcomeKey));
            const recovery = section.getByRole("region", { name: copy("copyFailed"), exact: true });
            await expect(recovery.getByRole("textbox", { name: copy("inviteUrl"), exact: true })).toHaveValue(/\/invite#token=/);
            await expect(recovery.getByText(copy("copyFailedDescription"), { exact: true })).toBeVisible();
            await expect(page.getByText(copy("linkCopied"), { exact: true })).toHaveCount(0);
            await recovery.getByRole("button", { name: copy("close"), exact: true }).first().click();
            await page.reload();
            const pending = section.locator("li").filter({ hasText: `flow_invited_${marker}@example.test` });
            await pending.hover();
            await pending.getByRole("button", { name: copy("inviteActions"), exact: true }).click();
            await expect(page.getByRole("menuitem", { name: copy("replaceInvite"), exact: true })).toBeVisible();
        } finally {
            await authenticator.send("WebAuthn.disable");
            await authenticator.detach();
            await recipient.close();
            await owner.close();
        }
    });
}

for (const example of [
    { name: "encoded slashes", path: "/%2f%2fhost" },
    { name: "encoded backslash", path: "/%5chost" },
    { name: "absolute destination", path: "https://connex.invalid/invite" },
]) {
    test(`authentication switches discard ${example.name}`, async ({ browser, baseURL }) => {
        const context = await browser.newContext({ baseURL, storageState: { cookies: [], origins: [] } });
        try {
            await useLocale(context, "en");
            const page = await context.newPage();
            await page.goto(`/auth/register?${new URLSearchParams({ redirect: example.path })}`);
            const signIn = page.getByRole("link", { name: message("en", "auth", "AuthRegister.altLabel"), exact: true });
            await expect(signIn).toHaveAttribute("href", "/auth/login");
            await signIn.click();
            await expect(page).toHaveURL(/\/auth\/login$/);
            await page.goto(`/auth/login?${new URLSearchParams({ redirect: example.path })}`);
            const register = page.getByRole("link", { name: message("en", "auth", "AuthLogin.altLabel"), exact: true });
            await expect(register).toHaveAttribute("href", "/auth/register");
        } finally {
            await context.close();
        }
    });
}
