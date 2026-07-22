"use client";

import { useEffect, useState } from "react";
import { useTranslations } from "next-intl";
import {
    BoltIcon,
    EllipsisHorizontalIcon,
    PencilSquareIcon,
    PlusIcon,
    TrashIcon,
} from "@heroicons/react/24/outline";

import type { Rule, RuleBuilderOptions, RuleRequest, SegmentFields } from "@/app/lib/types";
import {
    createRule,
    deleteRule,
    getActiveWorkspaceMembers,
    getCompanies,
    getPipelines,
    getRules,
    getSegmentFields,
    getStagesByPipelineId,
    updateRule,
} from "@/app/lib/api";
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
import RuleDialog from "./RuleDialog";

const rowActionTrigger =
    "flex size-7 items-center justify-center rounded-full text-muted-foreground opacity-0 transition hover:bg-muted/70 hover:text-foreground group-hover:opacity-100 focus:opacity-100 focus-visible:opacity-100 data-[state=open]:opacity-100";

/** Builds the request shape from a stored rule (drops the server-only fields). */
export function ruleToRequest(rule: Rule): RuleRequest {
    return {
        name: rule.name,
        description: rule.description,
        enabled: rule.enabled,
        recordType: rule.recordType,
        trigger: rule.trigger,
        condition: rule.condition ?? undefined,
        actions: rule.actions,
        executionMode: rule.executionMode,
    };
}

/** Human summary line for a rule, built from the shared WorkspaceRules vocabulary labels. */
export function ruleSummary(rule: Rule, t: (key: string, values?: Record<string, string>) => string): string {
    const trigger =
        rule.trigger.type === "schedule"
            ? t("summarySchedule", { cadence: t(`cadence.${rule.trigger.cadence ?? "daily"}`) })
            : t("summaryEntity", {
                  record: t(`record.${rule.recordType}`),
                  events: (rule.trigger.events ?? []).map((event) => t(`event.${event}`)).join(", "),
              });
    const actions = rule.actions.map((action) => t(`action.${action.type}`)).join(", ");
    return t("summaryFull", { trigger, actions });
}

