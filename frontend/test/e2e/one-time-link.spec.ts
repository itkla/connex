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

const FIRST_LINK = "browser_only_first_link_bearer_123456789";
const SECOND_LINK = "browser_only_second_link_bearer_987654321";
const INVALID_LINK_HEADING = "This link is invalid or has expired";
const UNAVAILABLE_HEADING = "We couldn't check your workspaces";

/** Records every requested URL so a test can prove neither bearer ever left the fragment. */
function recordRequestedUrls(page: Page): string[] {
    const requestedUrls: string[] = [];
    page.on("request", (request) => requestedUrls.push(request.url()));
    return requestedUrls;
}

/**
 * Fulfils a fragment-bearer exchange with the grant redirect and records the bearer it received.
 * The first `rateLimitedAttempts` exchanges are refused with 429 instead, as the admission filter
 * does, so a test can drive the retry path.
 */
async function routeGrantExchange(
    page: Page,
    exchangePath: string,
    grant: { location: string; cookie: string; cookiePath: string },
    exchanged: string[],
    rateLimitedAttempts = 0,
) {
    await page.route(`**${exchangePath}`, async (route) => {
        const body: unknown = route.request().postDataJSON();
        const token = typeof body === "object" && body !== null && "token" in body ? String(body.token) : "";
        exchanged.push(token);
        if (exchanged.length <= rateLimitedAttempts) {
            await route.fulfill({
                status: 429,
                contentType: "application/json",
                body: JSON.stringify({ code: "RATE_LIMITED", message: "Too many requests" }),
            });
            return;
        }
        await route.fulfill({
            status: 303,
            headers: {
                Location: grant.location,
                "Set-Cookie": `${grant.cookie}=grant; Path=${grant.cookiePath}; HttpOnly; SameSite=Strict`,
            },
        });
    });
}

/**
 * Opens the first emailed link, then lands a second link in the same tab as a fragment-only
 * navigation, and proves the page re-reads and strips the second bearer and renders its state.
 */
async function openSecondLinkInSameTab(
    page: Page,
    path: string,
    headings: { first: string; second: string },
    requestedUrls: string[],
) {
    const canonical = new RegExp(`${path.replace(/\//g, "\\/")}$`);

    await page.goto(`${path}#token=${FIRST_LINK}`);
    await expect(page).toHaveURL(canonical);
    await expect(page.getByRole("heading", { name: headings.first })).toBeVisible();

    await page.evaluate((token) => {
        window.location.hash = `token=${token}`;
    }, SECOND_LINK);

    await expect(page.getByRole("heading", { name: headings.second })).toBeVisible();
    await expect(page).toHaveURL(canonical);
    expect(requestedUrls.every((url) => !url.includes(FIRST_LINK) && !url.includes(SECOND_LINK))).toBe(true);
}

/**
 * Opens a link whose first exchange is refused, retries from the unavailable state, and proves the
 * retry re-sends the in-memory bearer without ever restoring the fragment to the address bar.
 */
async function retryRefusedExchange(
    page: Page,
    path: string,
    heading: string,
    requestedUrls: string[],
) {
    const canonical = new RegExp(`${path.replace(/\//g, "\\/")}$`);

    await page.goto(`${path}#token=${FIRST_LINK}`);
    await expect(page.getByRole("heading", { name: UNAVAILABLE_HEADING })).toBeVisible();
    await expect(page).toHaveURL(canonical);

    await page.getByRole("button", { name: "Try again" }).click();

    await expect(page.getByRole("heading", { name: heading })).toBeVisible();
    await expect(page).toHaveURL(canonical);
    expect(requestedUrls.every((url) => !url.includes(FIRST_LINK))).toBe(true);
}

