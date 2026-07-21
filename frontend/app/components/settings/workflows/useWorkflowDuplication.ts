"use client";

import { useLayoutEffect, useRef, useState } from "react";
import { usePathname, useRouter } from "next/navigation";
import { useTranslations } from "next-intl";

import type { Rule } from "@/app/lib/types";
import { createRule, getRuleById } from "@/app/lib/api";
import { toastError, toastSuccess } from "@/app/lib/toast";
import { ruleToRequest } from "@/app/components/settings/RulesPanel";

const MAX_RULE_NAME_LENGTH = 128;

type DuplicateOperation = {
    controller: AbortController;
    pathname: string;
    ruleId: number;
    systemMode: boolean;
    workspaceId: number;
};

type WorkflowDuplicationOptions = {
    activeWorkspaceId: number | null;
    canRunAsSystem: boolean;
    switching: boolean;
};

function fitDuplicatedRuleName(name: string, format: (value: string) => string): string {
    let baseName = name.trim();
    let copyName = format(baseName);
    if (copyName.length <= MAX_RULE_NAME_LENGTH) return copyName;

    baseName = baseName.slice(0, Math.max(0, baseName.length - (copyName.length - MAX_RULE_NAME_LENGTH))).trimEnd();
    copyName = format(baseName);
    return copyName.slice(0, MAX_RULE_NAME_LENGTH);
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
    const [duplicatingRuleId, setDuplicatingRuleId] = useState<number | null>(null);
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
            setDuplicatingRuleId(null);
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

    const duplicateRule = async (rule: Rule) => {
        const scope = duplicateScopeRef.current;
        if (
            duplicateOperationRef.current !== null
            || !scope.active
            || scope.switching
            || scope.activeWorkspaceId === null
            || (rule.executionMode === "system" && !scope.canRunAsSystem)
        ) return;

        const controller = new AbortController();
        const operation: DuplicateOperation = {
            controller,
            pathname: scope.pathname,
            ruleId: rule.id,
            systemMode: rule.executionMode === "system",
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
        const requestInit: RequestInit = {
            cache: "no-store",
            signal: controller.signal,
            headers: { "X-Workspace-Id": String(operation.workspaceId) },
        };

        duplicateOperationRef.current = operation;
        setDuplicatingRuleId(rule.id);
        try {
            const source = await getRuleById(rule.id, requestInit);
            if (!isCurrent()) return;
            if (source.executionMode === "system" && !duplicateScopeRef.current.canRunAsSystem) {
                toastError(tw("duplicateSystemRestricted"));
                return;
            }
            operation.systemMode = source.executionMode === "system";
            const created = await createRule(
                {
                    ...ruleToRequest(source),
                    name: fitDuplicatedRuleName(source.name, (name) => tw("copyName", { name })),
                    enabled: false,
                },
                requestInit,
            );
            if (!isCurrent()) return;
            toastSuccess(tw("duplicated", { name: created.name }));
            router.push(`/workflows/${created.id}`);
        } catch (error) {
            if (!isCurrent()) return;
            toastError(error instanceof Error ? error.message : tw("duplicateFailed"));
        } finally {
            if (duplicateOperationRef.current === operation) {
                duplicateOperationRef.current = null;
                setDuplicatingRuleId(null);
            }
        }
    };

    return { duplicateRule, duplicatingRuleId };
}
