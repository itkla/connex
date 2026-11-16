import { expect, test } from "@playwright/test";

import type { WorkflowListItem, WorkflowRunSummary } from "@/app/lib/types";
import { dealDocumentsHref } from "@/app/components/records/deals/dealLinks";
import { runFixture } from "./support/fixtures";
import { useLocale } from "./support/locale";
import { message } from "./support/messages";

const WORKFLOW_ID = 990004;
const STARTED_AT = "2026-11-05T01:00:00";

for (const locale of ["en", "ja"] as const) {
    const w = (key: string, values: Record<string, string | number> = {}) => Object.entries(values).reduce(
        (text, [name, value]) => text.replaceAll(`{${name}}`, String(value)),
        message(locale, "workspace", `WorkspaceWorkflows.${key}`),
    );
    test(`${locale}: failed workflow history explains recovery and links the visible target @mobile`, async ({ page }, testInfo) => {
        const fixture = runFixture(testInfo.project.name);
        const company = fixture.companies.primary;
        const deal = fixture.deals.primary;
        await useLocale(page, locale);
        await page.emulateMedia({ colorScheme: testInfo.project.name === "mobile-chromium" ? "dark" : "light" });
        const run: WorkflowRunSummary = {
            runKey: "legacy-12", source: "legacy", status: "failed", legacyStatus: "failed",
            version: { id: 3, number: 2, definitionHash: "hash", publishedAt: STARTED_AT },
            trigger: { type: "entity_change", event: "company.updated", recordType: "company", recordId: company.id },
            runtimeState: null, startedAt: STARTED_AT, finishedAt: STARTED_AT, durationMs: 0,
            failure: { nodeId: null, code: "action_tag_unavailable", message: "private-debug-payload", actionNumber: 1, actionType: "add_tag" },
            stepDetailAvailable: false,
        };
        const workflow: WorkflowListItem = {
            id: WORKFLOW_ID, name: "Workflow history regression", description: null, enabled: true,
            runtimeOwner: "legacy", executionAvailable: true, executionUnavailableReason: null,
            archivedAt: null, intakePausedAt: null, intakePausedById: null, draftRevision: 0,
            recordType: "company", executionMode: "user", runAsUserId: null,
            createdById: null, updatedById: null, createdAt: STARTED_AT, updatedAt: STARTED_AT,
            activeVersion: { id: 3, number: 2, publishedAt: STARTED_AT }, nodeCount: 3, actionCount: 1,
            latestRun: run,
        };
        const documentRuns: WorkflowRunSummary[] = [61, 62, 63].map((id) => ({
            ...run, runKey: `legacy-${id}`, status: "succeeded", legacyStatus: "matched", version: null, failure: null,
            trigger: { type: "entity_change", event: "document.finalized", recordType: "document", recordId: id },
        }));
        const documentRequests: string[][] = [];
        await page.route(/\/api\/documents\/targets(?:\?.*)?$/, (route) => {
            documentRequests.push(new URL(route.request().url()).searchParams.getAll("ids"));
            expect(route.request().headers()["x-workspace-id"]).toBe(String(fixture.workspaceId));
            return route.fulfill({ json: [{ id: 61, dealId: deal.id, title: "Workflow quote" }] });
        });
        await page.route(/\/api\/workflows(?:\?.*)?$/, (route) => route.fulfill({ json: [workflow] }));
        await page.route(new RegExp(`/api/workflows/${WORKFLOW_ID}/runs(?:\\?.*)?$`), (route) => route.fulfill({ json: { items: [run, ...documentRuns], nextCursor: null } }));
        await page.goto("/workflows");
        await page.getByRole("button", { name: w("rowActions", { name: workflow.name }), exact: true }).click();
        await page.getByRole("menuitem", { name: w("runs.view"), exact: true }).click();
        const dialog = page.getByRole("dialog");
        await expect(dialog.getByText(w("runs.status.failed"), { exact: true })).toBeVisible();
        await expect(dialog.getByText(w("diagnostics.action_tag_unavailable"), { exact: true })).toBeVisible();
        await expect(dialog.getByText(w("runs.failureRecovery"), { exact: true })).toBeVisible();
        await expect(dialog.getByRole("link", { name: w("runs.reviewWorkflow"), exact: true })).toHaveAttribute("href", `/workflows/${WORKFLOW_ID}`);
        await expect(dialog.getByText(w("runs.legacyReference", { run: 12 }), { exact: true })).toBeVisible();
        await expect(dialog.getByText(w("versionShort", { number: 2 }), { exact: true })).toBeVisible();
        await expect(dialog.getByText("private-debug-payload")).toHaveCount(0);
        const target = dialog.getByRole("link", { name: company.name, exact: true });
        await expect(target).toHaveAttribute("href", `/records/companies/${company.id}`);
        const documentTarget = dialog.getByRole("link", { name: "Workflow quote", exact: true });
        await expect(documentTarget).toHaveAttribute("href", dealDocumentsHref(deal.id));
        await expect(dialog.getByText(w("runs.documentTargetUnavailable"), { exact: true })).toHaveCount(2);
        expect(documentRequests).toEqual([["61", "62", "63"]]);
        await documentTarget.click();
        await expect(page).toHaveURL(new RegExp(`/records/deals/${deal.id}#deal-documents$`));
        await expect(page.locator("section#deal-documents")).toBeInViewport();
    });
}
