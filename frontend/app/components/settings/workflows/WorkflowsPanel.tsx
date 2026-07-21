"use client";

import { useEffect, useLayoutEffect, useRef, useState } from "react";
import { usePathname, useRouter } from "next/navigation";
import { useTranslations } from "next-intl";
import {
    BoltIcon,
    ClockIcon,
    DocumentDuplicateIcon,
    EllipsisHorizontalIcon,
    PencilSquareIcon,
    PlusIcon,
    TrashIcon,
} from "@heroicons/react/24/outline";

import type { Rule } from "@/app/lib/types";
import { createRule, deleteRule, getRuleById, getRules, updateRule } from "@/app/lib/api";
import { useWorkspace } from "@/app/hooks/useWorkspace";
import { toastError, toastSuccess } from "@/app/lib/toast";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
    DropdownMenu,
    DropdownMenuContent,
    DropdownMenuItem,
    DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Skeleton } from "@/components/ui/skeleton";
import { Switch } from "@/components/ui/switch";
import DeleteRecordDialog from "@/app/components/records/DeleteRecordDialog";
import Rise from "@/app/components/motion/Rise";
import SectionHeader from "@/app/components/dashboard/SectionHeader";
import { ruleSummary, ruleToRequest } from "@/app/components/settings/RulesPanel";
import WorkflowRunsDialog from "@/app/components/settings/workflows/WorkflowRunsDialog";

const rowActionTrigger =
    "flex size-7 items-center justify-center rounded-full text-muted-foreground opacity-0 transition hover:bg-muted/70 hover:text-foreground group-hover:opacity-100 focus:opacity-100 focus-visible:opacity-100 data-[state=open]:opacity-100";
const MAX_RULE_NAME_LENGTH = 128;

