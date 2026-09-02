import { expect, test, type APIRequestContext, type Locator, type Page } from "@playwright/test";

import { activeWorkspaceId, csrfBootstrap, registerUser } from "./support/api";

function isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === "object" && value !== null;
}

const RELEASE_VERSION = /^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-((0|[1-9][0-9]*|[0-9]*[A-Za-z-][0-9A-Za-z-]*)(\.(0|[1-9][0-9]*|[0-9]*[A-Za-z-][0-9A-Za-z-]*))*))?$/;
const DEVELOPMENT_VERSIONS = new Set(["0.0.0-dev", "0.0.1-snapshot"]);

const BUILD_IDENTITY_COPY = {
    matched: {
        status: "Release versions match",
        body: "The frontend and backend carry matching verified release provenance.",
        tone: "bg-emerald-500/10",
    },
    unverified: {
        status: "Versions agree — artifact provenance not verified",
        body: "The frontend and backend report the same version, but they do not expose the release evidence needed to confirm a matched release set.",
        tone: "bg-muted",
    },
    mismatched: {
        status: "Release builds do not match",
        body: "The frontend and backend report different release versions or release evidence. Deploy them together from one release set.",
        tone: "bg-amber-500/10",
    },
    unavailable: {
        status: "Backend version unavailable",
        body: "Couldn't check the backend version, so a release match cannot be confirmed. No settings changed — try again.",
        tone: "bg-amber-500/10",
    },
    unversioned: {
        status: "Development or source build",
        body: "At least one component has no stamped release version, so a release match cannot be confirmed.",
        tone: "bg-muted",
    },
} as const;

function isReleaseVersion(version: string | null): version is string {
    return version !== null
        && !DEVELOPMENT_VERSIONS.has(version.toLowerCase())
        && RELEASE_VERSION.test(version);
}

async function buildValue(section: Locator, label: string): Promise<string> {
    const value = section.getByText(label, { exact: true }).locator("..").locator("dd");
    await expect(value).toHaveCount(1);
    return (await value.innerText()).trim();
}

/** Verifies the live endpoint, component state, rendered values, tone, and environment-honest copy. */
async function expectRunningBuildIdentity(page: Page, request: APIRequestContext) {
    const heading = page.getByRole("heading", { name: "Build identity" });
    const section = page.locator("section").filter({ has: heading });
    await expect(section).toBeVisible();

    const response = await request.get("/api/version");
    const frontendDisplay = await buildValue(section, "Frontend version");
    const backendDisplay = await buildValue(section, "Backend version");
    const buildTimeDisplay = await buildValue(section, "Build time");
    const gitShaDisplay = await buildValue(section, "Source commit");
    const frontendVersion = frontendDisplay === "Not stamped" ? null : frontendDisplay;

    let expected: keyof typeof BUILD_IDENTITY_COPY;
    if (!response.ok()) {
        expected = "unavailable";
        expect(backendDisplay).toBe("Unavailable");
        expect(buildTimeDisplay).toBe("Unavailable");
        expect(gitShaDisplay).toBe("Unavailable");
    } else {
        const payload: unknown = await response.json();
        if (!isRecord(payload) || typeof payload.version !== "string") {
            throw new Error("Version response is missing its version");
        }
        const backendVersion = payload.version.trim();
        expect(backendDisplay).toBe(backendVersion || "Not stamped");
        for (const [value, rendered] of [
            [payload.buildTime, buildTimeDisplay],
            [payload.gitSha, gitShaDisplay],
        ] as const) {
            const metadata = typeof value === "string" ? value.trim() : "";
            expect(rendered).toBe(
                metadata.length > 0 && metadata.toLowerCase() !== "unknown"
                    ? metadata
                    : "Unavailable",
            );
        }
        expected = !isReleaseVersion(frontendVersion) || !isReleaseVersion(backendVersion)
            ? "unversioned"
            : frontendVersion === backendVersion
              ? "unverified"
              : "mismatched";
    }

    const expectedCopy = BUILD_IDENTITY_COPY[expected];
    const status = section.getByText(expectedCopy.status, { exact: true });
    await expect(status).toBeVisible();
    await expect(status).toHaveClass(new RegExp(expectedCopy.tone.replace("/", "\\/")));
    await expect(section.getByText(expectedCopy.body, { exact: true })).toBeVisible();
    const allStatuses = section.getByText(
        new RegExp(`^(${Object.values(BUILD_IDENTITY_COPY)
            .map(({ status: candidate }) => candidate.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"))
            .join("|")})$`),
    );
    await expect(allStatuses).toHaveCount(1);
}

