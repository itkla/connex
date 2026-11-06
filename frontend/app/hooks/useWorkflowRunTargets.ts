"use client";

import { useEffect, useMemo, useRef, useState } from "react";

import { getCompanyById, getContactById, getDealSummary, getGeneratedDocumentTargets, getTaskById } from "@/app/lib/api";
import { dealDocumentsHref } from "@/app/components/records/deals/dealLinks";
import { TASK_URL_KEY } from "@/app/hooks/listStateUrl";
import type { WorkflowRunSummary } from "@/app/lib/types";

type Target = { name: string; href: string };
type TargetRequest = { key: string; recordType: "person" | "company" | "deal" | "task" | "document"; id: number };
type TargetSnapshot = { workspace: { id: number }; values: Map<string, Target>; settled: Set<string> };
type TargetCache = TargetSnapshot & {
    controller: AbortController;
    requested: Set<string>;
    queue: TargetRequest[][];
    active: number;
};

const TARGET_CONCURRENCY = 4;
const DOCUMENT_BATCH_SIZE = 50;

/** Resolves each target once per workspace through APIs enforcing the viewer's current access. */
export function useWorkflowRunTargets(runs: WorkflowRunSummary[], workspaceId: number) {
    const workspace = useMemo(() => ({ id: workspaceId }), [workspaceId]);
    const cacheRef = useRef<TargetCache | null>(null);
    const [targets, setTargets] = useState<TargetSnapshot | null>(null);

    useEffect(() => {
        const cache: TargetCache = {
            workspace, values: new Map(), settled: new Set(), requested: new Set(),
            queue: [], active: 0, controller: new AbortController(),
        };
        cacheRef.current = cache;
        return () => {
            cache.controller.abort();
            cache.queue.length = 0;
            if (cacheRef.current === cache) cacheRef.current = null;
        };
    }, [workspace]);

    useEffect(() => {
        const cache = cacheRef.current;
        if (cache == null || cache.workspace !== workspace) return;
        const documents: TargetRequest[] = [];
        for (const run of runs) {
            const request = targetRequest(run);
            if (request == null || cache.requested.has(request.key)) continue;
            cache.requested.add(request.key);
            if (request.recordType === "document") documents.push(request);
            else cache.queue.push([request]);
        }
        for (let offset = 0; offset < documents.length; offset += DOCUMENT_BATCH_SIZE) {
            cache.queue.push(documents.slice(offset, offset + DOCUMENT_BATCH_SIZE));
        }
        while (cache.active < TARGET_CONCURRENCY && cache.queue.length > 0) {
            void resolveQueuedTargets(cache, () => setTargets({
                workspace, values: new Map(cache.values), settled: new Set(cache.settled),
            }));
        }
    }, [runs, workspace]);

    const current = targets?.workspace === workspace ? targets : null;
    const pending = new Set(runs.flatMap((run) => {
        const request = targetRequest(run);
        return request != null && !current?.settled.has(request.key) ? [request.key] : [];
    }));
    return { values: current?.values ?? new Map<string, Target>(), pending };
}

function targetRequest(run: WorkflowRunSummary): TargetRequest | null {
    const id = run.trigger?.recordId;
    const recordType = run.trigger?.recordType;
    if (id == null || !Number.isSafeInteger(id) || id <= 0) return null;
    if (recordType !== "person" && recordType !== "company" && recordType !== "deal" && recordType !== "task" && recordType !== "document") return null;
    return { key: runTargetKey(run), recordType, id };
}

async function resolveQueuedTargets(cache: TargetCache, publish: () => void) {
    cache.active += 1;
    try {
        while (!cache.controller.signal.aborted) {
            const requests = cache.queue.shift();
            if (requests == null) return;
            try {
                const resolved = await resolveTargets(requests, {
                    signal: cache.controller.signal,
                    headers: { "X-Workspace-Id": String(cache.workspace.id) },
                });
                if (cache.controller.signal.aborted) return;
                for (const [key, target] of resolved) cache.values.set(key, target);
            } catch {
                if (cache.controller.signal.aborted) return;
            }
            for (const request of requests) cache.settled.add(request.key);
            publish();
        }
    } finally {
        cache.active -= 1;
    }
}

async function resolveTargets(requests: TargetRequest[], init: RequestInit): Promise<Map<string, Target>> {
    const first = requests[0];
    const resolved = new Map<string, Target>();
    if (first == null) return resolved;
    if (first.recordType === "document") {
        const documents = await getGeneratedDocumentTargets(requests.map((request) => request.id), init);
        for (const request of requests) {
            const document = documents.find((item) => item.id === request.id);
            if (document?.title?.trim() && Number.isSafeInteger(document.dealId) && document.dealId > 0) {
                resolved.set(request.key, { name: document.title, href: dealDocumentsHref(document.dealId) });
            }
        }
    } else {
        const target = await resolveTarget(first, init);
        if (target != null) resolved.set(first.key, target);
    }
    return resolved;
}

async function resolveTarget(request: TargetRequest, init: RequestInit): Promise<Target | null> {
    const { recordType, id } = request;
    if (recordType === "task") {
        const task = await getTaskById(id, init);
        return task.description.trim() ? { name: task.description, href: `/activity/tasks?${TASK_URL_KEY}=${id}` } : null;
    }
    const record = recordType === "person" ? await getContactById(id, init)
        : recordType === "company" ? await getCompanyById(id, init) : await getDealSummary(id, init);
    const kind = recordType === "person" ? "contacts" : recordType === "company" ? "companies" : "deals";
    return record.name.trim() ? { name: record.name, href: `/records/${kind}/${id}` } : null;
}

/** Identifies a target within the workspace, without using a display label as identity. */
export function runTargetKey(run: WorkflowRunSummary) {
    return `${run.trigger?.recordType}:${run.trigger?.recordId}`;
}