test("workspace invite retries a refused exchange without restoring its fragment bearer", async ({ page }) => {
    const requestedUrls = recordRequestedUrls(page);
    const exchanged: string[] = [];
    await mockCsrf(page);
    await routeGrantExchange(page, "/api/invites/exchange", {
        location: "/invite",
        cookie: "connex_workspace_invite_flow",
        cookiePath: "/api/invites",
    }, exchanged, 1);
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
                flowId: "a".repeat(64),
                workspaceId: 42,
                workspaceName: "Retried Workspace",
                email: "recipient@example.com",
                role: "member",
                invitedByLabel: "Workspace Admin",
                status: "pending",
                valid: true,
            }),
        });
    });

    await retryRefusedExchange(page, "/invite", "Join Retried Workspace", requestedUrls);
    expect(exchanged).toEqual([FIRST_LINK, FIRST_LINK]);
});

test("workspace invite link retries a refused exchange without restoring its fragment bearer", async ({ page }) => {
    const requestedUrls = recordRequestedUrls(page);
    const exchanged: string[] = [];
    await mockCsrf(page);
    await routeGrantExchange(page, "/api/invite-links/exchange", {
        location: "/invite-link",
        cookie: "connex_workspace_invite_link_flow",
        cookiePath: "/api/invite-links",
    }, exchanged, 1);
    await page.route("**/api/auth/me", async (route) => {
        await route.fulfill({
            status: 200,
            contentType: "application/json",
            body: JSON.stringify({ id: 7, email: "recipient@example.com" }),
        });
    });
    await page.route("**/api/invite-links", async (route) => {
        await route.fulfill({
            status: 200,
            contentType: "application/json",
            body: JSON.stringify({
                flowId: "b".repeat(64),
                workspaceId: 43,
                workspaceName: "Retried Workspace",
                role: "member",
                valid: true,
            }),
        });
    });

    await retryRefusedExchange(page, "/invite-link", "Join Retried Workspace", requestedUrls);
    expect(exchanged).toEqual([FIRST_LINK, FIRST_LINK]);
});

test("password reset re-opens a second link that lands in the same tab", async ({ page }) => {
    const requestedUrls = recordRequestedUrls(page);
    const exchanged: string[] = [];
    await mockCsrf(page);
    await routeGrantExchange(page, "/api/auth/reset-password/exchange", {
        location: "/auth/reset-password",
        cookie: "connex_password_reset_flow",
        cookiePath: "/api/auth/reset-password",
    }, exchanged);
    await page.route("**/api/auth/reset-password/validate", async (route) => {
        await route.fulfill({
            status: 200,
            contentType: "application/json",
            body: JSON.stringify({ valid: exchanged.at(-1) === FIRST_LINK }),
        });
    });

    await openSecondLinkInSameTab(page, "/auth/reset-password", {
        first: "Set a new password",
        second: INVALID_LINK_HEADING,
    }, requestedUrls);
    expect(exchanged).toEqual([FIRST_LINK, SECOND_LINK]);
});

test("workspace invite re-opens a second link that lands in the same tab", async ({ page }) => {
    const requestedUrls = recordRequestedUrls(page);
    const exchanged: string[] = [];
    await mockCsrf(page);
    await routeGrantExchange(page, "/api/invites/exchange", {
        location: "/invite",
        cookie: "connex_workspace_invite_flow",
        cookiePath: "/api/invites",
    }, exchanged);
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
                flowId: "a".repeat(64),
                workspaceId: 42,
                workspaceName: exchanged.at(-1) === FIRST_LINK ? "First Workspace" : "Second Workspace",
                email: "recipient@example.com",
                role: "member",
                invitedByLabel: "Workspace Admin",
                status: "pending",
                valid: true,
            }),
        });
    });

    await openSecondLinkInSameTab(page, "/invite", {
        first: "Join First Workspace",
        second: "Join Second Workspace",
    }, requestedUrls);
    expect(exchanged).toEqual([FIRST_LINK, SECOND_LINK]);
});

