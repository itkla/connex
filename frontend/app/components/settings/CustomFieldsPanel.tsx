"use client";

import { useEffect, useMemo, useState, useTransition } from "react";
import AccessDenied from "@/app/components/AccessDenied";
import PermissionsUnavailable from "@/app/components/PermissionsUnavailable";
import { useTranslations } from "next-intl";
import {
    ArrowPathIcon,
    EllipsisHorizontalIcon,
    PencilSquareIcon,
    PlusIcon,
    TrashIcon,
} from "@heroicons/react/24/outline";

import type {
    CustomFieldDataClassification,
    CustomFieldDefinition,
    CustomFieldEntityType,
    CustomFieldType,
} from "@/app/lib/types";
import { deleteCustomField, getCustomFields } from "@/app/lib/api";
import { usePermissionCheck, usePermissionsRefresh } from "@/app/hooks/usePermissions";
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
import DeleteRecordDialog from "@/app/components/records/DeleteRecordDialog";
import Rise from "@/app/components/motion/Rise";
import { SettingsSection } from "@/app/components/settings/SettingsSection";
import CustomFieldDialog from "./CustomFieldDialog";

const ENTITY_TYPES: CustomFieldEntityType[] = ["company", "person", "deal"];

const rowActionTrigger =
    "flex size-7 items-center justify-center rounded-full text-muted-foreground opacity-0 transition hover:bg-muted/70 hover:text-foreground group-hover:opacity-100 focus:opacity-100 focus-visible:opacity-100 data-[state=open]:opacity-100";

