import { randomUUID } from "node:crypto";
import { expect, test } from "@playwright/test";
import { csrfBootstrap, registerUser } from "./support/api";
import { message } from "./support/messages";
import { useLocale } from "./support/locale";

const entryRoutes = [
    { path: "/auth/login", namespace: "auth", heading: "AuthLogin.title" },
    { path: "/auth/register", namespace: "auth", heading: "AuthRegister.title" },
    { path: "/auth/forgot-password", namespace: "auth", heading: "AuthForgotPassword.title" },
    { path: "/auth/reset-password", namespace: "auth", heading: "AuthResetPassword.invalidTitle" },
    { path: "/auth/confirm-email", namespace: "auth", heading: "AuthConfirmEmail.invalidTitle" },
    { path: "/auth/verify-email", namespace: "auth", heading: "AuthVerifyEmail.invalidTitle" },
    { path: "/auth/confirm-passkey", namespace: "auth", heading: "AuthConfirmPasskey.invalidTitle" },
    { path: "/unsubscribe", namespace: "unsubscribe", heading: "Unsubscribe.invalidTitle" },
];

test.describe("public entry in a Japanese browser", () => {
    test.use({ locale: "ja-JP", storageState: { cookies: [], origins: [] } });

    for (const entry of entryRoutes) {
        test(`${entry.path} uses Japanese without a locale cookie @mobile`, async ({ page }) => {
            const response = await page.goto(entry.path);

            expect(response?.status()).toBe(200);
            expect(await response?.text()).toContain('<html lang="ja"');
            await expect(page.getByRole("heading", {
                name: message("ja", entry.namespace, entry.heading),
                exact: true,
            })).toBeVisible();
            expect((await page.context().cookies()).some(({ name }) => name === "NEXT_LOCALE")).toBe(false);
        });
    }

    for (const path of ["/invite", "/invite-link"]) {
        test(`${path} keeps Japanese when a signed-out visitor is sent to sign in @mobile`, async ({ page }) => {
            await page.goto(path);

            await expect(page).toHaveURL(`/auth/login?redirect=${encodeURIComponent(path)}`);
            await expect(page.locator("html")).toHaveAttribute("lang", "ja");
            await expect(page.getByRole("heading", {
                name: message("ja", "auth", "AuthLogin.title"),
                exact: true,
            })).toBeVisible();
        });
    }

    test("an explicit English cookie wins across sign-in and unsubscribe entry @mobile", async ({ page }) => {
        await useLocale(page, "en");

        for (const entry of entryRoutes.filter(({ path }) => path === "/auth/login" || path === "/unsubscribe")) {
            await page.goto(entry.path);

            await expect(page.locator("html")).toHaveAttribute("lang", "en");
            await expect(page.getByRole("heading", {
                name: message("en", entry.namespace, entry.heading),
                exact: true,
            })).toBeVisible();
        }
    });

    test("a saved English profile wins on unsubscribe and invitation pages without a locale cookie @mobile", async ({ page, context }) => {
        const unique = randomUUID();
        await registerUser(context.request, {
            username: `locale-${unique}`,
            password: `Locale-${randomUUID()}!`,
            email: `locale-${unique}@example.test`,
            displayName: "Locale preference",
        });
        const csrf = await csrfBootstrap(context.request);
        const response = await context.request.patch("/api/users/me/locale", {
            headers: { [csrf.headerName]: csrf.token },
            data: { locale: "en" },
        });
        expect(response.ok()).toBe(true);
        await context.clearCookies({ name: "NEXT_LOCALE" });

        for (const entry of [
            { path: "/unsubscribe", namespace: "unsubscribe", heading: "Unsubscribe.invalidTitle" },
            { path: "/invite", namespace: "auth", heading: "InviteAccept.invalidTitle" },
            { path: "/invite-link", namespace: "auth", heading: "InviteLinkAccept.invalidTitle" },
        ]) {
            const documentResponse = await page.goto(entry.path);

            expect(documentResponse?.status()).toBe(200);
            expect(await documentResponse?.text()).toContain('<html lang="en"');
            await expect(page.getByRole("heading", {
                name: message("en", entry.namespace, entry.heading),
                exact: true,
            })).toBeVisible();
            expect((await context.cookies()).some(({ name }) => name === "NEXT_LOCALE")).toBe(false);
        }
    });
});

test.describe("public entry with an unsupported browser language", () => {
    test.use({ locale: "fr-FR", storageState: { cookies: [], origins: [] } });

    test("falls back to English and honors a later Japanese cookie @mobile", async ({ page }) => {
        await page.goto("/auth/login");
        await expect(page.getByRole("heading", {
            name: message("en", "auth", "AuthLogin.title"),
            exact: true,
        })).toBeVisible();
        await expect(page.locator("html")).toHaveAttribute("lang", "en");

        await useLocale(page, "ja");
        await page.reload();

        await expect(page.getByRole("heading", {
            name: message("ja", "auth", "AuthLogin.title"),
            exact: true,
        })).toBeVisible();
        await expect(page.locator("html")).toHaveAttribute("lang", "ja");
    });
});