test("workspace invite link re-opens a second link that lands in the same tab", async ({ page }) => {
    const requestedUrls = recordRequestedUrls(page);
    const exchanged: string[] = [];
    await mockCsrf(page);
    await routeGrantExchange(page, "/api/invite-links/exchange", {
        location: "/invite-link",
        cookie: "connex_workspace_invite_link_flow",
        cookiePath: "/api/invite-links",
    }, exchanged);
    await page.route("**/api/auth/me", async (route) => {
        await route.fulfill({
            status: 200,
            contentType: "application/json",
            body: JSON.stringify({ id: 7, email: "recipient@example.com" }),
        });
    });
    await page.route("**/api/invite-links", async (route) => {
        await route.fulfill({
            status: 200,
            contentType: "application/json",
            body: JSON.stringify({
                flowId: "b".repeat(64),
                workspaceId: 43,
                workspaceName: exchanged.at(-1) === FIRST_LINK ? "First Workspace" : "Second Workspace",
                role: "member",
                valid: true,
            }),
        });
    });

    await openSecondLinkInSameTab(page, "/invite-link", {
        first: "Join First Workspace",
        second: "Join Second Workspace",
    }, requestedUrls);
    expect(exchanged).toEqual([FIRST_LINK, SECOND_LINK]);
});

test("email change re-opens a second link that lands in the same tab", async ({ context, page }) => {
    const requestedUrls = recordRequestedUrls(page);
    const exchanged: string[] = [];
    await mockCsrf(page);
    await context.addCookies([
        {
            name: "JSESSIONID",
            value: "authenticated-browser-session",
            url: "http://127.0.0.1:3000",
        },
    ]);
    await routeGrantExchange(page, "/api/auth/email-change/exchange", {
        location: "/auth/verify-email",
        cookie: "connex_email_change_flow",
        cookiePath: "/api/auth/email-change",
    }, exchanged);
    await page.route("**/api/auth/email-change/validate", async (route) => {
        await route.fulfill({
            status: 200,
            contentType: "application/json",
            body: JSON.stringify({ valid: exchanged.at(-1) === FIRST_LINK }),
        });
    });

    await openSecondLinkInSameTab(page, "/auth/verify-email", {
        first: "Confirm your new email",
        second: INVALID_LINK_HEADING,
    }, requestedUrls);
    expect(exchanged).toEqual([FIRST_LINK, SECOND_LINK]);
});

test("email verification re-opens a second link that lands in the same tab", async ({ page }) => {
    const requestedUrls = recordRequestedUrls(page);
    const exchanged: string[] = [];
    await mockCsrf(page);
    await routeGrantExchange(page, "/api/auth/verify-email/exchange", {
        location: "/auth/confirm-email",
        cookie: "connex_registration_verification_flow",
        cookiePath: "/api/auth/verify-email",
    }, exchanged);
    await page.route("**/api/auth/verify-email/validate", async (route) => {
        await route.fulfill({
            status: 200,
            contentType: "application/json",
            body: JSON.stringify({ valid: exchanged.at(-1) === FIRST_LINK }),
        });
    });

    await openSecondLinkInSameTab(page, "/auth/confirm-email", {
        first: "Verify your email",
        second: INVALID_LINK_HEADING,
    }, requestedUrls);
    expect(exchanged).toEqual([FIRST_LINK, SECOND_LINK]);
});

test("passkey enrollment confirmation re-opens a second link that lands in the same tab", async ({ page }) => {
    const requestedUrls = recordRequestedUrls(page);
    const exchanged: string[] = [];
    await mockCsrf(page);
    await page.route("**/api/auth/webauthn/register/confirmation/exchange", async (route) => {
        const body: unknown = route.request().postDataJSON();
        const token = typeof body === "object" && body !== null && "token" in body ? String(body.token) : "";
        exchanged.push(token);
        await route.fulfill(token === FIRST_LINK
            ? {
                status: 200,
                contentType: "application/json",
                body: JSON.stringify({ message: "Confirmed" }),
            }
            : {
                status: 400,
                contentType: "application/json",
                body: JSON.stringify({ code: "INVALID_TOKEN", message: INVALID_LINK_HEADING }),
            });
    });

    await openSecondLinkInSameTab(page, "/auth/confirm-passkey", {
        first: "Enrollment confirmed",
        second: INVALID_LINK_HEADING,
    }, requestedUrls);
    expect(exchanged).toEqual([FIRST_LINK, SECOND_LINK]);
});
