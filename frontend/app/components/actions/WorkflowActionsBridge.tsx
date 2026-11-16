"use client";

import { useEffect, useMemo, useState } from "react";
import { BoltIcon } from "@heroicons/react/24/outline";

import { useActions, useRegisterActions } from "@/app/hooks/useActions";
import { usePermission } from "@/app/hooks/usePermissions";
import { useWorkspace } from "@/app/hooks/useWorkspace";
import { getWorkflows } from "@/app/lib/api";
import type { AppAction } from "@/app/lib/actions/types";
import { subscribeToPaletteOpened } from "@/app/lib/actions/paletteEvents";
import { supportsManualRun } from "@/app/components/settings/workflows/vocabulary";

/** Registers the context-free workflow launcher only for a permitted, runnable workspace. */
export default function WorkflowActionsBridge(): null {
    const { context } = useActions();
    const { switching } = useWorkspace();
    const permitted = usePermission("RULE_MANAGE");
    const workspaceId = context.workspace?.id ?? null;
    const userId = context.user?.id ?? null;
    const identity = `${userId}:${workspaceId}:${context.route.pathname}`;
    const [availability, setAvailability] = useState<{ identity: string; runnable: boolean } | null>(null);

    useEffect(() => {
        if (!permitted || switching || workspaceId === null || userId === null) return;
        let pending: AbortController | null = null;
        const refresh = () => {
            pending?.abort();
            const controller = new AbortController();
            pending = controller;
            void getWorkflows(false, {
                headers: { "X-Workspace-Id": String(workspaceId) },
                signal: controller.signal,
            }).then((workflows) => {
                if (controller.signal.aborted) return;
                setAvailability({
                    identity,
                    runnable: workflows.some((workflow) => workflow.activeVersion != null
                        && workflow.runtimeOwner === "canonical"
                        && workflow.executionAvailable
                        && workflow.enabled
                        && workflow.archivedAt == null
                        && workflow.intakePausedAt == null
                        && supportsManualRun(workflow.recordType)),
                });
            }).catch(() => {
                if (!controller.signal.aborted) setAvailability({ identity, runnable: false });
            });
        };
        refresh();
        const unsubscribe = subscribeToPaletteOpened(() => {
            setAvailability(null);
            refresh();
        });
        return () => {
            pending?.abort();
            unsubscribe();
        };
    }, [identity, permitted, switching, userId, workspaceId]);

    const runnable = permitted && !switching && availability?.identity === identity && availability.runnable;
    const actions = useMemo<readonly AppAction[]>(() => [{
        id: "workspace.run-workflow",
        group: "workspace",
        labelKey: "workspace.runWorkflow",
        icon: BoltIcon,
        order: 20,
        isAvailable: (current) => runnable === true
            && current.workspace?.id === workspaceId
            && current.user?.id === userId
            && current.record === null
            && current.selection === null,
        execute: (_current, helpers) => helpers.openOverlay({
            kind: "workflow-manual-run",
            sourceSurface: helpers.source === "palette" ? "command_palette" : "record_list",
            recordType: null,
            scope: null,
        }),
    }], [runnable, userId, workspaceId]);

    useRegisterActions(actions);
    return null;
}
