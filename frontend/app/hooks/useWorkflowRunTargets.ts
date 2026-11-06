"use client";

import { useEffect, useState } from "react";

import { getCompanyById, getContactById, getDealSummary, getTaskById } from "@/app/lib/api";
import { TASK_URL_KEY } from "@/app/hooks/listStateUrl";
import type { WorkflowRunSummary } from "@/app/lib/types";

type Target = { name: string; href: string };

/** Resolves history targets through record APIs that enforce the viewer's current access. */
export function useWorkflowRunTargets(runs: WorkflowRunSummary[], workspaceId: number) {
    const [targets, setTargets] = useState<{ workspaceId: number; runs: WorkflowRunSummary[]; values: Map<string, Target> } | null>(null);
    useEffect(() => {
        const controller = new AbortController();
        const values = new Map<string, Target>();
        const unique = new Map(runs.map((run) => [runTargetKey(run), run.trigger]));
        const init = { signal: controller.signal, headers: { "X-Workspace-Id": String(workspaceId) } };
        void Promise.all([...unique].map(async ([key, trigger]) => {
            if (trigger?.recordId == null) return;
            const id = trigger.recordId;
            const request = trigger.recordType === "person" ? getContactById(id, init)
                : trigger.recordType === "company" ? getCompanyById(id, init)
                    : trigger.recordType === "deal" ? getDealSummary(id, init)
                        : trigger.recordType === "task" ? getTaskById(id, init).then((task) => ({ name: task.description })) : null;
            if (!request) return;
            try {
                const record = await request;
                const kind = trigger.recordType === "person" ? "contacts"
                    : trigger.recordType === "company" ? "companies" : "deals";
                if (record.name.trim()) values.set(key, {
                    name: record.name,
                    href: trigger.recordType === "task" ? `/activity/tasks?${TASK_URL_KEY}=${id}` : `/records/${kind}/${id}`,
                });
            } catch {
                values.delete(key);
            }
        })).then(() => {
            if (!controller.signal.aborted) setTargets({ workspaceId, runs, values });
        });
        return () => controller.abort();
    }, [runs, workspaceId]);
    const ready = targets?.workspaceId === workspaceId && targets.runs === runs;
    return { values: ready ? targets.values : new Map<string, Target>(), loading: !ready };
}

/** Identifies a target within the workspace, without using a display label as identity. */
export function runTargetKey(run: WorkflowRunSummary) {
    return `${run.trigger?.recordType}:${run.trigger?.recordId}`;
}