type DuplicateOperation = {
    controller: AbortController;
    pathname: string;
    ruleId: number;
    systemMode: boolean;
    workspaceId: number;
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
 * Workflows list at /workflows: the same automations the legacy rules panel managed,
 * now opening the full-page editor instead of a dialog. Data access is unchanged — rows are
 * rules, toggles/deletes go straight through the rules API.
 */
export default function WorkflowsPanel() {
    const t = useTranslations("WorkspaceRules");
    const tw = useTranslations("WorkspaceWorkflows");
    const router = useRouter();
    const pathname = usePathname();
    const { activeWorkspaceId, activeWorkspace, switching } = useWorkspace();
    const canRunAsSystem = activeWorkspace?.role === "owner" || activeWorkspace?.role === "admin";

    const [rules, setRules] = useState<Rule[]>([]);
    const [loading, setLoading] = useState(true);
    const [accessDenied, setAccessDenied] = useState(false);
    const [removeTarget, setRemoveTarget] = useState<Rule | null>(null);
    const [runsTarget, setRunsTarget] = useState<{ workspaceId: number; rule: Rule } | null>(null);
    const [isRemoving, setIsRemoving] = useState(false);
    const [duplicatingRuleId, setDuplicatingRuleId] = useState<number | null>(null);
    const duplicateOperationRef = useRef<DuplicateOperation | null>(null);
    const duplicateScopeRef = useRef({
        active: true,
        activeWorkspaceId,
        canRunAsSystem,
        pathname,
        switching,
    });

    const runsRule = runsTarget?.workspaceId === activeWorkspaceId ? runsTarget.rule : null;

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

    useEffect(() => {
        if (!activeWorkspaceId) return;
        let cancelled = false;
        (async () => {
            setLoading(true);
            setAccessDenied(false);
            try {
                const loaded = await getRules();
                if (!cancelled) setRules(loaded);
            } catch (err) {
                if (!cancelled) {
                    if (err instanceof Error && "status" in err && (err as { status?: number }).status === 403) {
                        setAccessDenied(true);
                    } else {
                        toastError(t("loadFailed"));
                    }
                }
            } finally {
                if (!cancelled) setLoading(false);
            }
        })();
        return () => {
            cancelled = true;
        };
    }, [activeWorkspaceId, t]);

    const toggleEnabled = async (rule: Rule) => {
        const next = !rule.enabled;
        setRules((prev) => prev.map((r) => (r.id === rule.id ? { ...r, enabled: next } : r)));
        try {
            await updateRule(rule.id, { ...ruleToRequest(rule), enabled: next });
        } catch (err) {
            setRules((prev) => prev.map((r) => (r.id === rule.id ? { ...r, enabled: rule.enabled } : r)));
            toastError(err instanceof Error ? err.message : t("saveFailed"));
        }
    };

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

    const confirmRemove = async () => {
        if (!removeTarget) return;
        setIsRemoving(true);
        try {
            await deleteRule(removeTarget.id);
            setRules((prev) => prev.filter((r) => r.id !== removeTarget.id));
            toastSuccess(t("deleted"));
            setRemoveTarget(null);
        } catch (err) {
            toastError(err instanceof Error ? err.message : t("deleteFailed"));
        } finally {
            setIsRemoving(false);
        }
    };

    if (accessDenied) {
        return (
            <p className="rounded-2xl border border-border bg-card px-4 py-6 text-center text-sm text-muted-foreground">
                {t("noAccess")}
            </p>
        );
    }

    return (
        <Rise className="space-y-4">
            <div>
                <SectionHeader
                    title={tw("title")}
                    action={
                        !loading && (
                            <Button onClick={() => router.push("/workflows/new")} variant="brand">
                                <PlusIcon className="size-4" />
                                {tw("newWorkflow")}
                            </Button>
                        )
                    }
                />
                <p className="max-w-prose px-6 text-sm text-muted-foreground">{tw("subtitle")}</p>
            </div>

            {loading ? (
                <WorkflowSkeleton rows={3} />
            ) : rules.length === 0 ? (
                <div className="flex flex-col items-center gap-3 rounded-2xl border border-border bg-card px-6 py-12 text-center">
                    <span aria-hidden className="grid size-11 place-items-center rounded-full bg-muted text-muted-foreground">
                        <BoltIcon className="size-5" />
                    </span>
                    <p className="text-sm font-medium text-foreground">{tw("emptyTitle")}</p>
                    <p className="max-w-xs text-sm text-muted-foreground">{tw("emptyBody")}</p>
                    <Button onClick={() => router.push("/workflows/new")} variant="outline" className="mt-1">
                        <PlusIcon className="size-4" />
                        {tw("newWorkflow")}
                    </Button>
                </div>
            ) : (
                <ul className="divide-y divide-border overflow-hidden rounded-2xl border border-border bg-card">
                    {rules.map((rule) => (
                        <li key={rule.id} className="group flex items-center gap-3 px-4 py-3.5">
                            <Switch
                                checked={rule.enabled}
                                onCheckedChange={() => toggleEnabled(rule)}
                                disabled={rule.executionMode === "system" && !canRunAsSystem}
                                aria-label={t("toggleEnabled", { name: rule.name })}
                                aria-describedby={
                                    rule.executionMode === "system" && !canRunAsSystem
                                        ? `system-toggle-restriction-${rule.id}`
                                        : undefined
                                }
                            />
                            <button
                                type="button"
                                onClick={() => router.push(`/workflows/${rule.id}`)}
                                className="min-w-0 flex-1 space-y-1 text-left focus-visible:outline-none"
                            >
                                <span className="flex items-center gap-2">
                                    <span className="truncate text-sm font-medium text-foreground group-hover:underline">
                                        {rule.name}
                                    </span>
                                    {rule.executionMode === "system" && (
                                        <Badge variant="secondary" className="gap-1 text-muted-foreground">
                                            <BoltIcon className="size-3" />
                                            {t("systemBadge")}
                                        </Badge>
                                    )}
                                    {!rule.enabled && (
                                        <Badge variant="outline" className="text-muted-foreground">
                                            {t("disabledBadge")}
                                        </Badge>
                                    )}
                                </span>
                                <span className="block truncate text-xs text-muted-foreground">{ruleSummary(rule, t)}</span>
                                {rule.executionMode === "system" && !canRunAsSystem ? (
                                    <span id={`system-toggle-restriction-${rule.id}`} className="block text-xs text-muted-foreground">
                                        {t("systemToggleRestricted")}
                                    </span>
                                ) : null}
                            </button>
                            <DropdownMenu>
                                <DropdownMenuTrigger asChild>
                                    <button type="button" aria-label={tw("rowActions", { name: rule.name })} className={rowActionTrigger}>
                                        <EllipsisHorizontalIcon className="size-5" />
                                    </button>
                                </DropdownMenuTrigger>
                                <DropdownMenuContent align="end" className="w-40">
                                    <DropdownMenuItem onSelect={() => router.push(`/workflows/${rule.id}`)}>
                                        <PencilSquareIcon className="size-4" />
                                        {t("edit")}
                                    </DropdownMenuItem>
                                    {(rule.executionMode !== "system" || canRunAsSystem) && (
                                        <DropdownMenuItem
                                            disabled={duplicatingRuleId !== null}
                                            onSelect={() => void duplicateRule(rule)}
                                        >
                                            <DocumentDuplicateIcon className="size-4" />
                                            {duplicatingRuleId === rule.id ? tw("duplicating") : tw("duplicate")}
                                        </DropdownMenuItem>
                                    )}
                                    <DropdownMenuItem
                                        onSelect={() => {
                                            if (activeWorkspaceId == null) return;
                                            requestAnimationFrame(() => setRunsTarget({ workspaceId: activeWorkspaceId, rule }));
                                        }}
                                    >
                                        <ClockIcon className="size-4" />
                                        {tw("runs.view")}
                                    </DropdownMenuItem>
                                    <DropdownMenuItem variant="destructive" onSelect={() => setRemoveTarget(rule)}>
                                        <TrashIcon className="size-4" />
                                        {t("delete")}
                                    </DropdownMenuItem>
                                </DropdownMenuContent>
                            </DropdownMenu>
                        </li>
                    ))}
                </ul>
            )}

            {runsRule && activeWorkspaceId != null ? (
                <WorkflowRunsDialog
                    open
                    onOpenChange={(open) => {
                        if (!open) setRunsTarget(null);
                    }}
                    rule={runsRule}
                    workspaceId={activeWorkspaceId}
                />
            ) : null}

            <DeleteRecordDialog
                open={removeTarget !== null}
                onOpenChange={(open) => {
                    if (!open) setRemoveTarget(null);
                }}
                selectedIds={new Set(removeTarget ? [removeTarget.id] : [])}
                selectedItems={removeTarget ? [removeTarget] : []}
                entityLabel={t("ruleEntityLabel")}
                getDisplayName={(r) => r.name}
                isDeleting={isRemoving}
                confirmDelete={confirmRemove}
            />
        </Rise>
    );
}

function WorkflowSkeleton({ rows }: { rows: number }) {
    return (
        <ul className="divide-y divide-border overflow-hidden rounded-2xl border border-border bg-card">
            {Array.from({ length: rows }, (_, i) => (
                <li key={i} className="flex items-center gap-3 px-4 py-3.5">
                    <Skeleton className="h-5 w-9 shrink-0 rounded-full" />
                    <div className="flex-1 space-y-2">
                        <Skeleton className="h-3.5 w-32" />
                        <Skeleton className="h-3 w-52" />
                    </div>
                </li>
            ))}
        </ul>
    );
}