export default function RulesPanel() {
    const t = useTranslations("WorkspaceRules");
    const { activeWorkspaceId, activeWorkspace } = useWorkspace();
    const canRunAsSystem = activeWorkspace?.role === "owner" || activeWorkspace?.role === "admin";

    const [rules, setRules] = useState<Rule[]>([]);
    const [fields, setFields] = useState<SegmentFields | null>(null);
    const [options, setOptions] = useState<RuleBuilderOptions | null>(null);
    const [loading, setLoading] = useState(true);
    const [accessDenied, setAccessDenied] = useState(false);

    const [dialogOpen, setDialogOpen] = useState(false);
    const [editing, setEditing] = useState<Rule | null>(null);
    const [removeTarget, setRemoveTarget] = useState<Rule | null>(null);
    const [isRemoving, setIsRemoving] = useState(false);

    useEffect(() => {
        if (!activeWorkspaceId) return;
        let cancelled = false;
        (async () => {
            setLoading(true);
            setAccessDenied(false);
            try {
                const loadedRules = await getRules();
                if (cancelled) return;
                setRules(loadedRules);
            } catch (err) {
                if (!cancelled) {
                    if (err instanceof Error && "status" in err && (err as { status?: number }).status === 403) {
                        setAccessDenied(true);
                    } else {
                        toastError(t("loadFailed"));
                    }
                    setLoading(false);
                }
                return;
            }
            if (!cancelled) setLoading(false);
            try {
                const loadedFields = await getSegmentFields("company");
                if (!cancelled) setFields(loadedFields);
            } catch {
                if (!cancelled) toastError(t("fieldsLoadFailed"));
            }
            const [pipelines, members, companies] = await Promise.all([
                getPipelines().catch(() => []),
                getActiveWorkspaceMembers().catch(() => []),
                getCompanies().catch(() => []),
            ]);
            if (cancelled) return;
            const stageLists = await Promise.all(
                pipelines.map((pipeline) =>
                    getStagesByPipelineId(pipeline.id)
                        .then((stages) => stages.map((stage) => ({ id: stage.id, name: stage.name, pipeline: pipeline.name })))
                        .catch(() => []),
                ),
            );
            if (!cancelled) {
                setOptions({
                    stages: stageLists.flat(),
                    owners: members.map((member) => ({ id: member.id, name: member.displayName || member.username })),
                    companies: companies.map((company) => ({ id: company.id, name: company.name })),
                });
            }
        })();
        return () => {
            cancelled = true;
        };
    }, [activeWorkspaceId, t]);

    const openCreate = () => {
        setEditing(null);
        setDialogOpen(true);
    };

    const openEdit = (rule: Rule) => {
        setEditing(rule);
        setDialogOpen(true);
    };

    const submitRule = async (payload: RuleRequest) => {
        try {
            if (editing) {
                const updated = await updateRule(editing.id, payload);
                setRules((prev) => prev.map((r) => (r.id === updated.id ? updated : r)));
                toastSuccess(t("updated"));
            } else {
                const created = await createRule(payload);
                setRules((prev) => [created, ...prev]);
                toastSuccess(t("created"));
            }
        } catch (err) {
            toastError(err instanceof Error ? err.message : t("saveFailed"));
            throw err;
        }
    };

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
                    title={t("title")}
                    action={
                        !loading && (
                            <Button onClick={openCreate} variant="brand">
                                <PlusIcon className="size-4" />
                                {t("newRule")}
                            </Button>
                        )
                    }
                />
                <p className="max-w-prose px-6 text-sm text-muted-foreground">{t("subtitle")}</p>
            </div>

            {loading ? (
                <RuleSkeleton rows={3} />
            ) : rules.length === 0 ? (
                <div className="flex flex-col items-center gap-3 rounded-2xl border border-border bg-card px-6 py-12 text-center">
                    <span aria-hidden className="grid size-11 place-items-center rounded-full bg-muted text-muted-foreground">
                        <BoltIcon className="size-5" />
                    </span>
                    <p className="text-sm font-medium text-foreground">{t("emptyTitle")}</p>
                    <p className="max-w-xs text-sm text-muted-foreground">{t("emptyBody")}</p>
                    <Button onClick={openCreate} variant="outline" className="mt-1">
                        <PlusIcon className="size-4" />
                        {t("newRule")}
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
                            <div className="min-w-0 flex-1 space-y-1">
                                <div className="flex items-center gap-2">
                                    <span className="truncate text-sm font-medium text-foreground">{rule.name}</span>
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
                                </div>
                                <p className="truncate text-xs text-muted-foreground">{ruleSummary(rule, t)}</p>
                                {rule.executionMode === "system" && !canRunAsSystem ? (
                                    <p id={`system-toggle-restriction-${rule.id}`} className="text-xs text-muted-foreground">
                                        {t("systemToggleRestricted")}
                                    </p>
                                ) : null}
                            </div>
                            <DropdownMenu>
                                <DropdownMenuTrigger asChild>
                                    <button type="button" aria-label={t("ruleActions")} className={rowActionTrigger}>
                                        <EllipsisHorizontalIcon className="size-5" />
                                    </button>
                                </DropdownMenuTrigger>
                                <DropdownMenuContent align="end" className="w-40">
                                    <DropdownMenuItem onSelect={() => openEdit(rule)}>
                                        <PencilSquareIcon className="size-4" />
                                        {t("edit")}
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

            <RuleDialog
                open={dialogOpen}
                onOpenChange={setDialogOpen}
                editing={editing}
                fields={fields}
                options={options}
                canRunAsSystem={canRunAsSystem}
                onSubmit={submitRule}
            />

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

function RuleSkeleton({ rows }: { rows: number }) {
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
