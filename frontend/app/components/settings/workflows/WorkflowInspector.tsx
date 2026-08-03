"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { useTranslations } from "next-intl";
import {
    BoltIcon,
    CheckIcon,
    UserIcon,
} from "@heroicons/react/24/outline";

import SegmentBuilder from "@/app/components/records/SegmentBuilder";
import {
    WORKFLOW_DELAY_MAX_SECONDS,
    WORKFLOW_DELAY_MIN_SECONDS,
} from "@/app/components/settings/workflows/workflowGraph";
import {
    CADENCES,
    RECORD_TYPES,
    SCHEDULE_RECORD_TYPES,
    actionsFor,
    eventsFor,
} from "@/app/components/settings/workflows/vocabulary";
import type { WorkflowEditorDocument } from "@/app/components/settings/workflows/workflowEditorReducer";
import type {
    RuleAction,
    RuleBuilderOptions,
    SegmentFields,
    WorkflowDiagnostic,
    WorkflowExecutionMode,
    WorkflowNode,
} from "@/app/lib/types";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
    Select,
    SelectContent,
    SelectItem,
    SelectTrigger,
    SelectValue,
} from "@/components/ui/select";
import { Textarea } from "@/components/ui/textarea";
import { cn } from "@/lib/utils";

type ChangeMode = "transient" | "commit";

function errorId(nodeId: string, diagnostic: WorkflowDiagnostic, index: number): string {
    return `workflow-error-${nodeId}-${index}-${diagnostic.code}`;
}

