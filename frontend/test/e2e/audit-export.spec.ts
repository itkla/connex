import { randomUUID } from "node:crypto";
import { readFile } from "node:fs/promises";
import { expect, test, type Page } from "@playwright/test";
import Papa from "papaparse";

import { activeWorkspaceId, csrfBootstrap, registerUser, seeder } from "./support/api";
import { useLocale } from "./support/locale";
import { message } from "./support/messages";

type AuditRow = { id: number; action: string; actorLabel?: string; targetLabel?: string; summary?: string };

function isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === "object" && value !== null;
}

function auditRows(value: unknown): AuditRow[] {
    if (!Array.isArray(value)) throw new Error("Expected an audit list");
    return value.map((row: unknown) => {
        if (!isRecord(row) || typeof row.id !== "number" || typeof row.action !== "string") {
            throw new Error("Invalid audit row");
        }
        return {
            id: row.id, action: row.action,
            actorLabel: typeof row.actorLabel === "string" ? row.actorLabel : undefined,
            targetLabel: typeof row.targetLabel === "string" ? row.targetLabel : undefined,
            summary: typeof row.summary === "string" ? row.summary : undefined,
        };
    });
}

function matchesSearch(row: AuditRow, query: string): boolean {
    return [row.action, row.actorLabel, row.targetLabel, row.summary]
        .some((text) => text?.toLowerCase().includes(query.toLowerCase()));
}

async function exportLoadedRows(page: Page, locale: "en" | "ja", path: string, ids: number[]) {
    const responsePromise = page.waitForResponse((response) => new URL(response.url()).pathname === path
        && response.status() === 200);
    const downloadPromise = page.waitForEvent("download");
    await page.getByRole("button", { name: message(locale, "admin", "AdminAuditLog.exportCsv"), exact: true }).click();
    const [response, download] = await Promise.all([responsePromise, downloadPromise]);
    const url = new URL(response.url());
    expect(url.searchParams.get("ids")).toBe(ids.join(","));
    expect(url.searchParams.get("limit")).toBe("200");
    expect(url.searchParams.get("offset")).toBe("0");
    const downloadPath = await download.path();
    if (downloadPath === null) throw new Error("Audit download was not saved");
    const csv = Papa.parse<{ id: string }>(await readFile(downloadPath, "utf8"), { header: true, skipEmptyLines: true });
    expect(csv.errors).toEqual([]);
    expect(csv.data.map((row) => Number(row.id)).sort((a, b) => a - b)).toEqual([...ids].sort((a, b) => a - b));
}

for (const locale of ["en", "ja"] as const) {
    test(`workspace and organization audit export the loaded filter in ${locale} @mobile`, async ({ browser }, testInfo) => {
        test.setTimeout(120_000);
        const baseURL = testInfo.project.use.baseURL;
        if (typeof baseURL !== "string") throw new Error("E2E base URL is required");
        const runId = `audit${randomUUID().replaceAll("-", "").slice(0, 16)}`;
        const password = `Audit!${runId}A1`;
        const context = await browser.newContext({
            baseURL, storageState: { cookies: [], origins: [] }, locale: "en-US", timezoneId: "UTC",
            reducedMotion: "reduce", viewport: testInfo.project.use.viewport,
            colorScheme: locale === "ja" ? "dark" : "light",
        });
        try {
            await registerUser(context.request, { username: runId, password, email: `${runId}@example.com` });
            const workspaceId = await activeWorkspaceId(context.request);
            const page = await context.newPage();
            const cdp = await context.newCDPSession(page);
            await cdp.send("WebAuthn.enable");
            await cdp.send("WebAuthn.addVirtualAuthenticator", {
                options: { protocol: "ctap2", ctap2Version: "ctap2_1", transport: "internal", hasResidentKey: true,
                    hasUserVerification: true, isUserVerified: true, automaticPresenceSimulation: true },
            });
            await page.goto("/settings/personal/security");
            await page.getByRole("button", { name: message("en", "account", "AccountSecurity.add") }).first().click();
            const passwordDialog = page.getByRole("dialog", { name: message("en", "account", "AccountSecurity.passwordTitle") });
            await passwordDialog.getByLabel(message("en", "account", "AccountSecurity.passwordLabel")).fill(password);
            await passwordDialog.getByRole("button", { name: message("en", "account", "AccountSecurity.continue"), exact: true }).click();
            await expect(page.getByText(message("en", "account", "AccountSecurity.added"), { exact: true })).toBeVisible();

            const csrf = await csrfBootstrap(context.request);
            const seed = seeder(context.request, workspaceId, csrf);
            const companyName = `Audit export ${runId}`;
            await seed.post("/api/companies", { name: companyName });
            await seed.post("/api/companies", { name: `Excluded ${runId}` });
            const headers = { "X-Workspace-Id": String(workspaceId) };
            const auditResponse = await context.request.get("/api/audit?limit=200&offset=0", { headers });
            expect(auditResponse.status()).toBe(200);
            const workspaceRows = auditRows(await auditResponse.json());
            const expected = workspaceRows.filter((row) => matchesSearch(row, companyName)).map(({ id }) => id);
            expect(expected.length).toBeGreaterThan(0);
            await useLocale(context, locale);
            await page.goto("/settings/workspace/audit-diagnostics");
            const search = page.getByRole("textbox", { name: message(locale, "admin", "AdminAuditLog.searchAria") });
            await search.fill(companyName);
            await exportLoadedRows(page, locale, "/api/audit/export", expected);
            await search.fill("No matching audit event");
            await expect(page.getByRole("button", { name: message(locale, "admin", "AdminAuditLog.exportCsv"), exact: true })).toBeDisabled();

            const memberships = await context.request.get("/api/workspaces");
            const payload: unknown = await memberships.json();
            if (!isRecord(payload) || !Array.isArray(payload.workspaces)) throw new Error("Expected workspace memberships");
            const workspace: unknown = payload.workspaces.find((row: unknown) => isRecord(row) && row.id === workspaceId);
            if (!isRecord(workspace) || typeof workspace.orgId !== "number") throw new Error("Expected workspace organization");
            const orgPath = `/api/orgs/${workspace.orgId}/audit`;
            const orgResponse = await context.request.get(`${orgPath}?limit=30&offset=0`);
            expect(orgResponse.status()).toBe(200);
            const orgRows = auditRows(await orgResponse.json());
            expect(orgRows.length).toBeGreaterThan(0);
            const action = orgRows[0].action;
            const orgExpected = orgRows.filter((row) => matchesSearch(row, action)).map(({ id }) => id);
            await page.goto("/settings/organization/audit-diagnostics");
            await page.getByRole("textbox", { name: message(locale, "admin", "AdminAuditLog.searchAria") }).fill(action);
            await expect(page.getByRole("button", { name: message(locale, "admin", "AdminAuditLog.filterOutcome"), exact: true })).toBeVisible();
            await exportLoadedRows(page, locale, `${orgPath}/export`, orgExpected);
        } finally {
            await context.close();
        }
    });
}
