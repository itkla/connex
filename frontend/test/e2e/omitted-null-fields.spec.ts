import { randomUUID } from "node:crypto";
import { expect, test } from "@playwright/test";

import { activeWorkspaceId, csrfBootstrap, registerUser, seeder } from "./support/api";
import { message } from "./support/messages";

test.use({ storageState: { cookies: [], origins: [] } });

test("omitted API fields preserve audit diagnostics and member disqualification", async ({ browser, baseURL }, testInfo) => {
    test.setTimeout(180_000);
    const owner = await browser.newContext({ baseURL, storageState: { cookies: [], origins: [] } });
    const member = await browser.newContext({ baseURL, storageState: { cookies: [], origins: [] } });
    const runId = randomUUID().replaceAll("-", "");
    const password = `Regression!${runId}Aa1`;
    try {
        await registerUser(owner.request, {
            username: `null_owner_${runId}`, password, email: `null_owner_${runId}@example.com`,
        });
        const workspaceId = await activeWorkspaceId(owner.request);
        const page = await owner.newPage();
        const errors: string[] = [];
        page.on("pageerror", (error) => errors.push(error.message));
        page.on("console", (entry) => {
            if (entry.type() === "error") errors.push(entry.text());
        });
        const cdp = await owner.newCDPSession(page);
        await cdp.send("WebAuthn.enable");
        await cdp.send("WebAuthn.addVirtualAuthenticator", { options: {
            protocol: "ctap2", ctap2Version: "ctap2_1", transport: "internal",
            hasResidentKey: true, hasUserVerification: true, isUserVerified: true,
            automaticPresenceSimulation: true,
        } });
        await page.goto("/settings/personal/security");
        await page.getByRole("button", { name: message("en", "account", "AccountSecurity.add") }).first().click();
        const passwordDialog = page.getByRole("dialog", {
            name: message("en", "account", "AccountSecurity.passwordTitle"),
        });
        await passwordDialog.getByLabel(message("en", "account", "AccountSecurity.passwordLabel")).fill(password);
        await passwordDialog.getByRole("button", {
            name: message("en", "account", "AccountSecurity.continue"), exact: true,
        }).click();
        await expect(page.getByText(message("en", "account", "AccountSecurity.added"), { exact: true })).toBeVisible();

        const csrf = await csrfBootstrap(owner.request);
        const seed = seeder(owner.request, workspaceId, csrf);
        await seed.post(`/api/workspaces/${workspaceId}/invite-links`, { role: "member" });
        const audit = await owner.request.get("/api/audit");
        expect(audit.ok()).toBe(true);
        const entries: unknown = await audit.json();
        if (!Array.isArray(entries)) throw new Error("Expected audit entries");
        const inviteEntry: unknown = entries.find((entry: unknown) => typeof entry === "object" && entry !== null
            && "action" in entry && entry.action === "workspace.invite_link.create");
        expect(inviteEntry).toBeDefined();
        expect(inviteEntry).not.toHaveProperty("targetLabel");

        await page.goto("/settings/workspace/audit-diagnostics");
        await expect(page.getByRole("heading", {
            name: message("en", "settings", "SettingsNav.groupAuditDiagnostics"), exact: true,
        })).toBeVisible();
        await expect(page.getByText("Created an invite link granting member", { exact: true })).toBeVisible();
        await expect(page.locator("#diagnostics")).toBeVisible();
        await expect(page.locator("#diagnostics").getByText(
            message("en", "workspace", "TenantDiagnostics.buildIdentityTitle"), { exact: true },
        )).toBeVisible();
        await page.reload();
        await expect(page.getByText("Created an invite link granting member", { exact: true })).toBeVisible();
        await expect(page.locator("#diagnostics")).toBeVisible();

        const reasons = await owner.request.get("/api/disqualification-reasons?includeArchived=true");
        expect(reasons.ok()).toBe(true);
        const catalog: unknown = await reasons.json();
        if (!Array.isArray(catalog)) throw new Error("Expected disqualification reasons");
        expect(catalog.length).toBeGreaterThan(0);
        for (const reason of catalog) expect(reason).not.toHaveProperty("archivedAt");
        await page.goto("/settings/workspace/crm");
        await expect(page.getByText(message("en", "contacts", "ContactLifecycle.reason.NO_BUDGET"), { exact: true })).toBeVisible();
        await expect(page.locator("#show-archived-reasons")).toHaveCount(0);

        await registerUser(member.request, {
            username: `null_member_${runId}`, password, email: `null_member_${runId}@example.com`,
        });
        await seed.post(`/api/workspaces/${workspaceId}/members`, {
            email: `null_member_${runId}@example.com`, role: "member",
        });
        const memberCsrf = await csrfBootstrap(member.request);
        const accepted = await member.request.post(`/api/workspaces/${workspaceId}/accept`, {
            headers: { [memberCsrf.headerName]: memberCsrf.token },
        });
        expect(accepted.ok()).toBe(true);
        const memberSeed = seeder(member.request, workspaceId, await csrfBootstrap(member.request));
        const contact = await memberSeed.post("/api/persons", { name: `Lifecycle regression ${runId}` });
        if (typeof contact.id !== "number") throw new Error("Expected contact id");
        const memberPage = await member.newPage();
        memberPage.on("pageerror", (error) => errors.push(error.message));
        memberPage.on("console", (entry) => {
            if (entry.type() === "error") errors.push(entry.text());
        });
        await memberPage.goto(`/records/contacts/${contact.id}`);
        await memberPage.getByRole("button", {
            name: message("en", "contacts", "ContactLifecycle.startAction"), exact: true,
        }).click();
        const dialog = memberPage.getByRole("dialog", { name: message("en", "contacts", "ContactLifecycle.dialogTitle") });
        await dialog.getByLabel(message("en", "contacts", "ContactLifecycle.stageLabel"), { exact: true }).click();
        await memberPage.getByRole("option", { name: message("en", "contacts", "ContactLifecycle.stage.NEW"), exact: true }).click();
        await dialog.getByRole("button", { name: message("en", "contacts", "ContactLifecycle.save"), exact: true }).click();
        await expect(dialog).toBeHidden();
        await memberPage.getByRole("button", {
            name: message("en", "contacts", "ContactLifecycle.changeAction"), exact: true,
        }).click();
        await dialog.getByLabel(message("en", "contacts", "ContactLifecycle.stageLabel"), { exact: true }).click();
        await memberPage.getByRole("option", { name: message("en", "contacts", "ContactLifecycle.stage.DISQUALIFIED"), exact: true }).click();
        await dialog.getByLabel(message("en", "contacts", "ContactLifecycle.reasonLabel"), { exact: true }).click();
        await memberPage.getByRole("option", { name: message("en", "contacts", "ContactLifecycle.reason.NO_BUDGET"), exact: true }).click();
        await dialog.getByRole("button", { name: message("en", "contacts", "ContactLifecycle.save"), exact: true }).click();
        await expect(dialog).toBeHidden();
        await memberPage.reload();
        const lifecycleResponse = await member.request.get(`/api/persons/${contact.id}/lifecycle`);
        expect(lifecycleResponse.ok()).toBe(true);
        expect(await lifecycleResponse.json()).toMatchObject({ stage: "DISQUALIFIED", disqualifiedReason: "NO_BUDGET" });
        await expect(memberPage.getByText(message("en", "contacts", "ContactLifecycle.stage.DISQUALIFIED"), { exact: true }).first()).toBeVisible();
        if (errors.length > 0) {
            await testInfo.attach("browser-diagnostics", {
                body: JSON.stringify(errors, null, 2),
                contentType: "application/json",
            });
        }
    } finally {
        await owner.close();
        await member.close();
    }
});
