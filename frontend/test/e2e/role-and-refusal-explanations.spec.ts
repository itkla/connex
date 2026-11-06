import { randomUUID } from "node:crypto";
import { expect, test } from "@playwright/test";

import { registerUser } from "./support/api";
import { useLocale } from "./support/locale";
import { message } from "./support/messages";

for (const locale of ["en", "ja"] as const) {
    test(`role actions and safety refusals explain the next step in ${locale} @mobile`, async ({ browser, baseURL }) => {
        const context = await browser.newContext({ baseURL, storageState: { cookies: [], origins: [] } });
        try {
            const marker = randomUUID().replaceAll("-", "").slice(0, 16);
            await registerUser(context.request, {
                username: `roles_${marker}`, password: `Roles!${marker}Aa1`,
                email: `roles_${marker}@example.test`, displayName: `Roles ${marker}`,
            });
            await useLocale(context, locale);
            const page = await context.newPage();
            const copy = (key: string) => message(locale, "workspace", `WorkspaceRoles.${key}`);
            await page.goto("/settings/workspace/people#roles");
            const roles = page.locator("#roles");
            const member = roles.getByRole("listitem").filter({
                has: page.getByText(copy("builtInRoles.member.description"), { exact: true }),
            });
            await expect(member).toHaveCount(1);
            const memberName = member.locator(':scope > div > div > span:not([data-slot="badge"])');
            await expect(memberName).toHaveText(copy("builtInRoles.member.name"));
            await expect(memberName).toBeVisible();
            await member.locator("summary").click();
            await expect(member.getByText(`${copy("permissionLabels.ROLE_MANAGE.action")} · ${copy("notAllowed")}`, { exact: true })).toBeVisible();
            await expect(member.getByText(copy("permissionLabels.AI_SESSION_ADMIN.description"), { exact: true })).toBeVisible();
            await roles.getByRole("button", { name: copy("newRole"), exact: true }).click();
            const dialog = page.getByRole("dialog", { name: copy("createTitle"), exact: true });
            const permission = dialog.getByRole("checkbox", { name: copy("permissionLabels.RULE_MANAGE.action"), exact: true });
            await permission.check();
            await expect(permission).toBeChecked();
            await expect(dialog.getByText(copy("permissionLabels.RULE_MANAGE.description"), { exact: true })).toBeVisible();
            await dialog.getByRole("button", { name: copy("cancel"), exact: true }).click();

            await page.route("**/api/workspaces/*/invites", async (route) => {
                if (route.request().method() !== "POST") return route.continue();
                await route.fulfill({ status: 403, json: {
                    code: "INVITE_EMAIL_DOMAIN_NOT_ALLOWED", message: "private backend detail",
                } });
            });
            const members = page.locator("#members");
            const invite = (key: string) => message(locale, "workspace", `WorkspaceMembers.${key}`);
            const email = `invite_${marker}@example.test`;
            await members.getByRole("textbox", { name: invite("emailLabel"), exact: true }).fill(email);
            await members.getByRole("button", { name: invite("sendInvite"), exact: true }).click();
            await expect(page.getByText(message(locale, "errors", "ApiErrors.inviteEmailDomainNotAllowed"), { exact: true })).toBeVisible();
            await expect(members.getByRole("textbox", { name: invite("emailLabel"), exact: true })).toHaveValue(email);
            await expect(page.getByText("private backend detail", { exact: true })).toHaveCount(0);

            await page.route("**/api/auth/webauthn/credentials", (route) => route.fulfill({ json: [{
                credentialId: "protected-passkey", label: "Protected passkey", transports: ["internal"],
                createdAt: "2026-09-01T00:00:00Z",
            }] }));
            let passkeyCode = "LAST_PASSKEY_REQUIRED";
            await page.route("**/api/auth/webauthn/credentials/protected-passkey", (route) => route.fulfill({
                status: 400, json: { code: passkeyCode, message: "private backend detail" },
            }));
            await page.goto("/settings/personal/security");
            const security = (key: string) => message(locale, "account", `AccountSecurity.${key}`);
            await page.getByRole("button", { name: security("passkeyActions").replace("{label}", "Protected passkey"), exact: true }).click();
            await page.getByRole("menuitem", { name: security("remove"), exact: true }).click();
            const removal = page.getByRole("dialog");
            const confirm = removal.getByRole("button", { name: message(locale, "records", "RecordsDeleteDialog.delete"), exact: true });
            await confirm.click();
            await expect(page.getByText(message(locale, "errors", "ApiErrors.lastPasskeyRequired"), { exact: true })).toBeVisible();
            await expect(removal).toBeVisible();
            passkeyCode = "LAST_SUFFICIENT_PASSKEY_REQUIRED";
            await confirm.click();
            await expect(page.getByText(message(locale, "errors", "ApiErrors.lastSufficientPasskeyRequired"), { exact: true })).toBeVisible();
            await expect(removal).toContainText("Protected passkey");
        } finally {
            await context.close();
        }
    });
}