export default function CustomFieldsPanel() {
    const t = useTranslations("WorkspaceCustomFields");
    const { activeWorkspaceId } = useWorkspace();
    const workspaceId = activeWorkspaceId;

    const manageCheck = usePermissionCheck("CUSTOM_FIELD_MANAGE");
    const canManage = manageCheck === "granted";

    const [fields, setFields] = useState<CustomFieldDefinition[]>([]);
    const [loading, setLoading] = useState(true);
    const [accessDenied, setAccessDenied] = useState(false);

    const [dialogOpen, setDialogOpen] = useState(false);
    const [dialogMode, setDialogMode] = useState<"create" | "edit">("create");
    const [dialogEntity, setDialogEntity] = useState<CustomFieldEntityType>("company");
    const [editing, setEditing] = useState<CustomFieldDefinition | null>(null);
    const [removeTarget, setRemoveTarget] = useState<CustomFieldDefinition | null>(null);
    const [isRemoving, setIsRemoving] = useState(false);

    const typeLabels: Record<CustomFieldType, string> = {
        text: t("typeText"),
        textarea: t("typeTextarea"),
        number: t("typeNumber"),
        date: t("typeDate"),
        boolean: t("typeBoolean"),
        select: t("typeSelect"),
        url: t("typeUrl"),
    };
    const entityLabels: Record<CustomFieldEntityType, string> = {
        company: t("entityCompany"),
        person: t("entityPerson"),
        deal: t("entityDeal"),
    };
    const emptyLabels: Record<CustomFieldEntityType, string> = {
        company: t("emptyCompany"),
        person: t("emptyPerson"),
        deal: t("emptyDeal"),
    };
    const classificationLabels: Record<CustomFieldDataClassification, string> = {
        standard: t("classificationBadge.standard"),
        sensitive: t("classificationBadge.sensitive"),
        special_care: t("classificationBadge.special_care"),
    };

    useEffect(() => {
        if (!workspaceId || !canManage) return;
        let cancelled = false;
        (async () => {
            setLoading(true);
            try {
                const loaded = await getCustomFields();
                if (cancelled) return;
                setFields(loaded);
                setAccessDenied(false);
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
    }, [workspaceId, canManage, t]);

    const byEntity = useMemo(() => {
        const groups: Record<CustomFieldEntityType, CustomFieldDefinition[]> = {
            company: [],
            person: [],
            deal: [],
        };
        for (const field of fields) {
            groups[field.entityType]?.push(field);
        }
        return groups;
    }, [fields]);

    const openCreate = (entityType: CustomFieldEntityType) => {
        setDialogMode("create");
        setDialogEntity(entityType);
        setEditing(null);
        setDialogOpen(true);
    };

    const openEdit = (field: CustomFieldDefinition) => {
        setDialogMode("edit");
        setDialogEntity(field.entityType);
        setEditing(field);
        setDialogOpen(true);
    };

    const handleSaved = (saved: CustomFieldDefinition) => {
        setFields((prev) => {
            const exists = prev.some((field) => field.id === saved.id);
            return exists ? prev.map((field) => (field.id === saved.id ? saved : field)) : [...prev, saved];
        });
    };

    const confirmRemove = async () => {
        if (!removeTarget) return;
        setIsRemoving(true);
        try {
            await deleteCustomField(removeTarget.id);
            setFields((prev) => prev.filter((field) => field.id !== removeTarget.id));
            toastSuccess(t("deleted"));
            setRemoveTarget(null);
        } catch (err) {
            toastError(err instanceof Error ? err.message : t("deleteFailed"));
        } finally {
            setIsRemoving(false);
        }
    };

    if (manageCheck === "unavailable") {
        return <PermissionsUnavailableSection />;
    }

    if (!canManage || accessDenied) {
        return (
            <AccessDenied variant="inline" body={t("noAccess")} />
        );
    }

    return (
        <div className="space-y-10">
            {ENTITY_TYPES.map((entityType, index) => (
                <Rise key={entityType} index={index} className="space-y-4">
                    <SettingsSection
                        title={entityLabels[entityType]}
                        action={
                            !loading && (
                                <Button
                                    variant="outline"
                                    size="sm"
                                    onClick={() => openCreate(entityType)}
                                >
                                    <PlusIcon className="size-4" />
                                    {t("newField")}
                                </Button>
                            )
                        }
                    />

                    {loading ? (
                        <FieldSkeleton rows={2} />
                    ) : byEntity[entityType].length === 0 ? (
                        <p className="rounded-2xl border border-border bg-card px-4 py-6 text-center text-sm text-muted-foreground">
                            {emptyLabels[entityType]}
                        </p>
                    ) : (
                        <ul className="divide-y divide-border overflow-hidden rounded-2xl border border-border bg-card">
                            {byEntity[entityType].map((field) => (
                                <li key={field.id} className="group flex items-center gap-3 px-4 py-3.5">
                                    <div className="min-w-0 flex-1 space-y-1">
                                        <div className="flex items-center gap-2">
                                            <span className="truncate text-sm font-medium text-foreground">
                                                {field.label}
                                            </span>
                                            {field.required && (
                                                <Badge variant="secondary" className="text-muted-foreground">
                                                    {t("requiredBadge")}
                                                </Badge>
                                            )}
                                            {field.dataClassification !== "standard" && (
                                                <Badge
                                                    variant={
                                                        field.dataClassification === "special_care"
                                                            ? "destructive"
                                                            : "secondary"
                                                    }
                                                >
                                                    {classificationLabels[field.dataClassification]}
                                                </Badge>
                                            )}
                                        </div>
                                        <div className="flex items-center gap-2 text-xs text-muted-foreground">
                                            <span>{typeLabels[field.fieldType]}</span>
                                            <span aria-hidden>·</span>
                                            <span className="truncate font-mono">{field.fieldKey}</span>
                                        </div>
                                    </div>
                                    <DropdownMenu>
                                        <DropdownMenuTrigger asChild>
                                            <button
                                                type="button"
                                                aria-label={t("fieldActions")}
                                                className={rowActionTrigger}
                                            >
                                                <EllipsisHorizontalIcon className="size-5" />
                                            </button>
                                        </DropdownMenuTrigger>
                                        <DropdownMenuContent align="end" className="w-40">
                                            <DropdownMenuItem onSelect={() => openEdit(field)}>
                                                <PencilSquareIcon className="size-4" />
                                                {t("edit")}
                                            </DropdownMenuItem>
                                            <DropdownMenuItem
                                                variant="destructive"
                                                onSelect={() => setRemoveTarget(field)}
                                            >
                                                <TrashIcon className="size-4" />
                                                {t("delete")}
                                            </DropdownMenuItem>
                                        </DropdownMenuContent>
                                    </DropdownMenu>
                                </li>
                            ))}
                        </ul>
                    )}
                </Rise>
            ))}

            <CustomFieldDialog
                open={dialogOpen}
                onOpenChange={setDialogOpen}
                mode={dialogMode}
                entityType={dialogEntity}
                field={editing}
                onSaved={handleSaved}
            />

            <DeleteRecordDialog
                open={removeTarget !== null}
                onOpenChange={(open) => {
                    if (!open) setRemoveTarget(null);
                }}
                selectedIds={new Set(removeTarget ? [removeTarget.id] : [])}
                selectedItems={removeTarget ? [removeTarget] : []}
                entityLabel={t("fieldEntityLabel")}
                getDisplayName={(field) => field.label}
                isDeleting={isRemoving}
                confirmDelete={confirmRemove}
            />
        </div>
    );
}

function PermissionsUnavailableSection() {
    const t = useTranslations("PermissionsUnavailable");
    const refreshPermissions = usePermissionsRefresh();
    const [isRetrying, startTransition] = useTransition();

    const retry = () => {
        startTransition(async () => {
            await refreshPermissions();
        });
    };

    return (
        <PermissionsUnavailable
            variant="inline"
            title={t("title")}
            body={t("sectionBody")}
            action={
                <Button variant="outline" size="sm" onClick={retry} disabled={isRetrying}>
                    <ArrowPathIcon
                        data-icon="inline-start"
                        className={isRetrying ? "animate-spin motion-reduce:animate-none" : undefined}
                    />
                    {isRetrying ? t("retrying") : t("retry")}
                </Button>
            }
        />
    );
}

function FieldSkeleton({ rows }: { rows: number }) {
    return (
        <ul className="divide-y divide-border overflow-hidden rounded-2xl border border-border bg-card">
            {Array.from({ length: rows }, (_, i) => (
                <li key={i} className="flex items-center gap-3 px-4 py-3.5">
                    <div className="flex-1 space-y-2">
                        <Skeleton className="h-3.5 w-32" />
                        <Skeleton className="h-3 w-48" />
                    </div>
                </li>
            ))}
        </ul>
    );
}
