/** @vitest-environment jsdom */
import { act, type ComponentProps } from "react";
import { createRoot, type Root } from "react-dom/client";
import { NextIntlClientProvider } from "next-intl";
import { afterEach, describe, expect, it, vi } from "vitest";

import WorkflowRunsDialog from "@/app/components/settings/workflows/WorkflowRunsDialog";
import { getCompanyById, getGeneratedDocumentTargets, getTaskById, getWorkflowRuns } from "@/app/lib/api";
import type { WorkflowRunSummary } from "@/app/lib/types";
import enWorkspace from "@/messages/en/workspace.json";
import jaWorkspace from "@/messages/ja/workspace.json";
import enOperations from "@/messages/en/workflow-operations.json";
import jaOperations from "@/messages/ja/workflow-operations.json";

vi.mock("@/app/lib/api", () => ({
    getWorkflowRuns: vi.fn(), getWorkflowRun: vi.fn(),
    getCompanyById: vi.fn(), getContactById: vi.fn(), getDealSummary: vi.fn(), getTaskById: vi.fn(),
    getGeneratedDocumentTargets: vi.fn(),
}));

globalThis.IS_REACT_ACT_ENVIRONMENT = true;
let root: Root | null = null;
afterEach(async () => {
    await act(async () => root?.unmount());
    root = null;
    document.body.replaceChildren();
    vi.clearAllMocks();
});

const run: WorkflowRunSummary = {
    runKey: "legacy-12", source: "legacy", status: "failed", legacyStatus: "failed",
    version: { id: 3, number: 2, definitionHash: "hash", publishedAt: "2026-11-05T00:00:00" },
    trigger: { type: "entity_change", event: "company.updated", recordType: "company", recordId: 41 },
    runtimeState: null, startedAt: "2026-11-05T01:00:00", finishedAt: "2026-11-05T01:00:00", durationMs: 0,
    failure: { nodeId: null, code: "action_tag_unavailable", message: "secret=do-not-render", actionNumber: 1, actionType: "add_tag" },
    stepDetailAvailable: false,
};

async function renderHistory(locale: string, messages: ComponentProps<typeof NextIntlClientProvider>["messages"]) {
    const container = document.createElement("div");
    document.body.appendChild(container);
    root = createRoot(container);
    await act(async () => {
        root?.render(
            <NextIntlClientProvider locale={locale} messages={messages} timeZone="UTC">
                <WorkflowRunsDialog open onOpenChange={vi.fn()} workflowId={11} workflowName="Tag new companies" workspaceId={7} />
            </NextIntlClientProvider>,
        );
    });
}

