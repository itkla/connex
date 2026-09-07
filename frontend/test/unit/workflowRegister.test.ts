import { describe, expect, it } from "vitest";

import { filterWorkflowRegister } from "@/app/components/settings/workflows/workflowRegister";
import type { WorkflowListItem } from "@/app/lib/types";

function workflow(id: number, overrides: Partial<WorkflowListItem> = {}): WorkflowListItem {
    return {
        id,
        name: "Account review",
        description: "Follow up with customers before renewal",
        recordType: "company",
        enabled: true,
        runtimeOwner: "canonical",
        archivedAt: null,
        intakePausedAt: null,
        intakePausedById: null,
        draftRevision: 1,
        executionMode: "user",
        runAsUserId: 1,
        createdById: 1,
        updatedById: 1,
        createdAt: "2026-09-07T00:00:00Z",
        updatedAt: "2026-09-07T00:00:00Z",
        activeVersion: { id: 1, number: 1, publishedAt: "2026-09-07T00:00:00Z" },
        nodeCount: 3,
        actionCount: 1,
        latestRun: null,
        ...overrides,
    };
}

describe("workflow register discovery", () => {
    it("combines purpose search with primary record and lifecycle filters", () => {
        const workflows = [
            workflow(1),
            workflow(2, { recordType: "person" }),
            workflow(3, { intakePausedAt: "2026-09-07T01:00:00Z" }),
            workflow(4, { description: null, name: "New contact routing" }),
            workflow(5, { activeVersion: null }),
        ];
        expect(filterWorkflowRegister(workflows, {
            query: " RENEWAL ", recordType: "company", state: "enabled",
        }).map(({ id }) => id)).toEqual([1]);
    });

    it("distinguishes actionable failure from expected skips and waiting work", () => {
        const latestRun: NonNullable<WorkflowListItem["latestRun"]> = {
            runKey: "canonical-1", source: "canonical", status: "failed", legacyStatus: null,
            startedAt: "2026-09-07T00:00:00Z", finishedAt: null, stepDetailAvailable: true,
        };
        const workflows = [
            workflow(1, { latestRun }),
            workflow(2, { latestRun: { ...latestRun, status: "waiting" } }),
            workflow(3, { latestRun: { ...latestRun, status: "skipped" } }),
            workflow(4, { latestRun: { ...latestRun, status: "intervention_required" } }),
        ];
        expect(filterWorkflowRegister(workflows, {
            query: "", recordType: "all", state: "attention",
        }).map(({ id }) => id)).toEqual([1, 4]);
    });

    it("finds Japanese purposes and keeps draft-only workflows discoverable", () => {
        const workflows = [workflow(1, { activeVersion: null, description: "更新前に担当者が対応" }), workflow(2)];
        expect(filterWorkflowRegister(workflows, {
            query: "更新", recordType: "all", state: "draft",
        }).map(({ id }) => id)).toEqual([1]);
    });
});
