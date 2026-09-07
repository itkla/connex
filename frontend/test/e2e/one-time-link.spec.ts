import { expect, test, type Page } from "@playwright/test";

test.use({ storageState: { cookies: [], origins: [] } });

async function mockCsrf(page: Page) {
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
}

test("password reset removes its fragment bearer before exchange navigation", async ({ page }) => {
    const rawToken = "browser_only_reset_bearer_123456789";
    const requestedUrls: string[] = [];
    page.on("request", (request) => requestedUrls.push(request.url()));

    await mockCsrf(page);
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

test("workspace invite removes its fragment bearer before rendering the preview", async ({ page }) => {
    const rawToken = "browser_only_workspace_invite_bearer_123456789";
    const flowId = "a".repeat(64);
    const requestedUrls: string[] = [];
    page.on("request", (request) => requestedUrls.push(request.url()));

    await mockCsrf(page);
    await page.route("**/api/invites/exchange", async (route) => {
        expect(route.request().postDataJSON()).toEqual({ token: rawToken });
        await route.fulfill({
            status: 303,
            headers: {
                Location: "/invite",
                "Set-Cookie": "connex_workspace_invite_flow=grant; Path=/api/invites; HttpOnly; SameSite=Strict",
            },
        });
    });
    await page.route("**/api/auth/me", async (route) => {
        await route.fulfill({
            status: 200,
            contentType: "application/json",
            body: JSON.stringify({ id: 7, email: "recipient@example.com" }),
        });
    });
    await page.route("**/api/invites", async (route) => {
        await route.fulfill({
            status: 200,
            contentType: "application/json",
            body: JSON.stringify({
                flowId,
                workspaceId: 42,
                workspaceName: "Security Workspace",
                email: "recipient@example.com",
                role: "member",
                invitedByLabel: "Workspace Admin",
                status: "pending",
                valid: true,
            }),
        });
    });

    await page.goto(`/invite#token=${rawToken}`);
    await expect(page).toHaveURL(/\/invite$/);
    await expect(page.getByRole("heading", { name: "Join Security Workspace" })).toBeVisible();
    expect(requestedUrls.every((url) => !url.includes(rawToken))).toBe(true);
});

test("email change remains reachable with a session and removes its fragment bearer", async ({ context, page }) => {
    const rawToken = "browser_only_email_change_bearer_123456789";
    const requestedUrls: string[] = [];
    page.on("request", (request) => requestedUrls.push(request.url()));
    await mockCsrf(page);
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

test("document acceptance removes its fragment bearer before exchange navigation", async ({ context, page }) => {
    const rawToken = `w42-${"a".repeat(64)}`;
    const requestedUrls: string[] = [];
    page.on("request", (request) => requestedUrls.push(request.url()));

    await mockCsrf(page);
    await page.route("**/api/document-acceptance/exchange", async (route) => {
        expect(route.request().postDataJSON()).toEqual({ token: rawToken });
        await route.fulfill({
            status: 303,
            headers: {
                Location: "/document-acceptance",
                "Set-Cookie": "connex_document_acceptance_flow=grant; Path=/api/document-acceptance; HttpOnly; SameSite=Strict",
            },
        });
    });
    await page.route(
        (url) => url.pathname === "/api/document-acceptance",
        async (route) => {
            await route.fulfill({
                status: 404,
                contentType: "application/json",
                body: JSON.stringify({
                    code: "RESOURCE_NOT_FOUND",
                    message: "Document link is no longer available",
                }),
            });
        },
    );

    await page.goto(`/document-acceptance#token=${rawToken}`);
    await expect(page).toHaveURL(/\/document-acceptance$/);
    await expect(page.getByRole("heading", { name: "Link unavailable" })).toBeVisible();
    expect(requestedUrls.every((url) => !url.includes(rawToken))).toBe(true);
    expect((await context.cookies()).some((cookie) => cookie.value.includes(rawToken))).toBe(false);
});

test("unsubscribe removes its fragment bearer before exchange navigation", async ({ context, page }) => {
    const rawToken = "b".repeat(64);
    const requestedUrls: string[] = [];
    page.on("request", (request) => requestedUrls.push(request.url()));

    await mockCsrf(page);
    await page.route("**/api/delivery/unsubscribe/exchange", async (route) => {
        expect(route.request().postDataJSON()).toEqual({ token: rawToken });
        await route.fulfill({
            status: 303,
            headers: {
                Location: "/unsubscribe",
                "Set-Cookie": "connex_delivery_unsubscribe_flow=grant; Path=/api/delivery/unsubscribe; HttpOnly; SameSite=Strict",
            },
        });
    });
    await page.route(
        (url) => url.pathname === "/api/delivery/unsubscribe",
        async (route) => {
            await route.fulfill({
                status: 200,
                contentType: "application/json",
                body: JSON.stringify({
                    flowId: "d".repeat(64),
                    channel: "email",
                    address: "r***@dest.test",
                    unsubscribed: false,
                }),
            });
        },
    );

    await page.goto(`/unsubscribe#token=${rawToken}`);
    await expect(page).toHaveURL(/\/unsubscribe$/);
    await expect(page.getByRole("heading", { name: "Unsubscribe" })).toBeVisible();
    expect(requestedUrls.every((url) => !url.includes(rawToken))).toBe(true);
    expect((await context.cookies()).some((cookie) => cookie.value.includes(rawToken))).toBe(false);
});
