import { expect, test, type Page, type Route } from "@playwright/test";

import type {
    WorkflowDto,
    WorkflowListItem,
    WorkflowOperationsRunItem,
    WorkflowOperationsSummary,
    WorkflowRecipe,
    WorkflowRecipePreview,
    WorkflowValidation,
} from "@/app/lib/types";
import { runFixture } from "./support/fixtures";
import { useLocale } from "./support/locale";
import { message } from "./support/messages";

const WORKFLOW_ID = 990003;
const CREATED_AT = "2026-11-04T12:00:00Z";

function json(route: Route, value: unknown) {
    return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(value) });
}

/** Keeps authentication real while controlling the workflow service responses under review. */
async function workflowResponses(page: Page) {
    const workflow: WorkflowDto = {
        id: WORKFLOW_ID,
        name: "Unavailable workflow",
        description: null,
        enabled: true,
        runtimeOwner: "canonical",
        executionAvailable: false,
        executionUnavailableReason: "execution_unavailable",
        archivedAt: null,
        intakePausedAt: null,
        intakePausedById: null,
        draftRevision: 0,
        recordType: "deal",
        executionMode: "user",
        runAsUserId: null,
        definition: {
            schemaVersion: 1,
            entryNodeId: "trigger",
            nodes: [
                { id: "trigger", type: "TRIGGER", config: { type: "entity_change", events: ["deal.won"] } },
                { id: "end", type: "END" },
            ],
            edges: [{ id: "next", sourceNodeId: "trigger", targetNodeId: "end", outcome: "next" }],
        },
        canvas: { positions: { trigger: { x: 0, y: 0 }, end: { x: 320, y: 0 } }, viewport: { x: 0, y: 0, zoom: 1 } },
        activeVersionId: 1,
        createdById: null,
        updatedById: null,
        createdAt: CREATED_AT,
        updatedAt: CREATED_AT,
    };
    const validation: WorkflowValidation = {
        draftRevision: 0,
        valid: true,
        canPublish: false,
        systemAuthoringAllowed: true,
        requiredPermissions: [],
        missingPermissions: [],
        errors: [{ code: "legacy_projection_unsupported", nodeId: null, edgeId: null, fieldPath: null, params: { reason: "multiple_conditions" } }],
    };
    const summary: WorkflowOperationsSummary = {
        workflowCount: 1,
        healthyCount: 0,
        unavailableCount: 1,
        pausedCount: 0,
        disabledCount: 0,
        interventionRequiredCount: 0,
        queuedCount: 0,
        waitingCount: 0,
        overdueCount: 0,
        openInterventionCount: 0,
        recentFailureCount: 0,
        triggerDiagnostics: [],
    };
    const state: { runs: WorkflowOperationsRunItem[]; installCalls: number } = { runs: [], installCalls: 0 };
    const recipe: WorkflowRecipe = {
        recipeKey: "cooling-company-review",
        recipeVersion: 1,
        schemaVersion: 1,
        titleKey: "cooling-company-review",
        descriptionKey: "cooling-company-review",
        sourceEvent: "schedule.daily",
        actorModel: "user",
        dataRead: ["company"],
        dataWritten: ["task"],
        requiredParameters: [],
        requiredPermissions: ["TASK_CREATE"],
        lockedFields: [],
        editableFields: [],
        sideEffects: ["create_task"],
        actions: [{ actionType: "create_task", retrySafety: "transactional" }],
        disableBehavior: "disable",
        removeBehavior: "archive",
    };
    const preview: WorkflowRecipePreview = {
        recipe,
        previewHash: "review",
        definition: workflow.definition,
        canvas: workflow.canvas,
        unresolvedParameters: [],
        validation: {
            ...validation,
            valid: false,
            errors: [{ code: "action_record_type_unsupported", nodeId: null, edgeId: null, fieldPath: null, params: {} }],
        },
        plannedActions: [{ nodeId: "task", actionType: "create_task", retrySafety: "transactional" }],
        exampleResult: null,
        writesCreated: false,
    };
    await page.route(/\/api\/workflows(?:\?.*)?$/, (route) => {
        const item: WorkflowListItem = {
            ...workflow,
            description: workflow.description ?? null,
            executionUnavailableReason: workflow.executionUnavailableReason ?? null,
            archivedAt: workflow.archivedAt ?? null,
            intakePausedAt: workflow.intakePausedAt ?? null,
            intakePausedById: workflow.intakePausedById ?? null,
            recordType: workflow.recordType ?? null,
            runAsUserId: workflow.runAsUserId ?? null,
            createdById: workflow.createdById ?? null,
            updatedById: workflow.updatedById ?? null,
            activeVersion: { id: 1, number: 1, publishedAt: CREATED_AT },
            nodeCount: 2,
            actionCount: 0,
            latestRun: null,
        };
        return json(route, [item]);
    });
    await page.route(`**/api/workflows/${WORKFLOW_ID}`, (route) => json(route, workflow));
    await page.route(`**/api/workflows/${WORKFLOW_ID}/versions`, (route) => json(route, []));
    await page.route(`**/api/workflows/${WORKFLOW_ID}/validate`, (route) => json(route, validation));
    await page.route("**/api/workflow-operations/summary", (route) => json(route, summary));
    await page.route(/\/api\/workflow-operations\/runs(?:\?.*)?$/, (route) => {
        const filtered = new URL(route.request().url()).searchParams.has("status");
        return json(route, { items: filtered ? [] : state.runs, nextCursor: null });
    });
    await page.route("**/api/workflow-recipes/cooling-company-review", (route) => json(route, recipe));
    await page.route("**/api/workflow-recipes/cooling-company-review/preview", (route) => json(route, preview));
    await page.route("**/api/workflow-recipes/cooling-company-review/install", (route) => {
        state.installCalls += 1;
        return route.fulfill({ status: 409, contentType: "application/json", body: "{}" });
    });
    return { workflow, summary, state };
}