/** Shared typed inspector used by both canvas and outline authoring modes. */
export default function WorkflowInspector({
    document,
    selectedNodeId,
    fields,
    options,
    diagnostics,
    readOnly,
    canRunAsSystem,
    focusFieldPath,
    focusRequestId,
    diagnosticMessage,
    onNodeChange,
    onMetadataChange,
    onCommitTransient,
}: {
    document: WorkflowEditorDocument;
    selectedNodeId: string | null;
    fields: SegmentFields | null;
    options: RuleBuilderOptions | null;
    diagnostics: WorkflowDiagnostic[];
    readOnly: boolean;
    canRunAsSystem: boolean;
    focusFieldPath: string | null;
    focusRequestId: number;
    diagnosticMessage: (diagnostic: WorkflowDiagnostic) => string;
    onNodeChange: (node: WorkflowNode, mode: ChangeMode) => void;
    onMetadataChange: (
        field: "description" | "recordType" | "executionMode",
        value: string | null,
        mode: ChangeMode,
    ) => void;
    onCommitTransient: () => void;
}) {
    const t = useTranslations("WorkspaceWorkflows");
    const inspectorRef = useRef<HTMLDivElement>(null);
    const node = document.definition.nodes.find((candidate) => candidate.id === selectedNodeId) ?? null;
    const nodeDiagnostics = useMemo(
        () => diagnostics.filter((diagnostic) => diagnostic.nodeId === selectedNodeId),
        [diagnostics, selectedNodeId],
    );
    const indexedDiagnostics = nodeDiagnostics.map((diagnostic, index) => ({ diagnostic, index }));

    useEffect(() => {
        if (!focusFieldPath || !selectedNodeId) return;
        requestAnimationFrame(() => {
            let path = focusFieldPath;
            let field: HTMLElement | null = null;
            while (path && !field) {
                field = inspectorRef.current?.querySelector<HTMLElement>(
                    `[data-workflow-node="${CSS.escape(selectedNodeId)}"][data-workflow-field="${CSS.escape(path)}"]`,
                ) ?? null;
                const separator = Math.max(path.lastIndexOf("."), path.lastIndexOf("["));
                path = separator > 0 ? path.slice(0, separator) : "";
            }
            field?.focus();
        });
    }, [focusFieldPath, focusRequestId, selectedNodeId]);

    if (!node) {
        return (
            <div className="grid min-h-56 place-items-center px-5 text-center text-sm text-muted-foreground">
                {t("selectNodePrompt")}
            </div>
        );
    }

    const errorsFor = (fieldPath: string) => indexedDiagnostics.filter(({ diagnostic }) => (
        diagnostic.fieldPath === fieldPath
        || diagnostic.fieldPath?.endsWith(`.${fieldPath}`)
        || diagnostic.fieldPath?.startsWith(`${fieldPath}.`)
        || diagnostic.fieldPath?.startsWith(`${fieldPath}[`)
    ));
    const describedBy = (fieldPath: string) => {
        const ids = errorsFor(fieldPath).map(({ diagnostic, index }) => errorId(node.id, diagnostic, index));
        return ids.length > 0 ? ids.join(" ") : undefined;
    };
    const fieldProps = (fieldPath: string) => ({
        "data-workflow-node": node.id,
        "data-workflow-field": fieldPath,
        "aria-invalid": errorsFor(fieldPath).length > 0,
        "aria-describedby": describedBy(fieldPath),
    });

    return (
        <div ref={inspectorRef} className="space-y-5 p-5">
            <div>
                <h2 className="text-base font-semibold text-foreground">{t(`nodeType.${node.type.toLowerCase()}`)}</h2>
                <p className="mt-1 text-sm text-muted-foreground">{t(`inspectorHint.${node.type.toLowerCase()}`)}</p>
            </div>

            {node.type === "TRIGGER" ? (
                <TriggerFields
                    node={node}
                    document={document}
                    options={options}
                    readOnly={readOnly}
                    canRunAsSystem={canRunAsSystem}
                    fieldProps={fieldProps}
                    onNodeChange={onNodeChange}
                    onMetadataChange={onMetadataChange}
                    onCommitTransient={onCommitTransient}
                />
            ) : null}
            {node.type === "CONDITION" ? (
                <div
                    inert={readOnly ? true : undefined}
                    aria-disabled={readOnly}
                    className={cn(
                        "rounded-lg focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring",
                        readOnly && "opacity-70",
                    )}
                >
                    <SegmentBuilder
                        key={`${node.id}:${focusFieldPath ?? "idle"}:${focusRequestId}`}
                        definition={node.config}
                        fields={fields}
                        onChange={(config) => onNodeChange({ ...node, config }, "commit")}
                        onTransientChange={(config) => onNodeChange({ ...node, config }, "transient")}
                        onCommitTransient={onCommitTransient}
                        recordType={document.recordType ?? "deal"}
                        options={options}
                        advanced
                        triggerProps={fieldProps("config")}
                        focusPath={focusFieldPath}
                        focusDescriptionId={focusFieldPath ? describedBy(focusFieldPath) : undefined}
                        initiallyOpen={focusFieldPath?.startsWith("config.") ?? false}
                    />
                </div>
            ) : null}
            {node.type === "ACTION" ? (
                <ActionFields
                    node={node}
                    recordType={document.recordType ?? "deal"}
                    fields={fields}
                    options={options}
                    readOnly={readOnly}
                    fieldProps={fieldProps}
                    onNodeChange={onNodeChange}
                    onCommitTransient={onCommitTransient}
                />
            ) : null}
            {node.type === "DELAY" ? (
                <DelayFields
                    key={node.id}
                    node={node}
                    readOnly={readOnly}
                    fieldProps={fieldProps}
                    onNodeChange={onNodeChange}
                    onCommitTransient={onCommitTransient}
                />
            ) : null}
            {node.type === "END" ? (
                <p className="rounded-xl bg-muted/50 p-3 text-sm text-muted-foreground">{t("endExplanation")}</p>
            ) : null}

            {nodeDiagnostics.length > 0 ? (
                <div className="space-y-2 border-t border-border pt-4" aria-live="polite">
                    <h3 className="text-sm font-medium text-foreground">{t("nodeErrors")}</h3>
                    {indexedDiagnostics.map(({ diagnostic, index }) => {
                        return (
                            <p
                                key={`${diagnostic.code}-${diagnostic.edgeId ?? index}`}
                                id={errorId(node.id, diagnostic, index)}
                                className="text-sm text-destructive"
                            >
                                {diagnosticMessage(diagnostic)}
                            </p>
                        );
                    })}
                </div>
            ) : null}
        </div>
    );
}

