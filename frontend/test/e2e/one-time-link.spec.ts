import { expect, test } from "@playwright/test";

test.use({ storageState: { cookies: [], origins: [] } });

test("password reset removes its fragment bearer before exchange navigation", async ({ page }) => {
    const rawToken = "browser_only_reset_bearer_123456789";
    const requestedUrls: string[] = [];
    page.on("request", (request) => requestedUrls.push(request.url()));

    await page.route("**/api/auth/csrf", async (route) => {
        await route.fulfill({
            status: 200,
            contentType: "application/json",
            body: JSON.stringify({
                token: "csrf-token",
                headerName: "X-CSRF-TOKEN",
                parameterName: "_csrf",
                requestIdentity: null,
            }),
        });
    });
    await page.route("**/api/auth/reset-password/exchange", async (route) => {
        expect(route.request().postDataJSON()).toEqual({ token: rawToken });
        await route.fulfill({
            status: 303,
            headers: {
                Location: "/auth/reset-password",
                "Set-Cookie": "connex_password_reset_flow=grant; Path=/api/auth/reset-password; HttpOnly; SameSite=Strict",
            },
        });
    });
    await page.route("**/api/auth/reset-password/validate", async (route) => {
        await route.fulfill({
            status: 200,
            contentType: "application/json",
            body: JSON.stringify({ valid: true }),
        });
    });

    await page.goto(`/auth/reset-password#token=${rawToken}`);
    await expect(page).toHaveURL(/\/auth\/reset-password$/);
    await expect(page.getByRole("heading", { name: "Set a new password" })).toBeVisible();
    expect(requestedUrls.every((url) => !url.includes(rawToken))).toBe(true);
});

test("email change remains reachable with a session and removes its fragment bearer", async ({ context, page }) => {
    const rawToken = "browser_only_email_change_bearer_123456789";
    const requestedUrls: string[] = [];
    page.on("request", (request) => requestedUrls.push(request.url()));
    await context.addCookies([
        {
            name: "JSESSIONID",
            value: "authenticated-browser-session",
            url: "http://127.0.0.1:3000",
        },
    ]);

    await page.route("**/api/auth/email-change/exchange", async (route) => {
        expect(route.request().postDataJSON()).toEqual({ token: rawToken });
        await route.fulfill({
            status: 303,
            headers: {
                Location: "/auth/verify-email",
                "Set-Cookie": "connex_email_change_flow=grant; Path=/api/auth/email-change; HttpOnly; SameSite=Strict",
            },
        });
    });
    await page.route("**/api/auth/email-change/validate", async (route) => {
        await route.fulfill({
            status: 200,
            contentType: "application/json",
            body: JSON.stringify({ valid: true }),
        });
    });

    await page.goto(`/auth/verify-email#token=${rawToken}`);
    await expect(page).toHaveURL(/\/auth\/verify-email$/);
    await expect(page.getByRole("heading", { name: "Confirm your new email" })).toBeVisible();
    expect(requestedUrls.every((url) => !url.includes(rawToken))).toBe(true);
});
