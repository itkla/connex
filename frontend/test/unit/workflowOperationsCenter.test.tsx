/** @vitest-environment jsdom */
import { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { NextIntlClientProvider } from "next-intl";
import { afterEach, describe, expect, it, vi } from "vitest";

import WorkflowOperationsCenter from "@/app/components/settings/workflows/operations/WorkflowOperationsCenter";
import { getWorkflowOperationsRuns, getWorkflowOperationsSummary } from "@/app/lib/api";
import type { WorkflowOperationsSummary } from "@/app/lib/types";
import enOperations from "@/messages/en/workflow-operations.json";
import enWorkspace from "@/messages/en/workspace.json";

globalThis.IS_REACT_ACT_ENVIRONMENT = true;

vi.mock("@/app/hooks/useWorkspace", () => ({
    useWorkspace: () => ({ activeWorkspaceId: 1, switching: false }),
}));
vi.mock("@/app/lib/api", () => ({
    ApiError: class extends Error {},
    getWorkflowOperationsRuns: vi.fn(),
    getWorkflowOperationsSummary: vi.fn(),
}));

let root: Root | null = null;
afterEach(async () => {
    await act(async () => root?.unmount());
    root = null;
    document.body.replaceChildren();
    vi.clearAllMocks();
});

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

async function mount(overrides: Partial<WorkflowOperationsSummary> = {}) {
    vi.mocked(getWorkflowOperationsSummary).mockResolvedValue({ ...summary, ...overrides });
    const container = document.createElement("div");
    document.body.appendChild(container);
    root = createRoot(container);
    await act(async () => {
        root?.render(
            <NextIntlClientProvider locale="en" messages={{ ...enOperations, ...enWorkspace }} timeZone="UTC">
                <WorkflowOperationsCenter />
            </NextIntlClientProvider>,
        );
    });
}

describe("workflow operations current evidence", () => {
    it("distinguishes a first-run empty state and shows unavailable workflows", async () => {
        vi.mocked(getWorkflowOperationsRuns).mockResolvedValue({ items: [], nextCursor: null });
        await mount();
        expect(document.body.textContent).toContain(enOperations.WorkflowOperations.runs.firstRunTitle);
        expect(document.body.textContent).not.toContain(enOperations.WorkflowOperations.runs.emptyTitle);
        const unavailable = [...document.querySelectorAll("dt")]
            .find((item) => item.textContent === enOperations.WorkflowOperations.summary.unavailable);
        expect(unavailable?.nextElementSibling?.textContent).toBe("1");
        expect(document.body.textContent).toContain(enOperations.WorkflowOperations.summary.unavailableBody);
        expect(document.querySelector('a[href="/workflows"]')).not.toBeNull();
    });

    it("shows a partly finished recorded run and its attention count", async () => {
        vi.mocked(getWorkflowOperationsRuns).mockResolvedValue({
            items: [{
                workflowId: 3,
                workflowName: "Deal handoff",
                recipeKey: "deal-won-handoff",
                failureCategory: "execution",
                intervention: null,
                run: {
                    runKey: "legacy-4",
                    source: "legacy",
                    status: "partial",
                    legacyStatus: "partial",
                    version: null,
                    trigger: { type: "entity_change", event: "deal.won", recordType: "deal", recordId: 2 },
                    runtimeState: { waitKind: null, resumeAt: null, cancellationRequested: false },
                    startedAt: "2026-11-04T12:00:00",
                    finishedAt: "2026-11-04T12:00:01",
                    durationMs: 1000,
                    failure: { nodeId: null, code: "legacy_partial", message: "" },
                    stepDetailAvailable: false,
                },
            }],
            nextCursor: null,
        });
        await mount({ workflowCount: 2, interventionRequiredCount: 1, recentFailureCount: 1 });
        expect(document.body.textContent).toContain("Deal handoff");
        expect(document.body.textContent).toContain(enWorkspace.WorkspaceWorkflows.runs.legacyPartial);
        expect(document.querySelector('a[href="/workflows/3/runs/legacy-4"]')).not.toBeNull();
        const attention = [...document.querySelectorAll("dt")]
            .find((item) => item.textContent === enOperations.WorkflowOperations.summary.attention);
        expect(attention?.nextElementSibling?.textContent).toBe("1");
    });
});