type FieldProps = (fieldPath: string) => {
    "data-workflow-node": string;
    "data-workflow-field": string;
    "aria-invalid": boolean;
    "aria-describedby": string | undefined;
};

function TriggerFields({
    node,
    document,
    options,
    readOnly,
    canRunAsSystem,
    fieldProps,
    onNodeChange,
    onMetadataChange,
    onCommitTransient,
}: {
    node: Extract<WorkflowNode, { type: "TRIGGER" }>;
    document: WorkflowEditorDocument;
    options: RuleBuilderOptions | null;
    readOnly: boolean;
    canRunAsSystem: boolean;
    fieldProps: FieldProps;
    onNodeChange: (node: WorkflowNode, mode: ChangeMode) => void;
    onMetadataChange: (
        field: "description" | "recordType" | "executionMode",
        value: string | null,
        mode: ChangeMode,
    ) => void;
    onCommitTransient: () => void;
}) {
    const t = useTranslations("WorkspaceWorkflows");
    const tr = useTranslations("WorkspaceRules");
    const isSchedule = node.config.type === "schedule";
    const recordType = document.recordType ?? "deal";
    return (
        <div className="space-y-4">
            <LabeledField label={tr("recordType")}>
                <Select
                    value={recordType}
                    onValueChange={(value) => onMetadataChange("recordType", value, "commit")}
                    disabled={readOnly}
                >
                    <SelectTrigger size="sm" {...fieldProps("recordType")}><SelectValue /></SelectTrigger>
                    <SelectContent>
                        {RECORD_TYPES.map((type) => <SelectItem key={type} value={type}>{tr(`record.${type}`)}</SelectItem>)}
                    </SelectContent>
                </Select>
            </LabeledField>
            <LabeledField label={tr("triggerKind")}>
                <Select
                    value={node.config.type}
                    onValueChange={(type) => onNodeChange({
                        ...node,
                        config: type === "schedule"
                            ? { type, cadence: node.config.cadence ?? "daily" }
                            : { type: "entity_change", events: node.config.events ?? [] },
                    }, "commit")}
                    disabled={readOnly}
                >
                    <SelectTrigger size="sm" {...fieldProps("config.type")}><SelectValue /></SelectTrigger>
                    <SelectContent>
                        <SelectItem value="entity_change">{tr("kindEntityChange")}</SelectItem>
                        {SCHEDULE_RECORD_TYPES.includes(recordType) ? (
                            <SelectItem value="schedule">{tr("kindSchedule")}</SelectItem>
                        ) : null}
                    </SelectContent>
                </Select>
            </LabeledField>
            {isSchedule ? (
                <LabeledField label={tr("cadenceLabel")}>
                    <Select
                        value={node.config.cadence ?? "daily"}
                        onValueChange={(cadence) => onNodeChange({ ...node, config: { ...node.config, cadence } }, "commit")}
                        disabled={readOnly}
                    >
                        <SelectTrigger size="sm" {...fieldProps("config.cadence")}><SelectValue /></SelectTrigger>
                        <SelectContent>
                            {CADENCES.map((cadence) => <SelectItem key={cadence} value={cadence}>{tr(`cadence.${cadence}`)}</SelectItem>)}
                        </SelectContent>
                    </Select>
                </LabeledField>
            ) : (
                <div className="space-y-2">
                    <Label>{tr("eventsLabel")}</Label>
                    <div className="flex flex-wrap gap-1.5" {...fieldProps("config.events")} tabIndex={-1}>
                        {eventsFor(recordType).map((event) => {
                            const selected = node.config.events?.includes(event) ?? false;
                            return (
                                <button
                                    key={event}
                                    type="button"
                                    aria-pressed={selected}
                                    disabled={readOnly}
                                    onClick={() => onNodeChange({
                                        ...node,
                                        config: {
                                            ...node.config,
                                            events: selected
                                                ? (node.config.events ?? []).filter((value) => value !== event)
                                                : [...(node.config.events ?? []), event],
                                        },
                                    }, "commit")}
                                    className={cn(
                                        "inline-flex min-h-8 items-center gap-1 rounded-full px-3 py-1 text-xs font-medium ring-1 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring disabled:opacity-50",
                                        selected ? "bg-brand/15 text-foreground ring-brand" : "bg-muted text-muted-foreground ring-border hover:text-foreground",
                                    )}
                                >
                                    {selected ? <CheckIcon aria-hidden className="size-3 text-brand" /> : null}
                                    {tr(`event.${event}`)}
                                </button>
                            );
                        })}
                    </div>
                </div>
            )}
            {!isSchedule ? (
                <LabeledField label={tr("throttleLabel")}>
                    <Input
                        type="number"
                        min={1}
                        value={node.config.throttleMinutes ?? ""}
                        disabled={readOnly}
                        onChange={(event) => onNodeChange({
                            ...node,
                            config: {
                                ...node.config,
                                throttleMinutes: event.target.value ? Number(event.target.value) : undefined,
                            },
                        }, "transient")}
                        onBlur={onCommitTransient}
                        {...fieldProps("config.throttleMinutes")}
                    />
                </LabeledField>
            ) : null}
            {recordType === "deal" && !isSchedule ? (
                <LabeledField label={tr("stageFilterLabel")}>
                    <Select
                        value={node.config.targetStageId ? String(node.config.targetStageId) : "any"}
                        onValueChange={(value) => onNodeChange({
                            ...node,
                            config: { ...node.config, targetStageId: value === "any" ? undefined : Number(value) },
                        }, "commit")}
                        disabled={readOnly}
                    >
                        <SelectTrigger size="sm" {...fieldProps("config.targetStageId")}><SelectValue /></SelectTrigger>
                        <SelectContent>
                            <SelectItem value="any">{tr("anyStage")}</SelectItem>
                            {(options?.stages ?? []).map((stage) => (
                                <SelectItem key={stage.id} value={String(stage.id)}>{stage.pipeline} / {stage.name}</SelectItem>
                            ))}
                        </SelectContent>
                    </Select>
                </LabeledField>
            ) : null}
            <LabeledField label={t("descriptionLabel")}>
                <Textarea
                    value={document.description ?? ""}
                    onChange={(event) => onMetadataChange("description", event.target.value || null, "transient")}
                    onBlur={onCommitTransient}
                    disabled={readOnly}
                    maxLength={512}
                    {...fieldProps("description")}
                />
            </LabeledField>
            <div className="space-y-2 border-t border-border pt-4">
                <Label>{tr("runAsLabel")}</Label>
                <div role="radiogroup" aria-label={tr("runAsLabel")} className="grid gap-2">
                    {(["user", "system"] satisfies WorkflowExecutionMode[]).map((mode) => {
                        const Icon = mode === "system" ? BoltIcon : UserIcon;
                        const selected = document.executionMode === mode;
                        const disabled = readOnly || (mode === "system" && !canRunAsSystem);
                        return (
                            <button
                                key={mode}
                                type="button"
                                role="radio"
                                aria-checked={selected}
                                disabled={disabled}
                                onClick={() => onMetadataChange("executionMode", mode, "commit")}
                                className={cn(
                                    "flex items-start gap-2.5 rounded-xl p-3 text-left ring-1 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring disabled:cursor-not-allowed disabled:opacity-50",
                                    selected ? "bg-brand/5 ring-brand" : "bg-card ring-border hover:bg-muted/40",
                                )}
                            >
                                <Icon aria-hidden className={cn("mt-0.5 size-4 shrink-0", selected ? "text-brand" : "text-muted-foreground")} />
                                <span>
                                    <span className="block text-sm font-medium text-foreground">{tr(`runAs.${mode}.title`)}</span>
                                    <span className="block text-xs text-muted-foreground">{tr(`runAs.${mode}.hint`)}</span>
                                </span>
                            </button>
                        );
                    })}
                </div>
                {!canRunAsSystem ? <p className="text-xs text-muted-foreground">{t("systemAuthoringRestricted")}</p> : null}
            </div>
        </div>
    );
}

