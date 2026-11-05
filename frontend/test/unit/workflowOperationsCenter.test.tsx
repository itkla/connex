/** @vitest-environment jsdom */
import { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { NextIntlClientProvider } from "next-intl";
import { afterEach, describe, expect, it, vi } from "vitest";

import WorkflowOperationsCenter from "@/app/components/settings/workflows/operations/WorkflowOperationsCenter";
import { getWorkflowOperationsRuns, getWorkflowOperationsSummary } from "@/app/lib/api";
import type { WorkflowOperationsRunItem, WorkflowOperationsSummary, WorkflowRunSummary } from "@/app/lib/types";
import enOperations from "@/messages/en/workflow-operations.json";
import enWorkspace from "@/messages/en/workspace.json";
import jaOperations from "@/messages/ja/workflow-operations.json";
import jaWorkspace from "@/messages/ja/workspace.json";

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

const catalogues = {
    en: { operations: enOperations, workspace: enWorkspace },
    ja: { operations: jaOperations, workspace: jaWorkspace },
};

async function mount(overrides: Partial<WorkflowOperationsSummary> = {}, locale: keyof typeof catalogues = "en") {
    vi.mocked(getWorkflowOperationsSummary).mockResolvedValue({ ...summary, ...overrides });
    const container = document.createElement("div");
    document.body.appendChild(container);
    root = createRoot(container);
    await act(async () => {
        root?.render(
            <NextIntlClientProvider locale={locale} messages={{ ...catalogues[locale].operations, ...catalogues[locale].workspace }} timeZone="UTC">
                <WorkflowOperationsCenter />
            </NextIntlClientProvider>,
        );
    });
}

function recordedRun(source: WorkflowRunSummary["source"], version: WorkflowRunSummary["version"]): WorkflowOperationsRunItem {
    return {
        workflowId: 3,
        workflowName: "Deal handoff",
        recipeKey: null,
        failureCategory: null,
        intervention: null,
        run: {
            runKey: `${source}-41`,
            source,
            status: "succeeded",
            legacyStatus: source === "legacy" ? "matched" : null,
            version,
            trigger: null,
            runtimeState: null,
            startedAt: "2026-11-04T12:00:00",
            finishedAt: "2026-11-04T12:00:01",
            durationMs: 1000,
            failure: null,
            stepDetailAvailable: false,
        },
    };
}

describe("workflow operations current evidence", () => {
    for (const locale of ["en", "ja"] as const) {
        for (const source of ["legacy", "canonical"] as const) {
            for (const knownVersion of [false, true]) {
                it(`${locale}: ${source} run references are neutral with ${knownVersion ? "a known" : "no known"} version`, async () => {
                    const version = knownVersion ? {
                        id: 7,
                        number: 3,
                        definitionHash: "a".repeat(64),
                        publishedAt: "2026-11-04T11:00:00",
                    } : null;
                    vi.mocked(getWorkflowOperationsRuns).mockResolvedValue({
                        items: [recordedRun(source, version)],
                        nextCursor: null,
                    });
                    await mount({}, locale);
                    const messages = catalogues[locale].operations.WorkflowOperations.runs;
                    const expected = (knownVersion ? messages.runMeta : messages.runReference)
                        .replace("{run}", "41")
                        .replace("{version}", "3");
                    const row = document.querySelector(`a[href="/workflows/3/runs/${source}-41"]`);
                    expect(row?.querySelector("p")?.textContent).toBe(expected);
                });
            }
        }
    }

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
