"use client";

import { useTranslations } from "next-intl";
import {
    ArrowLeftIcon,
    ArrowPathIcon,
    BeakerIcon,
    CheckCircleIcon,
    ClockIcon,
    DocumentCheckIcon,
    RectangleStackIcon,
} from "@heroicons/react/24/outline";
import { Loader2Icon, Redo2Icon, Undo2Icon } from "lucide-react";

import type { WorkflowExecutionMode, WorkflowRuntimeOwner, WorkflowValidation } from "@/app/lib/types";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { cn } from "@/lib/utils";

/** Persistent workflow identity, draft state, permission manifest, and lifecycle actions. */
export default function WorkflowLifecycleBar({
    name,
    revision,
    activeVersionNumber,
    hasActiveVersion,
    enabled,
    runtimeOwner,
    executionMode,
    archived,
    dirty,
    validation,
    canUndo,
    canRedo,
    busyAction,
    readOnly,
    onBack,
    onNameChange,
    onNameCommit,
    onUndo,
    onRedo,
    onSave,
    onValidate,
    onPublish,
    onToggleEnabled,
    onOpenSimulation,
    onOpenVersions,
    onOpenRuns,
}: {
    name: string;
    revision: number;
    activeVersionNumber: number | null;
    hasActiveVersion: boolean;
    enabled: boolean;
    runtimeOwner: WorkflowRuntimeOwner;
    executionMode: WorkflowExecutionMode;
    archived: boolean;
    dirty: boolean;
    validation: WorkflowValidation | null;
    canUndo: boolean;
    canRedo: boolean;
    busyAction: string | null;
    readOnly: boolean;
    onBack: () => void;
    onNameChange: (name: string) => void;
    onNameCommit: () => void;
    onUndo: () => void;
    onRedo: () => void;
    onSave: () => void;
    onValidate: () => void;
    onPublish: () => void;
    onToggleEnabled: () => void;
    onOpenSimulation: () => void;
    onOpenVersions: () => void;
    onOpenRuns: () => void;
}) {
    const t = useTranslations("WorkspaceWorkflows");
    const validationCurrent = validation?.draftRevision === revision;
    const canPublish = !readOnly && !dirty && validationCurrent && validation.canPublish;
    const permissionLabel = (permission: string) => {
        switch (permission) {
            case "TASK_CREATE":
            case "ACTIVITY_CREATE":
            case "NOTE_CREATE":
            case "DEAL_UPDATE":
            case "COMPANY_UPDATE":
            case "PERSON_UPDATE":
                return t(`permission.${permission}`);
            default:
                return t("permission.additional");
        }
    };
    const requiredPermissions = validation?.requiredPermissions.map(permissionLabel).join(", ") ?? "";
    const missingPermissions = validation?.missingPermissions.map(permissionLabel).join(", ") ?? "";
    return (
        <header className="border-b border-border bg-background px-3 py-3">
            <div className="flex flex-wrap items-center gap-2">
                <Button variant="ghost" size="icon-sm" aria-label={t("backToList")} onClick={onBack}>
                    <ArrowLeftIcon className="size-4" />
                </Button>
                <Input
                    value={name}
                    onChange={(event) => onNameChange(event.target.value)}
                    onBlur={onNameCommit}
                    disabled={readOnly}
                    maxLength={128}
                    aria-label={t("nameLabel")}
                    placeholder={t("namePlaceholder")}
                    className="h-9 min-w-48 flex-1 text-base font-semibold sm:max-w-sm"
                />
                <div className="flex items-center gap-1">
                    <Button variant="ghost" size="icon-sm" aria-label={t("undo")} disabled={!canUndo || readOnly} onClick={onUndo}>
                        <Undo2Icon className="size-4" />
                    </Button>
                    <Button variant="ghost" size="icon-sm" aria-label={t("redo")} disabled={!canRedo || readOnly} onClick={onRedo}>
                        <Redo2Icon className="size-4" />
                    </Button>
                </div>
                <Button variant="outline" size="sm" onClick={onOpenSimulation} disabled={dirty || revision < 0 || readOnly}>
                    <BeakerIcon className="size-4" />
                    {t("previewAction")}
                </Button>
                <Button variant="outline" size="sm" onClick={onValidate} disabled={dirty || revision < 0 || readOnly || busyAction !== null}>
                    {busyAction === "validate" ? <Loader2Icon className="size-4 animate-spin motion-reduce:animate-none" /> : <CheckCircleIcon className="size-4" />}
                    {t("validate")}
                </Button>
                <Button variant="outline" size="sm" onClick={onSave} disabled={!dirty || readOnly || busyAction !== null}>
                    {busyAction === "save" ? <Loader2Icon className="size-4 animate-spin motion-reduce:animate-none" /> : <DocumentCheckIcon className="size-4" />}
                    {t("saveDraft")}
                </Button>
                <Button variant="brand" size="sm" onClick={onPublish} disabled={!canPublish || busyAction !== null}>
                    {busyAction === "publish" ? <Loader2Icon className="size-4 animate-spin motion-reduce:animate-none" /> : t("publish")}
                </Button>
            </div>
            <div className="mt-2 flex flex-wrap items-center gap-2 pl-10 text-xs text-muted-foreground">
                {archived ? <Badge variant="outline">{t("archivedState")}</Badge> : null}
                <span>{t("draftRevision", { revision })}</span>
                <span aria-hidden>/</span>
                <span>
                    {activeVersionNumber == null
                        ? hasActiveVersion ? t("publishedVersionUnavailable") : t("noPublishedVersion")
                        : t("activeVersion", { number: activeVersionNumber })}
                </span>
                <span aria-hidden>/</span>
                <span className={cn(dirty && "font-medium text-foreground")}>
                    {dirty ? t("unpublishedChanges") : t("draftSaved")}
                </span>
                <span aria-hidden>/</span>
                <span>{t(`runtimeOwner.${runtimeOwner}`)}</span>
                {enabled && dirty ? <span>{t("runsUseActiveVersion")}</span> : null}
                {validationCurrent && validation.requiredPermissions.length > 0 ? (
                    <span>{t("permissionsRequired", { permissions: requiredPermissions })}</span>
                ) : null}
                {validationCurrent && validation.missingPermissions.length > 0 ? (
                    <span className="font-medium text-destructive">
                        {t("permissionsMissing", { permissions: missingPermissions })}
                    </span>
                ) : null}
                {validationCurrent && executionMode === "system" && !validation.systemAuthoringAllowed ? (
                    <span className="font-medium text-destructive">{t("systemValidationDenied")}</span>
                ) : null}
                <div className="ml-auto flex flex-wrap items-center gap-1">
                    <Button variant="ghost" size="xs" onClick={onOpenVersions} disabled={revision < 0}>
                        <RectangleStackIcon className="size-3.5" />
                        {t("versions.view")}
                    </Button>
                    <Button variant="ghost" size="xs" onClick={onOpenRuns} disabled={revision < 0}>
                        <ClockIcon className="size-3.5" />
                        {t("runs.view")}
                    </Button>
                    {!archived && hasActiveVersion ? (
                        <Button variant="ghost" size="xs" onClick={onToggleEnabled} disabled={busyAction !== null}>
                            <ArrowPathIcon className="size-3.5" />
                            {enabled ? t("disable") : t("enable")}
                        </Button>
                    ) : null}
                </div>
            </div>
        </header>
    );
}
