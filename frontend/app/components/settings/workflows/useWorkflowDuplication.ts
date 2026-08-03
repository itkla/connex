"use client";

import { useLayoutEffect, useRef, useState } from "react";
import { usePathname, useRouter } from "next/navigation";
import { useTranslations } from "next-intl";

import type { WorkflowListItem } from "@/app/lib/types";
import { createWorkflow, getWorkflowById } from "@/app/lib/api";
import { toastError, toastSuccess } from "@/app/lib/toast";

const MAX_WORKFLOW_NAME_LENGTH = 128;

type DuplicateOperation = {
    controller: AbortController;
    pathname: string;
    workflowId: number;
    systemMode: boolean;
    workspaceId: number;
};

type WorkflowDuplicationOptions = {
    activeWorkspaceId: number | null;
    canRunAsSystem: boolean;
    switching: boolean;
};

function fitDuplicatedWorkflowName(name: string, format: (value: string) => string): string {
    const affixLength = format("").length;
    const baseName = truncateUtf16(name.trim(), Math.max(0, MAX_WORKFLOW_NAME_LENGTH - affixLength)).trimEnd();
    return truncateUtf16(format(baseName), MAX_WORKFLOW_NAME_LENGTH);
}

function truncateUtf16(value: string, maximumLength: number): string {
    let length = 0;
    let result = "";
    for (const character of value) {
        if (length + character.length > maximumLength) break;
        result += character;
        length += character.length;
    }
    return result;
}

/**
 * Manages one workspace-pinned workflow duplication operation and suppresses stale UI effects.
 */
export function useWorkflowDuplication({
    activeWorkspaceId,
    canRunAsSystem,
    switching,
}: WorkflowDuplicationOptions) {
    const tw = useTranslations("WorkspaceWorkflows");
    const router = useRouter();
    const pathname = usePathname();
    const [duplicatingWorkflowId, setDuplicatingWorkflowId] = useState<number | null>(null);
    const duplicateOperationRef = useRef<DuplicateOperation | null>(null);
    const duplicateScopeRef = useRef({
        active: true,
        activeWorkspaceId,
        canRunAsSystem,
        pathname,
        switching,
    });

    useLayoutEffect(() => {
        duplicateScopeRef.current = {
            active: true,
            activeWorkspaceId,
            canRunAsSystem,
            pathname,
            switching,
        };
        const operation = duplicateOperationRef.current;
        if (
            operation !== null
            && (
                switching
                || activeWorkspaceId !== operation.workspaceId
                || pathname !== operation.pathname
                || (operation.systemMode && !canRunAsSystem)
            )
        ) {
            duplicateOperationRef.current = null;
            operation.controller.abort();
            setDuplicatingWorkflowId(null);
        }
    }, [activeWorkspaceId, canRunAsSystem, pathname, switching]);

    useLayoutEffect(() => () => {
        duplicateScopeRef.current = {
            ...duplicateScopeRef.current,
            active: false,
        };
        const operation = duplicateOperationRef.current;
        duplicateOperationRef.current = null;
        operation?.controller.abort();
    }, []);

    const duplicateWorkflow = async (workflow: WorkflowListItem) => {
        const scope = duplicateScopeRef.current;
        if (
            duplicateOperationRef.current !== null
            || !scope.active
            || scope.switching
            || scope.activeWorkspaceId === null
            || (workflow.executionMode === "system" && !scope.canRunAsSystem)
        ) return;

        const controller = new AbortController();
        const operation: DuplicateOperation = {
            controller,
            pathname: scope.pathname,
            workflowId: workflow.id,
            systemMode: workflow.executionMode === "system",
            workspaceId: scope.activeWorkspaceId,
        };
        const isCurrent = () => {
            const currentScope = duplicateScopeRef.current;
            return duplicateOperationRef.current === operation
                && currentScope.active
                && !currentScope.switching
                && currentScope.activeWorkspaceId === operation.workspaceId
                && currentScope.pathname === operation.pathname
                && !controller.signal.aborted;
        };
        const workspaceHeaders = { "X-Workspace-Id": String(operation.workspaceId) };
        const sourceRequestInit: RequestInit = {
            cache: "no-store",
            signal: controller.signal,
            headers: workspaceHeaders,
        };

        duplicateOperationRef.current = operation;
        setDuplicatingWorkflowId(workflow.id);
        try {
            const source = await getWorkflowById(workflow.id, sourceRequestInit);
            if (!isCurrent()) return;
            if (source.executionMode === "system" && !duplicateScopeRef.current.canRunAsSystem) {
                toastError(tw("duplicateSystemRestricted"));
                return;
            }
            operation.systemMode = source.executionMode === "system";
            const created = await createWorkflow(
                {
                    name: fitDuplicatedWorkflowName(source.name, (name) => tw("copyName", { name })),
                    description: source.description,
                    recordType: source.recordType,
                    executionMode: source.executionMode,
                    definition: source.definition,
                    canvas: source.canvas,
                },
                { headers: workspaceHeaders },
            );
            if (!isCurrent()) return;
            toastSuccess(tw("duplicated", { name: created.name }));
            router.push(`/workflows/${created.id}`);
        } catch {
            if (!isCurrent()) return;
            toastError(tw("duplicateFailed"));
        } finally {
            if (duplicateOperationRef.current === operation) {
                duplicateOperationRef.current = null;
                setDuplicatingWorkflowId(null);
            }
        }
    };

    return { duplicateWorkflow, duplicatingWorkflowId };
}