describe("workflow history failure recovery", () => {
    for (const [locale, workspace, operations] of [
        ["en", enWorkspace, enOperations], ["ja", jaWorkspace, jaOperations],
    ] as const) {
        it(`${locale}: links visible documents and gives hidden and deleted documents the same neutral label`, async () => {
            vi.mocked(getWorkflowRuns).mockResolvedValue({ items: [61, 62, 63].map((id) => ({
                ...run, runKey: `legacy-${id}`,
                trigger: { type: "entity_change", event: "document.finalized", recordType: "document", recordId: id },
            })), nextCursor: null });
            vi.mocked(getGeneratedDocumentTargets).mockResolvedValue([{ id: 61, dealId: 41, title: "Visible quote" }]);
            await renderHistory(locale, { ...workspace, ...operations });
            const rows = document.querySelectorAll("ol > li");
            expect(rows).toHaveLength(3);
            expect(rows[0]?.querySelector('a[href="/records/deals/41#deal-documents"]')?.textContent).toBe("Visible quote");
            for (const row of [rows[1], rows[2]]) {
                expect(row?.textContent).toContain(workspace.WorkspaceWorkflows.runs.documentTargetUnavailable);
                expect(row?.querySelector('a[href^="/records/deals/"]')).toBeNull();
            }
            expect(document.body.textContent).not.toContain(workspace.WorkspaceWorkflows.runs.targetNameNotRecorded);
        });

        it(`${locale}: shows the failed action, safe reason, recovery and a named permitted target`, async () => {
            vi.mocked(getWorkflowRuns).mockResolvedValue({ items: [run], nextCursor: null });
            vi.mocked(getCompanyById).mockResolvedValue({ id: 41, name: "Example Company", website: "", industry: "", phone: "", address: "", logoUrl: "", createdAt: run.startedAt, updatedAt: run.startedAt });
            await renderHistory(locale, { ...workspace, ...operations });
            const text = document.body.textContent ?? "";
            const w = workspace.WorkspaceWorkflows;
            expect(text).toContain(w.runs.status.failed);
            expect(text).toContain(w.diagnostics.action_tag_unavailable);
            expect(text).toContain(workspace.WorkflowAuthoring.action.add_tag);
            expect(text).toContain(w.runs.failureRecovery);
            expect(text).not.toContain("secret=do-not-render");
            expect(text).not.toContain(w.runs.legacyVersion);
            expect(text).toContain(w.runs.legacyReference.replace("{run}", "12"));
            expect(document.querySelector('a[href="/records/companies/41"]')?.textContent).toBe("Example Company");
            expect(document.querySelector('a[href="/workflows/11"]')?.textContent).toBe(w.runs.reviewWorkflow);
            expect(getCompanyById).toHaveBeenCalledWith(41, expect.objectContaining({ headers: { "X-Workspace-Id": "7" } }));
        });
    }

    it("uses the run-specific member explanation for an unavailable actor", async () => {
        vi.mocked(getWorkflowRuns).mockResolvedValue({ items: [{ ...run, trigger: null, failure: { nodeId: null, code: "actor_unavailable", message: "private-actor-payload" } }], nextCursor: null });
        await renderHistory("en", { ...enWorkspace, ...enOperations });
        expect(document.body.textContent).toContain(enOperations.WorkflowOperations.reason.actor_unavailable);
        expect(document.body.textContent).not.toContain("private-actor-payload");
    });

    it("links task targets through the established task deep link", async () => {
        vi.mocked(getWorkflowRuns).mockResolvedValue({ items: [{ ...run, trigger: { type: "entity_change", event: "task.completed", recordType: "task", recordId: 51 } }], nextCursor: null });
        vi.mocked(getTaskById).mockResolvedValue({ id: 51, description: "Follow up", completed: true, status: "done", position: 0, assignedToId: 1, createdAt: run.startedAt, updatedAt: run.startedAt });
        await renderHistory("en", { ...enWorkspace, ...enOperations });
        expect(document.querySelector('a[href="/activity/tasks?task=51"]')?.textContent).toBe("Follow up");
    });

    it("does not claim an unsupported target was deleted when its name cannot be resolved here", async () => {
        vi.mocked(getWorkflowRuns).mockResolvedValue({ items: [{ ...run, trigger: { type: "entity_change", event: "activity.created", recordType: "activity", recordId: 61 } }], nextCursor: null });
        await renderHistory("en", { ...enWorkspace, ...enOperations });
        expect(document.body.textContent).toContain(enWorkspace.WorkspaceWorkflows.runs.targetNameNotRecorded);
        expect(document.body.textContent).not.toContain(enWorkspace.WorkspaceWorkflows.runs.targetUnavailable);
        expect(getCompanyById).not.toHaveBeenCalled();
    });

    it("keeps inaccessible targets unnamed and unlinked, and old failure evidence honest", async () => {
        vi.mocked(getWorkflowRuns).mockResolvedValue({ items: [{ ...run, version: null, failure: { nodeId: null, code: "legacy_failed", message: "private payload" } }], nextCursor: null });
        vi.mocked(getCompanyById).mockRejectedValue(new Error("not visible"));
        await renderHistory("en", { ...enWorkspace, ...enOperations });
        expect(document.querySelector('a[href="/records/companies/41"]')).toBeNull();
        expect(document.body.textContent).toContain(enWorkspace.WorkspaceWorkflows.runs.targetUnavailable);
        expect(document.body.textContent).toContain(enWorkspace.WorkspaceWorkflows.runs.failureUnknown);
        expect(document.body.textContent).toContain(enWorkspace.WorkspaceWorkflows.runs.legacyVersion);
        expect(document.body.textContent).not.toContain("private payload");
        expect(document.body.textContent).not.toContain("company #41");
    });
});
