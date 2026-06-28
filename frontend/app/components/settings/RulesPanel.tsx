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

import type { Rule, RuleRequest, SegmentFields } from "@/app/lib/types";
import { createRule, deleteRule, getRules, getSegmentFields, updateRule } from "@/app/lib/api";
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

function ruleSummary(rule: Rule, t: (key: string, values?: Record<string, string>) => string): string {
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
    const { activeWorkspaceId } = useWorkspace();

    const [rules, setRules] = useState<Rule[]>([]);
    const [fields, setFields] = useState<SegmentFields | null>(null);
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
                const [loadedRules, loadedFields] = await Promise.all([
                    getRules(),
                    getSegmentFields("company").catch(() => null),
                ]);
                if (cancelled) return;
                setRules(loadedRules);
                setFields(loadedFields);
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
            <p className="rounded-2xl bg-card px-4 py-6 text-center text-sm text-muted-foreground ring-1 ring-border">
                {t("noAccess")}
            </p>
        );
    }

    return (
        <div className="space-y-4">
            <div className="flex items-start justify-between gap-4">
                <div className="space-y-1">
                    <h2 className="text-sm font-medium text-foreground">{t("title")}</h2>
                    <p className="max-w-prose text-sm text-muted-foreground">{t("subtitle")}</p>
                </div>
                {!loading && (
                    <Button onClick={openCreate} className="shrink-0 bg-brand text-white hover:bg-brand-hover">
                        <PlusIcon className="size-4" />
                        {t("newRule")}
                    </Button>
                )}
            </div>

            {loading ? (
                <RuleSkeleton rows={3} />
            ) : rules.length === 0 ? (
                <div className="flex flex-col items-center gap-3 rounded-2xl bg-card px-6 py-12 text-center ring-1 ring-border">
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
                <ul className="divide-y divide-border overflow-hidden rounded-2xl bg-card ring-1 ring-border">
                    {rules.map((rule) => (
                        <li key={rule.id} className="group flex items-center gap-3 px-4 py-3.5">
                            <Switch
                                checked={rule.enabled}
                                onCheckedChange={() => toggleEnabled(rule)}
                                aria-label={t("toggleEnabled", { name: rule.name })}
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
        </div>
    );
}

function RuleSkeleton({ rows }: { rows: number }) {
    return (
        <ul className="divide-y divide-border overflow-hidden rounded-2xl bg-card ring-1 ring-border">
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