function ActionFields({
    node,
    recordType,
    fields,
    options,
    readOnly,
    fieldProps,
    onNodeChange,
    onCommitTransient,
}: {
    node: Extract<WorkflowNode, { type: "ACTION" }>;
    recordType: string;
    fields: SegmentFields | null;
    options: RuleBuilderOptions | null;
    readOnly: boolean;
    fieldProps: FieldProps;
    onNodeChange: (node: WorkflowNode, mode: ChangeMode) => void;
    onCommitTransient: () => void;
}) {
    const tr = useTranslations("WorkspaceRules");
    const update = (config: RuleAction, mode: ChangeMode) => onNodeChange({ ...node, config }, mode);
    const textInput = (field: "title" | "body" | "activityType", placeholder: string, maximum: number) => (
        <Input
            value={node.config[field] ?? ""}
            onChange={(event) => update({ ...node.config, [field]: event.target.value }, "transient")}
            onBlur={onCommitTransient}
            placeholder={placeholder}
            disabled={readOnly}
            maxLength={maximum}
            {...fieldProps(`config.${field}`)}
        />
    );
    return (
        <div className="space-y-4">
            <LabeledField label={tr("actionType")}>
                <Select value={node.config.type} onValueChange={(type) => update({ type }, "commit")} disabled={readOnly}>
                    <SelectTrigger size="sm" {...fieldProps("config.type")}><SelectValue /></SelectTrigger>
                    <SelectContent>
                        {actionsFor(recordType).map((type) => <SelectItem key={type} value={type}>{tr(`action.${type}`)}</SelectItem>)}
                    </SelectContent>
                </Select>
            </LabeledField>
            {(node.config.type === "create_task" || node.config.type === "notify")
                ? <LabeledField label={tr("actionTitlePlaceholder")}>{textInput("title", tr("actionTitlePlaceholder"), 255)}</LabeledField>
                : null}
            {node.config.type === "notify"
                ? <LabeledField label={tr("actionBodyPlaceholder")}>{textInput("body", tr("actionBodyPlaceholder"), 2_000)}</LabeledField>
                : null}
            {node.config.type === "create_note"
                ? <LabeledField label={tr("actionNotePlaceholder")}>{textInput("body", tr("actionNotePlaceholder"), 2_000)}</LabeledField>
                : null}
            {node.config.type === "create_task" ? (
                <LabeledField label={tr("dueIn")}>
                    <Input
                        type="number"
                        min={1}
                        value={node.config.dueInDays ?? 3}
                        onChange={(event) => update({ ...node.config, dueInDays: Number(event.target.value) }, "transient")}
                        onBlur={onCommitTransient}
                        disabled={readOnly}
                        {...fieldProps("config.dueInDays")}
                    />
                </LabeledField>
            ) : null}
            {node.config.type === "log_activity" ? (
                <>
                    <LabeledField label={tr("activityTypePlaceholder")}>
                        {textInput("activityType", tr("activityTypePlaceholder"), 32)}
                    </LabeledField>
                    <LabeledField label={tr("activitySubjectPlaceholder")}>
                        {textInput("title", tr("activitySubjectPlaceholder"), 255)}
                    </LabeledField>
                </>
            ) : null}
            {(node.config.type === "add_tag" || node.config.type === "remove_tag") ? (
                <LabeledField label={tr("tag")}>
                    <Select
                        value={node.config.tagId ? String(node.config.tagId) : undefined}
                        onValueChange={(value) => update({ ...node.config, tagId: Number(value) }, "commit")}
                        disabled={readOnly}
                    >
                        <SelectTrigger size="sm" {...fieldProps("config.tagId")}><SelectValue placeholder={tr("pickTag")} /></SelectTrigger>
                        <SelectContent>
                            {(fields?.tags ?? []).map((tag) => <SelectItem key={tag.id} value={String(tag.id)}>{tag.name}</SelectItem>)}
                        </SelectContent>
                    </Select>
                </LabeledField>
            ) : null}
            {node.config.type === "assign_owner" ? (
                <LabeledField label={tr("actionOwner")}>
                    <Select
                        value={node.config.targetUserId ? String(node.config.targetUserId) : undefined}
                        onValueChange={(value) => update({ ...node.config, targetUserId: Number(value) }, "commit")}
                        disabled={readOnly}
                    >
                        <SelectTrigger size="sm" {...fieldProps("config.targetUserId")}><SelectValue placeholder={tr("pickOwner")} /></SelectTrigger>
                        <SelectContent>
                            {(options?.owners ?? []).map((owner) => <SelectItem key={owner.id} value={String(owner.id)}>{owner.name}</SelectItem>)}
                        </SelectContent>
                    </Select>
                </LabeledField>
            ) : null}
            {node.config.type === "change_stage" ? (
                <LabeledField label={tr("actionStage")}>
                    <Select
                        value={node.config.targetStageId ? String(node.config.targetStageId) : undefined}
                        onValueChange={(value) => update({ ...node.config, targetStageId: Number(value) }, "commit")}
                        disabled={readOnly}
                    >
                        <SelectTrigger size="sm" {...fieldProps("config.targetStageId")}><SelectValue placeholder={tr("pickStage")} /></SelectTrigger>
                        <SelectContent>
                            {(options?.stages ?? []).map((stage) => (
                                <SelectItem key={stage.id} value={String(stage.id)}>{stage.pipeline} / {stage.name}</SelectItem>
                            ))}
                        </SelectContent>
                    </Select>
                </LabeledField>
            ) : null}
        </div>
    );
}

