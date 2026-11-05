import { randomUUID } from "node:crypto";
import { expect, test } from "@playwright/test";
import { message } from "./support/messages";
import { useLocale } from "./support/locale";

test.use({ storageState: { cookies: [], origins: [] } });

for (const locale of ["en", "ja"] as const) {
    test(`registration explains password rules and accepts symbols and spaces in ${locale} @mobile`, async ({ page }) => {
        await useLocale(page, locale);
        await page.goto("/auth/register");
        const password = page.getByLabel(message(locale, "auth", "AuthForm.labelPassword"), { exact: true });
        const submit = page.getByRole("button", { name: message(locale, "auth", "AuthRegister.submitLabel"), exact: true });
        await expect(page.locator("#register-password-hint")).toContainText(
            message(locale, "auth", "PasswordPolicy.specialCharacter"),
        );
        const suffix = randomUUID().replaceAll("-", "");
        await page.getByLabel(message(locale, "auth", "AuthForm.labelUsername"), { exact: true }).fill(`pw_${suffix}`);
        await page.getByLabel(message(locale, "auth", "AuthForm.labelEmail"), { exact: true }).fill(`pw_${suffix}@example.com`);
        await page.getByLabel(message(locale, "auth", "AuthForm.labelDisplayName"), { exact: true }).fill("Password Policy");
        await password.fill("Abcdefg1");
        await submit.click();
        await expect(page.locator("#register-password-error")).toHaveText(
            message(locale, "auth", "PasswordPolicy.specialCharacter"),
        );
        await expect(submit).toBeEnabled();
        await expect(page.locator("#register-password-hint")).toBeVisible();
        await password.fill(`Aa1#-_ ${suffix}`);
        const accepted = page.waitForResponse((response) => response.url().endsWith("/api/auth/register")
            && response.request().method() === "POST");
        await submit.click();
        expect((await accepted).status()).toBe(200);
        expect((await page.request.get("/api/auth/me")).status()).toBe(200);
    });
}