async function installVirtualAuthenticator(page: Page): Promise<() => Promise<void>> {
    const session = await page.context().newCDPSession(page);
    await session.send("WebAuthn.enable");
    try {
        const { authenticatorId } = await session.send("WebAuthn.addVirtualAuthenticator", {
            options: {
                protocol: "ctap2",
                ctap2Version: "ctap2_1",
                transport: "internal",
                hasResidentKey: true,
                hasUserVerification: true,
                automaticPresenceSimulation: true,
                isUserVerified: true,
            },
        });
        return async () => {
            if (page.isClosed()) return;
            await session.send("WebAuthn.removeVirtualAuthenticator", { authenticatorId });
            await session.send("WebAuthn.disable");
            await session.detach();
        };
    } catch (error) {
        await session.send("WebAuthn.disable");
        await session.detach();
        throw error;
    }
}

test.describe("workspace and organization identity", () => {
    test("privileged MFA confinement lands on a usable enrolment flow without reloading", async ({ browser }, testInfo) => {
        test.setTimeout(90_000);
        const baseURL = testInfo.project.use.baseURL;
        if (typeof baseURL !== "string") throw new Error("The E2E project requires a base URL");
        const runId = `mfa${Date.now().toString(36)}${testInfo.retry}`;
        const password = `MfaEnroll!${runId}A1`;
        const context = await browser.newContext({
            baseURL,
            locale: "en-US",
            timezoneId: "UTC",
            reducedMotion: "reduce",
            storageState: { cookies: [], origins: [] },
        });
        let removeVirtualAuthenticator: (() => Promise<void>) | null = null;
        try {
            await registerUser(context.request, {
                username: runId,
                password,
                email: `${runId}@example.com`,
            });
            await activeWorkspaceId(context.request);
            await csrfBootstrap(context.request);
            const page = await context.newPage();
            removeVirtualAuthenticator = await installVirtualAuthenticator(page);
            let notificationCountRequests = 0;
            await page.route("**/api/notifications/counts", async (route) => {
                notificationCountRequests += 1;
                if (notificationCountRequests === 1) {
                    await route.fulfill({
                        status: 403,
                        contentType: "application/json",
                        body: JSON.stringify({
                            code: "PRIVILEGED_MFA_ENROLLMENT_REQUIRED",
                            message: "A passkey must be enrolled before this privileged account can continue",
                        }),
                    });
                } else {
                    await route.fallback();
                }
            });

            await page.goto("/settings/personal/security");
            await expect(page).toHaveURL(/\/settings\/personal\/security\?mfa=enroll$/, { timeout: 20_000 });
            const addPasskey = page.getByRole("button", { name: "Add a passkey" }).first();
            await expect(addPasskey).toBeVisible();
            expect(notificationCountRequests).toBe(1);

            await addPasskey.click();
            const passwordDialog = page.getByRole("dialog", { name: "Confirm your password" });
            await passwordDialog.getByLabel("Current password").fill(password);
            await passwordDialog.getByRole("button", { name: "Continue", exact: true }).click();
            await expect(page.getByText("Passkey added", { exact: true })).toBeVisible();
            await expect(page).toHaveURL(/\/settings\/personal\/security$/);
            await page.getByRole("link", { name: "Dashboard", exact: true }).click();
            await expect(page).toHaveURL(/\/dashboard$/);
            await expect.poll(() => notificationCountRequests).toBeGreaterThan(1);
        } finally {
            if (removeVirtualAuthenticator) await removeVirtualAuthenticator();
            await context.close();
        }
    });

    test("renames both scopes and navigates the authorized organization layout", async ({ browser }, testInfo) => {
        const baseURL = testInfo.project.use.baseURL;
        if (typeof baseURL !== "string") throw new Error("The E2E project requires a base URL");
        const runId = `settings${Date.now().toString(36)}${testInfo.retry}`;
        const password = `Settings!${runId}A1`;
        const context = await browser.newContext({
            baseURL,
            locale: "en-US",
            timezoneId: "UTC",
            reducedMotion: "reduce",
            storageState: { cookies: [], origins: [] },
        });
        let removeVirtualAuthenticator: (() => Promise<void>) | null = null;
        try {
            await registerUser(context.request, {
                username: runId,
                password,
                email: `${runId}@example.com`,
            });
            const workspaceId = await activeWorkspaceId(context.request);
            const page = await context.newPage();
            removeVirtualAuthenticator = await installVirtualAuthenticator(page);
            const workspaceName = `Design operations ${runId}`;
            const organizationName = `Northstar ${runId}`;

            await page.goto("/settings/personal/security");
            await page.getByRole("button", { name: "Add a passkey" }).first().click();
            const passwordDialog = page.getByRole("dialog", { name: "Confirm your password" });
            await passwordDialog.getByLabel("Current password").fill(password);
            await passwordDialog.getByRole("button", { name: "Continue", exact: true }).click();
            await expect(page.getByText("Passkey added", { exact: true })).toBeVisible();

            await page.goto("/settings/workspace/audit-diagnostics");
            await expectRunningBuildIdentity(page, context.request);

            await page.goto("/settings/workspace/general");
            await expect(page.getByRole("heading", { name: "Workspace identity" })).toBeVisible();
            await page.getByLabel("Workspace name").fill(workspaceName);
            await page.getByLabel("Reporting timezone").fill("Asia/Tokyo");
            await page.getByRole("option", { name: "Asia/Tokyo", exact: true }).click();
            await page.getByRole("button", { name: "Save changes" }).click();
            await expect(page.getByText("Workspace settings updated.")).toBeVisible();
            await expect(page.getByRole("button", { name: "Switch workspace" })).toContainText(workspaceName);
            await expect(page.getByRole("navigation", { name: "Breadcrumb" })).toContainText(workspaceName);

            await page.goto("/organization/overview");
            await expect(page.getByRole("heading", { name: "Organization identity" })).toBeVisible();
            await page.getByLabel("Organization name").fill(organizationName);
            await page.getByRole("button", { name: "Save changes" }).click();
            await expect(page.getByText("Organization settings updated.")).toBeVisible();
            await expect(page.getByText(organizationName, { exact: true }).first()).toBeVisible();
            await expect(page.getByText("Asia/Tokyo", { exact: true })).toBeVisible();
            await expect(page.getByRole("navigation", { name: "Breadcrumb" })).toContainText(organizationName);

            await page.getByRole("button", { name: "Table" }).click();
            const organizationTable = page.getByRole("table", {
                name: "Authorized organization workspaces and memberships",
            });
            await expect(organizationTable).toBeVisible();
            await expect(organizationTable.getByRole("button", { name: workspaceName, exact: true })).toBeVisible();
            await organizationTable.getByRole("button", { name: "E2E Harness", exact: true }).click();
            await expect(page).toHaveURL(new RegExp(`/users/\\d+$`));
            const userBreadcrumb = page.getByRole("navigation", { name: "Breadcrumb" });
            await expect(userBreadcrumb).toContainText("E2E Harness");
            await expect(userBreadcrumb).toContainText("Users");
            await expect(userBreadcrumb).not.toContainText(organizationName);

            await page.goto("/settings/organization/audit-diagnostics");
            await expectRunningBuildIdentity(page, context.request);

            const workspacesResponse = await context.request.get("/api/workspaces");
            expect(workspacesResponse.status()).toBe(200);
            const payload: unknown = await workspacesResponse.json();
            if (!isRecord(payload) || !Array.isArray(payload.workspaces)) {
                throw new Error("Workspace response is missing its membership list");
            }
            const workspace = payload.workspaces.find((entry) => isRecord(entry) && entry.id === workspaceId);
            expect(workspace).toMatchObject({
                name: workspaceName,
                orgName: organizationName,
                timezone: "Asia/Tokyo",
            });
        } finally {
            await removeVirtualAuthenticator?.();
            await context.close();
        }
    });
});