for (const locale of ["en", "ja"] as const) {
    const w = (key: string) => message(locale, "workspace", `WorkspaceWorkflows.${key}`);
    const o = (key: string) => message(locale, "workflow-operations", `WorkflowOperations.${key}`);

    test(`${locale}: unavailable workflows and publication refusals stay explicit @mobile`, async ({ page }, testInfo) => {
        runFixture(testInfo.project.name);
        await useLocale(page, locale);
        await page.emulateMedia({ colorScheme: testInfo.project.name === "mobile-chromium" ? "dark" : "light" });
        const { workflow } = await workflowResponses(page);
        await page.goto("/workflows");
        await expect(page.getByText(w("executionUnavailable"), { exact: true })).toBeVisible();
        await expect(page.getByText(w("unavailableBadge"), { exact: true })).toBeVisible();
        await page.goto(`/workflows/${WORKFLOW_ID}`);
        await expect(page.getByRole("button", { name: w("disable"), exact: true })).toBeEnabled();
        if (testInfo.project.name === "chromium") {
            await expect(page.getByRole("button", { name: w("saveDraft"), exact: true })).toBeEnabled();
        }
        const validate = page.getByRole("button", { name: w("validate"), exact: true });
        await expect(validate).toBeEnabled();
        await validate.click();
        await expect(page.getByText(w("publicationReason.multiple_conditions"), { exact: true })).toBeVisible();
        await expect(page.getByText(w("validationReady"), { exact: true })).toHaveCount(0);
        await expect(page.getByRole("button", { name: w("publish"), exact: true })).toBeDisabled();
        workflow.enabled = false;
        await page.reload();
        await expect(page.getByText(w("executionUnavailable"), { exact: true })).toBeVisible();
        await expect(page.getByRole("button", { name: w("enable"), exact: true })).toBeDisabled();
    });

    test(`${locale}: Operations distinguishes first runs, filters, and recorded failures @mobile`, async ({ page }, testInfo) => {
        runFixture(testInfo.project.name);
        await useLocale(page, locale);
        const { state, summary } = await workflowResponses(page);
        await page.goto("/workflows/operations");
        await expect(page.getByRole("heading", { name: o("runs.firstRunTitle"), exact: true })).toBeVisible();
        await expect(page.getByText(o("summary.unavailableBody"), { exact: true })).toBeVisible();
        await page.getByRole("combobox", { name: o("filters.statusLabel"), exact: true }).click();
        await page.getByRole("option", { name: w("runs.status.failed"), exact: true }).click();
        await expect(page.getByRole("heading", { name: o("runs.emptyTitle"), exact: true })).toBeVisible();
        await page.getByRole("button", { name: o("filters.clear"), exact: true }).click();
        await expect(page.getByRole("heading", { name: o("runs.firstRunTitle"), exact: true })).toBeVisible();
        summary.workflowCount = 2;
        summary.interventionRequiredCount = 1;
        summary.recentFailureCount = 1;
        state.runs = ["partial", "running"].map<WorkflowOperationsRunItem>((status, index) => ({
            workflowId: WORKFLOW_ID,
            workflowName: index === 0 ? "Recorded handoff" : "Running handoff",
            recipeKey: null,
            failureCategory: status === "partial" ? "execution" : null,
            intervention: null,
            run: {
                runKey: `legacy-${index + 1}`,
                source: "legacy",
                status: status === "partial" ? "partial" : "running",
                legacyStatus: status === "partial" ? "partial" : "running",
                version: null,
                trigger: null,
                runtimeState: null,
                startedAt: CREATED_AT,
                finishedAt: status === "partial" ? CREATED_AT : null,
                durationMs: null,
                failure: null,
                stepDetailAvailable: false,
            },
        }));
        await page.reload();
        await expect(page.getByText("Recorded handoff", { exact: true })).toBeVisible();
        await expect(page.getByText(w("runs.legacyPartial"), { exact: true })).toBeVisible();
        await expect(page.getByText(w("runs.status.running"), { exact: true })).toBeVisible();
        for (const runNumber of [1, 2]) {
            const run = page.locator(`a[href="/workflows/${WORKFLOW_ID}/runs/legacy-${runNumber}"]`);
            await expect(run.locator("p").first()).toHaveText(o("runs.runReference").replace("{run}", String(runNumber)));
        }
        await expect(page.getByText(o("summary.attention"), { exact: true }).locator("..").locator("dd")).toHaveText("1");
    });

    test(`${locale}: an unsupported company recipe explains why it cannot be installed @mobile`, async ({ page }, testInfo) => {
        runFixture(testInfo.project.name);
        await useLocale(page, locale);
        const { state } = await workflowResponses(page);
        await page.goto("/workflows/recipes/cooling-company-review");
        await page.getByRole("button", { name: o("recipes.preview"), exact: true }).click();
        await expect(page.getByText(o("recipes.coolingUnavailable"), { exact: true })).toBeVisible();
        await expect(page.getByRole("button", { name: o("recipes.install"), exact: true })).toBeDisabled();
        expect(state.installCalls).toBe(0);
    });
}
