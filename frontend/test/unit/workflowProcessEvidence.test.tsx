import { type ReactNode } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { NextIntlClientProvider } from "next-intl";
import { describe, expect, it } from "vitest";

import { WorkflowSimulationEvidence } from "@/app/components/settings/workflows/WorkflowSimulationDialog";
import WorkflowWaitEvidence from "@/app/components/settings/workflows/WorkflowWaitEvidence";
import type { WorkflowDefinition, WorkflowSimulation, WorkflowStepRun } from "@/app/lib/types";
import messages from "../../messages/en/workspace.json";

function render(children: ReactNode): string {
    return renderToStaticMarkup(<NextIntlClientProvider locale="en" timeZone="UTC" messages={messages}>{children}</NextIntlClientProvider>);
}

const definition: WorkflowDefinition = {
    schemaVersion: 2, entryNodeId: "start",
    nodes: [
        { id: "start", type: "TRIGGER", config: { type: "manual" } },
        { id: "wait", type: "WAIT", config: { kind: "event", event: "task.completed", source: { nodeId: "task", output: "taskId" }, timeoutSeconds: 600 } },
        { id: "next", type: "ACTION", config: { type: "log_activity" } },
        { id: "stop", type: "END", config: { outcome: "stopped" } },
    ],
    edges: [
        { id: "complete", sourceNodeId: "wait", outcome: "completed", targetNodeId: "next" },
        { id: "timeout", sourceNodeId: "wait", outcome: "timeout", targetNodeId: "stop" },
    ],
};

const simulation: WorkflowSimulation = {
    result: "would_wait", blockers: [],
    path: [{ nodeId: "wait", nodeType: "wait", status: "waiting", outcome: null, actionType: null, code: "delay_wait" }],
};

const step: WorkflowStepRun = {
    sequence: 2, nodeId: "wait", nodeType: "wait", status: "waiting", attempts: 1,
    retrySafety: "none", selectedOutcome: null, selectedEdgeId: null, nextNodeId: null,
    actionOutcome: null, actionReferenceId: null, startedAt: "2026-09-07T01:00:00", finishedAt: null, durationMs: null, failure: null,
    wait: { kind: "event", event: "task.completed", sourceNodeId: "task", sourceOutput: "taskId", sourceTaskId: 812, timeoutAt: "2026-09-07T01:10:00", resolution: null, matchedEventId: null, resolvedAt: null },
};

describe("workflow wait evidence", () => {
    it("shows both possible simulation exits without claiming a task completion or rendering a made-up task link", () => {
        const html = render(<WorkflowSimulationEvidence definition={definition} result={simulation} diagnosticMessage={() => null} />);
        expect(html).toContain("Task completion is still pending");
        expect(html).toContain("Completed → log an activity");
        expect(html).toContain("Timed out → Stopped");
        expect(html).not.toContain("Task completion observed");
        expect(html).not.toContain("/activity/tasks?task=");
    });

    it("renders a task link and observed completion only from persisted wait evidence", () => {
        const pending = render(<WorkflowWaitEvidence step={step} />);
        expect(pending).toContain("Waiting for the task to be completed");
        expect(pending).toContain('href="/activity/tasks?task=812"');
        expect(pending).not.toContain("Task completion observed");
        if (!step.wait) throw new Error("Wait evidence missing");
        const completed = render(<WorkflowWaitEvidence step={{ ...step, wait: { ...step.wait, resolution: "completed", matchedEventId: 992, resolvedAt: "2026-09-07T01:05:00" } }} />);
        expect(completed).toContain("Task completion observed");
        expect(completed).toContain("Resolved:");
        expect(render(<WorkflowWaitEvidence step={{ ...step, wait: null }} />)).toBe("");
    });
});
