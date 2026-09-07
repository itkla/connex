import { expect, test, type BrowserContext, type Page } from "@playwright/test";

import { CSP_REPORT_PATH } from "@/security-headers";
import { activeWorkspaceId, registerUser } from "./support/api";
import { runFixture } from "./support/fixtures";
import { message } from "./support/messages";

declare global {
    interface Window {
        connexCspViolation: (violation: CspViolation) => void;
    }
}

type CspViolation = {
    directive: string;
    blocked: string;
    document: string;
    disposition: string;
    source: string;
    line: number;
};

type ViolationCapture = {
    violations: CspViolation[];
    consoleRefusals: string[];
};

const CSP_CONSOLE_PATTERN = /Content Security Policy|Refused to/;
const NONCED_SCRIPT_SRC = /script-src 'self' 'nonce-[^']+' 'strict-dynamic'/;
const PROBE_MARKUP =
    "<img data-csp-probe src=\"/csp-probe-missing.png\" onerror=\"document.documentElement.dataset.cspProbe = 'ran'\">";
const PIXEL_PNG =
    "iVBORw0KGgoAAAANSUhEUgAAAAoAAAAKCAYAAACNMs+9AAAADklEQVR4XmNgGAWDEwAAAZoAAWA5V18AAAAASUVORK5CYII=";

/**
 * Arms a context so every page reports its CSP violations back to the test.
 *
 * <p>A binding rather than a window array: bindings survive navigation, so a violation raised by
 * the very first bytes of the next document is still captured.
 */
async function armViolationCapture(context: BrowserContext): Promise<ViolationCapture> {
    const violations: CspViolation[] = [];
    const consoleRefusals: string[] = [];
    await context.exposeBinding("connexCspViolation", (_source, violation: CspViolation) => {
        violations.push(violation);
    });
    await context.addInitScript(() => {
        document.addEventListener("securitypolicyviolation", (event) => {
            window.connexCspViolation({
                directive: event.effectiveDirective,
                blocked: event.blockedURI,
                document: event.documentURI,
                disposition: event.disposition,
                source: event.sourceFile,
                line: event.lineNumber,
            });
        });
    });
    context.on("page", (page) => {
        page.on("console", (consoleMessage) => {
            if (CSP_CONSOLE_PATTERN.test(consoleMessage.text())) {
                consoleRefusals.push(consoleMessage.text());
            }
        });
    });
    for (const page of context.pages()) {
        page.on("console", (consoleMessage) => {
            if (CSP_CONSOLE_PATTERN.test(consoleMessage.text())) {
                consoleRefusals.push(consoleMessage.text());
            }
        });
    }
    return { violations, consoleRefusals };
}

function expectNoViolations(capture: ViolationCapture): void {
    expect(capture.violations).toEqual([]);
    expect(capture.consoleRefusals).toEqual([]);
}

/**
 * The `Reporting-Endpoints` value the proxy must emit for the suite's base URL, or null when the
 * browser-facing origin is not HTTPS: Chromium discards non-cryptographic reporting endpoints, so
 * the proxy then relies on `report-uri` alone.
 */
function reportingEndpointFor(baseURL: string): string | null {
    const origin = new URL(baseURL);
    return origin.protocol === "https:" ? `csp-endpoint="${origin.origin}${CSP_REPORT_PATH}"` : null;
}

function baseUrlOf(projectBaseURL: string | undefined): string {
    if (typeof projectBaseURL !== "string") throw new Error("The E2E project requires a base URL");
    return projectBaseURL;
}

async function expectEnforcedPolicyHeaders(page: Page, url: string, baseURL: string): Promise<void> {
    const response = await page.goto(url, { waitUntil: "domcontentloaded" });
    expect(response, `no response for ${url}`).not.toBeNull();
    const headers = response === null ? {} : response.headers();
    const policy = headers["content-security-policy"];
    expect(policy, `no enforced policy on ${url}`).toBeDefined();
    expect(policy).toMatch(NONCED_SCRIPT_SRC);
    expect(policy).toContain(`report-uri ${CSP_REPORT_PATH}`);
    const reportingEndpoint = reportingEndpointFor(baseURL);
    if (reportingEndpoint === null) {
        expect(policy).not.toContain("report-to");
        expect(headers["reporting-endpoints"]).toBeUndefined();
    } else {
        expect(policy).toContain("report-to csp-endpoint");
        expect(headers["reporting-endpoints"]).toBe(reportingEndpoint);
    }
    expect(policy).toContain("default-src 'self'");
    expect(policy).toContain("object-src 'none'");
    expect(policy).toContain("frame-ancestors 'none'");
    expect(headers["content-security-policy-report-only"]).toBeUndefined();
}

