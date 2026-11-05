/** @vitest-environment jsdom */
import { act } from "react";
import { createRoot } from "react-dom/client";
import { describe, expect, it, vi } from "vitest";

import WorkflowRunOperationsDetail from "@/app/components/settings/workflows/operations/WorkflowRunOperationsDetail";
import type { WorkflowOperationsDetail, WorkflowRunDetail } from "@/app/lib/types";

const api = vi.hoisted(() => ({ operations: vi.fn(), pause: vi.fn(), run: vi.fn() }));

vi.mock("next-intl", () => ({
    useLocale: () => "en",
    useTranslations: () => (key: string) => key,
}));
vi.mock("@/app/hooks/useActions", () => ({ useActions: () => ({ openOverlay: vi.fn() }) }));
vi.mock("@/app/hooks/useWorkspace", () => ({
    useWorkspace: () => ({ activeWorkspaceId: 1, switching: false }),
}));
vi.mock("@/app/hooks/useNavTrail", () => ({ CrumbLabel: () => null }));
vi.mock("@/app/lib/api", async (importOriginal) => ({
    ...await importOriginal<typeof import("@/app/lib/api")>(),
    getWorkflowOperations: api.operations,
    getWorkflowRun: api.run,
    getUsers: async () => [],
    pauseWorkflow: api.pause,
}));

const RUN: WorkflowRunDetail = {
    workflowId: 1, runKey: "canonical-1", source: "canonical", status: "succeeded",
    legacyStatus: null, version: null, trigger: null, runtimeState: null,
    startedAt: "2026-11-04T08:00:00Z", finishedAt: null, durationMs: null,
    failure: null, stepDetailAvailable: false, execution: null, path: [],
};
const OPERATIONS: WorkflowOperationsDetail = {
    recipeKey: null,
    workflow: { id: 1, name: "Active workflow", enabled: true, runtimeOwner: "canonical" },
    health: { state: "healthy", signals: [] }, activeVersion: null,
    recentDefinitionChanges: [], openInterventions: [],
    backlog: {
        queuedCount: 0, oldestQueuedAt: null, waitingCount: 0, dueNowCount: 0,
        overdueCount: 0, nextResumeAt: null, recentFailureCount: 0,
    },
};

describe("workflow operations with an omitted archive timestamp", () => {
    it.each([
        { archivedAt: undefined, paused: false },
        { archivedAt: undefined, paused: true },
        { archivedAt: null, paused: false },
        { archivedAt: null, paused: true },
        { archivedAt: "2026-11-01T00:00:00Z", paused: false },
        { archivedAt: "2026-11-01T00:00:00Z", paused: true },
    ])("enables controls only for active workflows ($archivedAt, paused=$paused)", async ({ archivedAt, paused }) => {
        vi.stubGlobal("IS_REACT_ACT_ENVIRONMENT", true);
        api.run.mockResolvedValue(RUN);
        api.operations.mockResolvedValue({
            ...OPERATIONS,
            workflow: {
                ...OPERATIONS.workflow,
                ...(archivedAt === undefined ? {} : { archivedAt }),
                ...(paused ? { intakePausedAt: "2026-11-01T00:00:00Z" } : {}),
            },
        });
        const container = document.createElement("div");
        document.body.append(container);
        const root = createRoot(container);
        try {
            await act(async () => root.render(<WorkflowRunOperationsDetail workflowId={1} runKey="canonical-1" />));
            for (const label of [paused ? "controls.resume" : "controls.pause", "controls.disable", "detail.newRun"]) {
                const button = Array.from(container.querySelectorAll("button"))
                    .find((candidate) => candidate.textContent?.trim() === label);
                expect(button, label).toBeDefined();
                expect(button?.disabled, label).toBe(archivedAt != null);
            }
        } finally {
            await act(async () => root.unmount());
            container.remove();
            vi.unstubAllGlobals();
        }
    });
});
