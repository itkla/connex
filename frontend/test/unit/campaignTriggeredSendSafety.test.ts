import { readFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";

const DELIVERY_PANEL = "app/components/marketing/campaigns/CampaignDelivery.tsx";
const ENGAGEMENT = "app/components/marketing/campaigns/CampaignEngagement.tsx";
const STATUS_BADGE = "app/components/marketing/campaigns/SendStatusBadge.tsx";
const WORKFLOW_EDITOR = "app/components/settings/workflows/WorkflowEditor.tsx";
const WORKFLOW_RUNS = "app/components/settings/workflows/WorkflowRunsDialog.tsx";
const TYPES = "app/lib/types.ts";

function source(relativePath: string): string {
    return readFileSync(path.resolve(process.cwd(), relativePath), "utf8");
}

type MessageValue = string | { [key: string]: MessageValue };

function isMessageTree(value: unknown): value is { [key: string]: MessageValue } {
    return typeof value === "object" && value !== null && !Array.isArray(value);
}

describe("workflow-managed campaign sends remain read-only on the campaign surface", () => {
    it("models their rollback-readable snapshot and closed origin vocabulary", () => {
        const types = source(TYPES);
        const send = types.slice(
            types.indexOf("export type CampaignSend ="),
            types.indexOf("};", types.indexOf("export type CampaignSend =")),
        );

        expect(send).toContain("snapshotId: number;");
        expect(send).toContain('origin: "audience" | "triggered"');
    });

    it("limits queue, pause, and cancel eligibility to audience sends", () => {
        const panel = source(DELIVERY_PANEL);

        expect(panel).toContain('const isAudienceSend = send.origin === "audience"');
        expect(panel).toContain("const canQueue = isAudienceSend");
        expect(panel).toContain("const canPause = isAudienceSend");
        expect(panel).toContain("isAudienceSend && (");
    });

    it("labels the managed origin in both locales", () => {
        for (const locale of ["en", "ja"] as const) {
            const parsed: unknown = JSON.parse(source(`messages/${locale}/campaigns.json`));
            if (!isMessageTree(parsed) || !isMessageTree(parsed.CampaignSends)) {
                throw new Error(`messages/${locale}/campaigns.json has no CampaignSends namespace`);
            }
            expect(parsed.CampaignSends.workflowOrigin).toBeTruthy();
            expect(parsed.CampaignSends.status).toBeTruthy();
            if (!isMessageTree(parsed.CampaignSends.status)) {
                throw new Error(`messages/${locale}/campaigns.json has no CampaignSends.status namespace`);
            }
            expect(parsed.CampaignSends.status.triggered).toBeTruthy();
        }
    });

    it("recognizes the triggered token on every current send-status surface", () => {
        expect(source(ENGAGEMENT)).toContain('"triggered",');
        expect(source(STATUS_BADGE)).toContain("triggered:");
    });

    it("keeps database identifiers and diagnostic codes out of visible workflow history copy", () => {
        const runs = source(WORKFLOW_RUNS);
        const editor = source(WORKFLOW_EDITOR);
        expect(runs).not.toContain("id: step.actionReferenceId");
        expect(editor).toContain('t("diagnosticFallback")');
        expect(editor).toContain("title={diagnostic.code}");
        expect(editor).not.toContain(": diagnostic.code, [t]");
        for (const locale of ["en", "ja"] as const) {
            const parsed: unknown = JSON.parse(source(`messages/${locale}/workspace.json`));
            if (!isMessageTree(parsed) || !isMessageTree(parsed.WorkspaceWorkflows)) {
                throw new Error(`messages/${locale}/workspace.json has no WorkspaceWorkflows namespace`);
            }
            const workflows = parsed.WorkspaceWorkflows;
            if (!isMessageTree(workflows.runs) || !isMessageTree(workflows.runs.actionOutcome)
                    || typeof workflows.diagnosticFallback !== "string") {
                throw new Error(`messages/${locale}/workspace.json is missing workflow safety copy`);
            }
            expect(workflows.diagnosticFallback).toBeTruthy();
            expect(workflows.runs.actionOutcome.delivery_reconciliation_required).toBeTruthy();
            expect(Object.values(workflows.runs.actionOutcome).join(" ")).not.toContain("#{id}");
        }
    });
});