test.describe("frontend CSP enforcement", () => {
    test("HTML responses carry the enforced full policy and its report-uri", async ({ page }, testInfo) => {
        const baseURL = baseUrlOf(testInfo.project.use.baseURL);

        await expectEnforcedPolicyHeaders(page, "/dashboard", baseURL);
    });

    test("the authenticated journey renders under enforcement without violations", async ({ page, context }, testInfo) => {
        test.setTimeout(120_000);
        const fixture = runFixture(testInfo.project.name);
        const capture = await armViolationCapture(context);

        await page.goto("/dashboard", { waitUntil: "domcontentloaded" });
        await expect(page.locator("[data-app-main]")).toBeVisible();

        await page.goto("/insights/analytics", { waitUntil: "domcontentloaded" });
        await expect(page.getByRole("heading", { name: "Analytics" })).toBeVisible();
        await expect(page.getByRole("heading", { name: "Trends" })).toBeVisible();
        await expect(page.locator("svg.recharts-surface").first()).toBeVisible();
        expect(await page.locator("svg.recharts-surface").count()).toBeGreaterThan(0);

        await page.goto("/records/contacts?view=table&sort=name&dir=asc&page=1&size=10", {
            waitUntil: "domcontentloaded",
        });
        await expect(page.locator(`[data-record-row-id="${fixture.contacts.peek.id}"]`)).toBeVisible();

        await page.goto(`/records/contacts/${fixture.contacts.peek.id}`, { waitUntil: "domcontentloaded" });
        await expect(page.getByRole("heading", { name: fixture.contacts.peek.name }).first()).toBeVisible();

        await page.goto("/library/documents", { waitUntil: "domcontentloaded" });
        await expect(page.locator("[data-app-main]")).toBeVisible();

        await page.getByRole("button", { name: message("en", "common", "AskConnex.title"), exact: true }).click();
        await expect(page.locator("#ask-connex-desktop-panel")).toHaveAttribute("aria-hidden", "false");

        expectNoViolations(capture);
    });

    test("an uploaded image previews under enforcement", async ({ page, context }, testInfo) => {
        test.setTimeout(90_000);
        const fixture = runFixture(testInfo.project.name);
        const capture = await armViolationCapture(context);
        const fileName = `csp-proof-${Date.now().toString(36)}.png`;

        await page.goto(`/records/contacts/${fixture.contacts.edit.id}`, { waitUntil: "domcontentloaded" });
        const attachmentsHeading = page.getByRole("heading", {
            level: 2,
            name: new RegExp(`^${message("en", "attachments", "Attachments.title")} · \\d+$`),
        });
        await expect(attachmentsHeading).toBeVisible();
        await attachmentsHeading
            .locator("xpath=ancestor::div[.//input[@type='file'][@multiple]][1]//input[@type='file'][@multiple]")
            .setInputFiles({
                name: fileName,
                mimeType: "image/png",
                buffer: Buffer.from(PIXEL_PNG, "base64"),
            });
        await expect(page.getByRole("link", { name: fileName })).toBeVisible({ timeout: 30_000 });

        await page.goto("/library/files", { waitUntil: "domcontentloaded" });
        await page.getByText(fileName).first().click();
        const preview = page.locator('img[src*="/api/attachments/content/"]').first();
        await expect(preview).toBeVisible();
        await expect
            .poll(() => preview.evaluate((image: HTMLImageElement) => image.complete && image.naturalWidth > 0))
            .toBe(true);

        expectNoViolations(capture);
    });

    test("public pages render under enforcement without violations", async ({ browser }, testInfo) => {
        const baseURL = baseUrlOf(testInfo.project.use.baseURL);
        const context = await browser.newContext({
            baseURL,
            locale: "en-US",
            timezoneId: "UTC",
            reducedMotion: "reduce",
            storageState: { cookies: [], origins: [] },
        });
        try {
            const capture = await armViolationCapture(context);
            const page = await context.newPage();

            await expectEnforcedPolicyHeaders(page, "/auth/login", baseURL);
            await expect(page.getByRole("button", { name: "Sign in", exact: true })).toBeVisible();

            await page.goto("/auth/register", { waitUntil: "domcontentloaded" });
            await expect(page.getByRole("button", { name: "Create account" })).toBeVisible();

            await page.goto(`/document-acceptance/w42-${"c".repeat(64)}`, { waitUntil: "domcontentloaded" });
            await expect(page.getByRole("heading", {
                name: message("en", "document-acceptance", "DocumentAcceptance.unavailableTitle"),
            })).toBeVisible();

            expectNoViolations(capture);
        } finally {
            await context.close();
        }
    });

    test("an injected inline event handler is blocked and reported", async ({ page, context }) => {
        test.setTimeout(90_000);
        const capture = await armViolationCapture(context);
        await page.goto("/dashboard", { waitUntil: "domcontentloaded" });
        await expect(page.locator("[data-app-main]")).toBeVisible();

        const delivery = page.waitForRequest(
            (request) => request.method() === "POST"
                && new URL(request.url()).pathname === CSP_REPORT_PATH,
            { timeout: 30_000 },
        );
        await page.evaluate((markup) => {
            document.body.insertAdjacentHTML("beforeend", markup);
        }, PROBE_MARKUP);
        const probe = page.locator("img[data-csp-probe]");
        await expect.poll(() => probe.evaluate((image: HTMLImageElement) => image.complete)).toBe(true);

        await expect.poll(() => capture.violations.length).toBeGreaterThan(0);
        expect(capture.violations).toHaveLength(1);
        expect(capture.violations[0]).toMatchObject({
            directive: "script-src-attr",
            blocked: "inline",
            disposition: "enforce",
        });
        expect(await page.evaluate(() => document.documentElement.dataset.cspProbe)).toBeUndefined();

        const request = await delivery;
        expect((await request.response())?.status()).toBe(204);
        expect(request.headers()["content-type"]).toMatch(/application\/(reports\+json|csp-report)/);
        expect(capture.consoleRefusals.length).toBeGreaterThan(0);
    });

    test("the report collector accepts garbage and refuses reads", async ({ page }) => {
        const garbage = await page.request.post(CSP_REPORT_PATH, {
            headers: { "content-type": "application/csp-report" },
            data: "not json",
        });
        expect(garbage.status()).toBe(204);

        const read = await page.request.get(CSP_REPORT_PATH);
        expect(read.status()).not.toBe(200);
    });

    test("the dark theme renders under enforcement without violations", async ({ browser }, testInfo) => {
        const baseURL = baseUrlOf(testInfo.project.use.baseURL);
        const context = await browser.newContext({
            baseURL,
            locale: "en-US",
            timezoneId: "UTC",
            reducedMotion: "reduce",
            colorScheme: "dark",
            storageState: testInfo.project.use.storageState,
        });
        try {
            await context.addInitScript(() => {
                window.localStorage.setItem("theme", "dark");
            });
            const capture = await armViolationCapture(context);
            const page = await context.newPage();

            await page.goto("/dashboard", { waitUntil: "domcontentloaded" });
            await expect(page.locator("[data-app-main]")).toBeVisible();
            await page.goto("/insights/analytics", { waitUntil: "domcontentloaded" });
            await expect(page.locator("svg.recharts-surface").first()).toBeVisible();

            expectNoViolations(capture);
        } finally {
            await context.close();
        }
    });

    test("passkey enrollment and passkey sign-in complete under enforcement", async ({ browser }, testInfo) => {
        test.setTimeout(120_000);
        const baseURL = baseUrlOf(testInfo.project.use.baseURL);
        const runId = `csp${Date.now().toString(36)}${testInfo.retry}`;
        const password = `CspPasskey!${runId}A1`;
        const context = await browser.newContext({
            baseURL,
            locale: "en-US",
            timezoneId: "UTC",
            reducedMotion: "reduce",
            storageState: { cookies: [], origins: [] },
        });
        try {
            await registerUser(context.request, {
                username: runId,
                password,
                email: `${runId}@example.com`,
            });
            await activeWorkspaceId(context.request);
            const capture = await armViolationCapture(context);
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

            await page.goto("/settings/personal/security", { waitUntil: "domcontentloaded" });
            await page.getByRole("button", { name: message("en", "account", "AccountSecurity.add") }).first().click();
            const passwordDialog = page.getByRole("dialog", {
                name: message("en", "account", "AccountSecurity.passwordTitle"),
            });
            await passwordDialog.getByLabel(message("en", "account", "AccountSecurity.passwordLabel")).fill(password);
            await passwordDialog
                .getByRole("button", { name: message("en", "account", "AccountSecurity.continue"), exact: true })
                .click();
            await expect(page.getByText(message("en", "account", "AccountSecurity.added"), { exact: true }))
                .toBeVisible();
            await expect(page.getByText(message("en", "account", "AccountSecurity.emptyTitle"))).toBeHidden();

            await context.clearCookies();
            await page.goto("/auth/login", { waitUntil: "domcontentloaded" });
            await page.getByRole("button", { name: message("en", "auth", "AuthLogin.passkeyButton") }).click();
            await expect(page).toHaveURL(/\/dashboard$/, { timeout: 30_000 });
            await expect(page.locator("[data-app-main]")).toBeVisible();

            expectNoViolations(capture);
        } finally {
            await context.close();
        }
    });
});
