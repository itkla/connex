import { expect, test } from "@playwright/test";
import { activeWorkspaceId, registerUser } from "./support/api";
import { message } from "./support/messages";

for (const width of [320, 720]) {
    test(`closed drawer has no keyboard stops at ${width} CSS pixels @mobile`, async ({ page }) => {
        await page.setViewportSize({ width, height: 800 });
        await page.goto("/dashboard");
        const sidebar = page.locator("#app-sidebar");
        await expect(sidebar).toHaveAttribute("inert", "");
        const skip = page.getByRole("link", { name: message("en", "common", "CommonContentShell.skipToContent") });
        await page.keyboard.press("Tab");
        await expect(skip).toBeFocused();
        await expect(skip).toBeInViewport();
        await page.keyboard.press("Enter");
        await expect(page.getByRole("main")).toBeFocused();
        for (let index = 0; index < 50; index += 1) {
            await page.keyboard.press("Tab");
            expect(await sidebar.evaluate((element) => element.contains(document.activeElement))).toBe(false);
        }
        const trigger = page.getByRole("button", { name: message("en", "common", "CommonContentShell.showSidebar") });
        await trigger.focus();
        await page.keyboard.press("Enter");
        await expect(sidebar).not.toHaveAttribute("inert", "");
        await expect(sidebar).toBeFocused();
        await page.keyboard.press("Tab");
        expect(await sidebar.evaluate((element) => element.contains(document.activeElement))).toBe(true);
        await page.keyboard.press("Escape");
        await expect(sidebar).toHaveAttribute("inert", "");
        await expect(trigger).toBeFocused();
        await page.setViewportSize({ width: 1280, height: 800 });
        await expect(sidebar).not.toHaveAttribute("inert", "");
        await expect(sidebar.getByRole("link", { name: message("en", "common", "CommonSidebar.navDashboard"), exact: true })).toBeVisible();
    });
}

test("passkey row actions are visible and usable without hover @mobile-only", async ({ browser }, testInfo) => {
    const runId = `touch${Date.now().toString(36)}${testInfo.retry}`;
    const password = `Touch!${runId}A1`;
    const context = await browser.newContext({
        storageState: { cookies: [], origins: [] },
        reducedMotion: "reduce",
    });
    try {
        await registerUser(context.request, { username: runId, password, email: `${runId}@example.com` });
        await activeWorkspaceId(context.request);
        const page = await context.newPage();
        const cdp = await context.newCDPSession(page);
        await cdp.send("WebAuthn.enable");
        await cdp.send("WebAuthn.addVirtualAuthenticator", {
            options: {
                protocol: "ctap2",
                ctap2Version: "ctap2_1",
                transport: "internal",
                hasResidentKey: true,
                hasUserVerification: true,
                isUserVerified: true,
                automaticPresenceSimulation: true,
            },
        });
        await page.goto("/settings/personal/security");
        expect(await page.evaluate(() => matchMedia("(hover: none)").matches)).toBe(true);
        await page.getByRole("button", { name: message("en", "account", "AccountSecurity.add"), exact: true }).first().tap();
        const passwordDialog = page.getByRole("dialog", { name: message("en", "account", "AccountSecurity.passwordTitle") });
        await passwordDialog.getByLabel(message("en", "account", "AccountSecurity.passwordLabel")).fill(password);
        await passwordDialog.getByRole("button", { name: message("en", "account", "AccountSecurity.continue"), exact: true }).tap();
        const actions = page.getByRole("main").locator('li button[aria-haspopup="menu"]').first();
        for (const width of [412, 1024]) {
            await page.setViewportSize({ width, height: 900 });
            await expect(actions).toBeVisible();
            await expect(actions).toHaveCSS("opacity", "1");
        }
        await actions.tap();
        const rename = page.getByRole("menuitem", { name: message("en", "account", "AccountSecurity.rename"), exact: true });
        await expect(rename).toBeVisible();
        await rename.tap();
        await expect(page.getByRole("dialog", { name: message("en", "account", "AccountSecurity.renameTitle") })).toBeVisible();
    } finally {
        await context.close();
    }
});
