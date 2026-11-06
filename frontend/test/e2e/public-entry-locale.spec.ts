import { randomUUID } from "node:crypto";
import { expect, test } from "@playwright/test";
import { csrfBootstrap, registerUser } from "./support/api";
import { message } from "./support/messages";
import { useLocale as setBrowserLocale } from "./support/locale";

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
            const unsubscribeRequest = entry.path === "/unsubscribe"
                ? page.waitForRequest((request) => request.url().endsWith("/api/delivery/unsubscribe") && request.method() === "GET")
                : null;
            const response = await page.goto(entry.path);

            expect(response?.status()).toBe(200);
            expect(await response?.text()).toContain('<html lang="ja"');
            await expect(page.getByRole("heading", {
                name: message("ja", entry.namespace, entry.heading),
                exact: true,
            })).toBeVisible();
            if (unsubscribeRequest) expect((await unsubscribeRequest).headers()["accept-language"]).toBe("ja");
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
        await setBrowserLocale(page, "en");

        for (const entry of entryRoutes.filter(({ path }) => path === "/auth/login" || path === "/unsubscribe")) {
            const unsubscribeRequest = entry.path === "/unsubscribe"
                ? page.waitForRequest((request) => request.url().endsWith("/api/delivery/unsubscribe") && request.method() === "GET")
                : null;
            await page.goto(entry.path);

            await expect(page.locator("html")).toHaveAttribute("lang", "en");
            await expect(page.getByRole("heading", {
                name: message("en", entry.namespace, entry.heading),
                exact: true,
            })).toBeVisible();
            if (unsubscribeRequest) expect((await unsubscribeRequest).headers()["accept-language"]).toBe("en");
        }
    });

    test("profile and cookie choices keep UI and API languages aligned on public pages @mobile", async ({ page, context }) => {
        test.setTimeout(120_000);
        const unique = randomUUID();
        await registerUser(context.request, {
            username: `locale-${unique}`,
            password: `Locale-${randomUUID()}!`,
            email: `locale-${unique}@example.test`,
            displayName: "Locale preference",
        });
        const csrf = await csrfBootstrap(context.request);
        for (const choice of [
            { profile: "en", cookie: null, rendered: "en" },
            { profile: "ja", cookie: null, rendered: "ja" },
            { profile: "ja", cookie: "en", rendered: "en" },
        ] as const) {
            const response = await context.request.patch("/api/users/me/locale", {
                headers: { [csrf.headerName]: csrf.token },
                data: { locale: choice.profile },
            });
            expect(response.ok()).toBe(true);
            await context.clearCookies({ name: "NEXT_LOCALE" });
            if (choice.cookie) await setBrowserLocale(context, choice.cookie);

            for (const entry of [
                { path: "/unsubscribe", api: "/api/delivery/unsubscribe", namespace: "unsubscribe", heading: "Unsubscribe.invalidTitle" },
                { path: "/invite", api: "/api/invites", namespace: "workspace", heading: "InviteAccept.invalidTitle" },
                { path: "/invite-link", api: "/api/invite-links", namespace: "workspace", heading: "InviteLinkAccept.invalidTitle" },
            ]) {
                const apiRequest = page.waitForRequest((request) => request.url().endsWith(entry.api) && request.method() === "GET");
                const documentResponse = await page.goto(entry.path);

                expect(documentResponse?.status()).toBe(200);
                expect(await documentResponse?.text()).toContain(`<html lang="${choice.rendered}"`);
                await expect(page.getByRole("heading", {
                    name: message(choice.rendered, entry.namespace, entry.heading),
                    exact: true,
                })).toBeVisible();
                expect((await apiRequest).headers()["accept-language"]).toBe(choice.rendered);
                expect((await context.cookies()).some(({ name }) => name === "NEXT_LOCALE")).toBe(choice.cookie !== null);
            }
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

        await setBrowserLocale(page, "ja");
        await page.reload();

        await expect(page.getByRole("heading", {
            name: message("ja", "auth", "AuthLogin.title"),
            exact: true,
        })).toBeVisible();
        await expect(page.locator("html")).toHaveAttribute("lang", "ja");
    });
});