function DelayFields({
    node,
    readOnly,
    fieldProps,
    onNodeChange,
    onCommitTransient,
}: {
    node: Extract<WorkflowNode, { type: "DELAY" }>;
    readOnly: boolean;
    fieldProps: FieldProps;
    onNodeChange: (node: WorkflowNode, mode: ChangeMode) => void;
    onCommitTransient: () => void;
}) {
    const t = useTranslations("WorkspaceWorkflows");
    const duration = node.config.durationSeconds;
    const [unit, setUnit] = useState<"minutes" | "hours" | "days">(
        duration > 0 && duration % 86_400 === 0
            ? "days"
            : duration > 0 && duration % 3_600 === 0
                ? "hours"
                : "minutes",
    );
    const divisor = unit === "days" ? 86_400 : unit === "hours" ? 3_600 : 60;
    const update = (seconds: number, mode: ChangeMode) => onNodeChange({
        ...node,
        config: { durationSeconds: Math.max(0, Math.round(seconds)) },
    }, mode);
    return (
        <LabeledField label={t("delayDurationLabel")} hint={t("delayBoundsHint")}>
            <div className="grid grid-cols-[minmax(0,1fr)_auto] gap-2">
                <Input
                    type="number"
                    min={Math.ceil(WORKFLOW_DELAY_MIN_SECONDS / divisor)}
                    max={Math.floor(WORKFLOW_DELAY_MAX_SECONDS / divisor)}
                    value={duration / divisor}
                    onChange={(event) => update(Number(event.target.value) * divisor, "transient")}
                    onBlur={onCommitTransient}
                    disabled={readOnly}
                    {...fieldProps("config.durationSeconds")}
                />
                <Select
                    value={unit}
                    onValueChange={(nextUnit) => {
                        setUnit(nextUnit === "days" ? "days" : nextUnit === "hours" ? "hours" : "minutes");
                    }}
                    disabled={readOnly}
                >
                    <SelectTrigger size="sm" aria-label={t("delayUnitLabel")}><SelectValue /></SelectTrigger>
                    <SelectContent>
                        <SelectItem value="minutes">{t("durationUnit.minutes")}</SelectItem>
                        <SelectItem value="hours">{t("durationUnit.hours")}</SelectItem>
                        <SelectItem value="days">{t("durationUnit.days")}</SelectItem>
                    </SelectContent>
                </Select>
            </div>
        </LabeledField>
    );
}

function LabeledField({ label, hint, children }: { label: string; hint?: string; children: React.ReactNode }) {
    return (
        <div className="space-y-1.5">
            <Label>{label}</Label>
            {children}
            {hint ? <p className="text-xs text-muted-foreground">{hint}</p> : null}
        </div>
    );
}
