"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import { useTranslations } from "next-intl";
import { useTheme } from "next-themes";
import { AnimatePresence, motion, useReducedMotion } from "motion/react";
import {
    ReactFlow,
    ReactFlowProvider,
    Background,
    Controls,
    type Edge,
    type NodeMouseHandler,
} from "@xyflow/react";
import "@xyflow/react/dist/style.css";
import {
    ArrowDownIcon,
    ArrowUpIcon,
    BoltIcon,
    CheckIcon,
    ChevronLeftIcon,
    FunnelIcon,
    PlayIcon,
    PlusIcon,
    TrashIcon,
    UserIcon,
} from "@heroicons/react/24/outline";
import { Loader2Icon } from "lucide-react";

import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
    Select,
    SelectContent,
    SelectItem,
    SelectTrigger,
    SelectValue,
} from "@/components/ui/select";
import { Skeleton } from "@/components/ui/skeleton";
import { Switch } from "@/components/ui/switch";
import { cn } from "@/lib/utils";
import {
    createRule,
    getActiveWorkspaceMembers,
    getCompanies,
    getPipelines,
    getRuleById,
    getSegmentFields,
    getStagesByPipelineId,
    previewRule,
    updateRule,
} from "@/app/lib/api";
import { useWorkspace } from "@/app/hooks/useWorkspace";
import { toastError, toastSuccess } from "@/app/lib/toast";
import { easeOut } from "@/app/lib/motion";
import SegmentBuilder, { EMPTY_DEFINITION } from "@/app/components/records/SegmentBuilder";
import WorkflowNode, { type WorkflowFlowNode } from "@/app/components/settings/workflows/WorkflowNode";
import {
    CADENCES,
    RECORD_TYPES,
    SCHEDULE_RECORD_TYPES,
    SEGMENT_RECORD_TYPES,
    actionsFor,
    defaultAction,
    eventsFor,
} from "@/app/components/settings/workflows/vocabulary";
import type {
    Rule,
    RuleAction,
    RuleBuilderOptions,
    RulePreview,
    RuleRequest,
    RuleTrigger,
    SavedViewRecordType,
    SegmentDefinition,
    SegmentFields,
} from "@/app/lib/types";

type Selection = "trigger" | "condition" | `action-${number}`;

const NODE_TYPES = { workflowStep: WorkflowNode };

const LIST_URL = "/settings/workflows";

/**
 * Full-page linear workflow editor over the existing rules engine. The single source of truth is
 * the draft rule state; the canvas is a derived projection used for orientation and selection,
 * while all editing happens in the inspector — which keeps the surface fully keyboard-operable
 * and lets the same steps render as a plain outline below the {@code lg} breakpoint.
 */
export default function WorkflowEditor({ ruleId }: { ruleId?: number }) {
    return (
        <ReactFlowProvider>
            <EditorBody ruleId={ruleId} />
        </ReactFlowProvider>
    );
}

function EditorBody({ ruleId }: { ruleId?: number }) {
    const t = useTranslations("WorkspaceWorkflows");
    const tr = useTranslations("WorkspaceRules");
    const router = useRouter();
    const { resolvedTheme } = useTheme();
    const reduce = useReducedMotion() ?? false;
    const { activeWorkspaceId, activeWorkspace } = useWorkspace();
    const canRunAsSystem = activeWorkspace?.role === "owner" || activeWorkspace?.role === "admin";

    const [loading, setLoading] = useState(true);
    const [missing, setMissing] = useState(false);
    const [accessDenied, setAccessDenied] = useState(false);
    const [fields, setFields] = useState<SegmentFields | null>(null);
    const [options, setOptions] = useState<RuleBuilderOptions | null>(null);
    const [editing, setEditing] = useState<Rule | null>(null);

    const [name, setName] = useState("");
    const [enabled, setEnabled] = useState(true);
    const [recordType, setRecordType] = useState("deal");
    const [triggerType, setTriggerType] = useState("entity_change");
    const [events, setEvents] = useState<string[]>([]);
    const [cadence, setCadence] = useState("daily");
    const [targetStageId, setTargetStageId] = useState<number | undefined>(undefined);
    const [throttle, setThrottle] = useState("");
    const [condition, setCondition] = useState<SegmentDefinition>(EMPTY_DEFINITION);
    const [actions, setActions] = useState<RuleAction[]>([defaultAction("deal")]);
    const [executionMode, setExecutionMode] = useState<"user" | "system">("user");

    const [selection, setSelection] = useState<Selection>("trigger");
    const [error, setError] = useState<string | null>(null);
    const [invalidStep, setInvalidStep] = useState<Selection | null>(null);
    const [preview, setPreview] = useState<RulePreview | null>(null);
    const [previewing, setPreviewing] = useState(false);
    const [saving, setSaving] = useState(false);

    useEffect(() => {
        if (!activeWorkspaceId) return;
        let cancelled = false;
        (async () => {
            setLoading(true);
            setAccessDenied(false);
            setMissing(false);
            if (ruleId != null) {
                try {
                    const rule = await getRuleById(ruleId);
                    if (cancelled) return;
                    setEditing(rule);
                    setName(rule.name);
                    setEnabled(rule.enabled);
                    setRecordType(rule.recordType);
                    setTriggerType(rule.trigger.type);
                    setEvents(rule.trigger.events ?? []);
                    setCadence(rule.trigger.cadence ?? "daily");
                    setTargetStageId(rule.trigger.targetStageId);
                    setThrottle(rule.trigger.throttleMinutes ? String(rule.trigger.throttleMinutes) : "");
                    setCondition(rule.condition ?? EMPTY_DEFINITION);
                    setActions(rule.actions.length ? rule.actions : [defaultAction(rule.recordType)]);
                    setExecutionMode(rule.executionMode);
                } catch (err) {
                    if (!cancelled) {
                        if (err instanceof Error && "status" in err && (err as { status?: number }).status === 403) {
                            setAccessDenied(true);
                        } else {
                            setMissing(true);
                        }
                        setLoading(false);
                    }
                    return;
                }
            }
            if (!cancelled) setLoading(false);
            try {
                const loadedFields = await getSegmentFields("company");
                if (!cancelled) setFields(loadedFields);
            } catch {
                if (!cancelled) toastError(tr("fieldsLoadFailed"));
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
    }, [activeWorkspaceId, ruleId, tr]);

    const supportsCondition = SEGMENT_RECORD_TYPES.includes(recordType);
    const supportsSchedule = SCHEDULE_RECORD_TYPES.includes(recordType);
    const isSchedule = triggerType === "schedule";
    const hasCondition = (condition.conditions?.length ?? 0) > 0 || (condition.groups?.length ?? 0) > 0;
    const canFilterStage = recordType === "deal" && !isSchedule;

    const changeRecordType = (next: string) => {
        setRecordType(next);
        setEvents([]);
        setTargetStageId(undefined);
        setPreview(null);
        setCondition(EMPTY_DEFINITION);
        if (!SCHEDULE_RECORD_TYPES.includes(next)) {
            setTriggerType("entity_change");
        }
        setActions((prev) => prev.map((action) => (actionsFor(next).includes(action.type) ? action : defaultAction(next))));
        if (!SEGMENT_RECORD_TYPES.includes(next) && selection === "condition") {
            setSelection("trigger");
        }
    };

    const toggleEvent = (event: string) =>
        setEvents((prev) => (prev.includes(event) ? prev.filter((value) => value !== event) : [...prev, event]));

    const setAction = (index: number, action: RuleAction) =>
        setActions((prev) => prev.map((existing, i) => (i === index ? action : existing)));

    const addAction = () => {
        setActions((prev) => [...prev, defaultAction(recordType)]);
        setSelection(`action-${actions.length}`);
    };

    const removeAction = (index: number) => {
        if (actions.length <= 1) return;
        setActions((prev) => prev.filter((_, i) => i !== index));
        setSelection(index > 0 ? `action-${index - 1}` : "trigger");
    };

    const moveAction = (index: number, direction: -1 | 1) => {
        const target = index + direction;
        if (target < 0 || target >= actions.length) return;
        setActions((prev) => {
            const next = [...prev];
            [next[index], next[target]] = [next[target], next[index]];
            return next;
        });
        setSelection(`action-${target}`);
    };

    const runPreview = async () => {
        setPreviewing(true);
        setPreview(null);
        try {
            setPreview(await previewRule(recordType as SavedViewRecordType, condition));
        } catch {
            setError(tr("previewFailed"));
        } finally {
            setPreviewing(false);
        }
    };

    const triggerSummary = isSchedule
        ? tr("summarySchedule", { cadence: tr(`cadence.${cadence}`) })
        : events.length
            ? events.map((event) => tr(`event.${event}`)).join(", ")
            : tr("previewAnyChange");

    const conditionSummary = hasCondition
        ? t("conditionSummarySet", { count: (condition.conditions?.length ?? 0) + (condition.groups?.length ?? 0) })
        : isSchedule
            ? t("conditionSummaryRequired")
            : t("conditionSummaryAny");

    const actionSummary = useCallback(
        (action: RuleAction) =>
            action.title?.trim() || action.body?.trim() || action.activityType?.trim() || tr(`action.${action.type}`),
        [tr],
    );

    const steps = useMemo(() => {
        const list: { key: Selection; label: string; summary: string }[] = [
            { key: "trigger", label: tr(`record.${recordType}`), summary: triggerSummary },
        ];
        if (supportsCondition) {
            list.push({ key: "condition", label: t("conditionStep"), summary: conditionSummary });
        }
        actions.forEach((action, index) => {
            list.push({ key: `action-${index}`, label: tr(`action.${action.type}`), summary: actionSummary(action) });
        });
        return list;
    }, [tr, t, recordType, triggerSummary, supportsCondition, conditionSummary, actions, actionSummary]);

    const nodes = useMemo<WorkflowFlowNode[]>(() => {
        const stepNodes: WorkflowFlowNode[] = steps.map((step, index) => ({
            id: step.key,
            type: "workflowStep",
            position: { x: 0, y: index * 128 },
            draggable: false,
            data: {
                kind: step.key === "trigger" ? "trigger" : step.key === "condition" ? "condition" : "action",
                label: step.label,
                summary: step.summary,
                selected: selection === step.key,
                invalid: invalidStep === step.key,
            },
        }));
        stepNodes.push({
            id: "add-step",
            type: "workflowStep",
            position: { x: 0, y: steps.length * 128 },
            draggable: false,
            data: { kind: "add", label: t("addStep"), summary: "", selected: false, invalid: false },
        });
        return stepNodes;
    }, [steps, selection, invalidStep, t]);

    const edges = useMemo<Edge[]>(() => {
        const ids = [...steps.map((step) => step.key), "add-step"];
        return ids.slice(0, -1).map((id, index) => ({
            id: `e-${id}`,
            source: id,
            target: ids[index + 1],
            type: "smoothstep",
            selectable: false,
            style: { stroke: "var(--color-chart-grid)", strokeWidth: 1.5 },
        }));
    }, [steps]);

    const onNodeClick = useCallback<NodeMouseHandler>((_, node) => {
        if (node.id === "add-step") {
            setActions((prev) => [...prev, defaultAction(recordType)]);
            setSelection(`action-${actions.length}` as Selection);
            return;
        }
        setSelection(node.id as Selection);
    }, [recordType, actions.length]);

    const validate = (): RuleRequest | null => {
        setError(null);
        setInvalidStep(null);
        if (executionMode === "system" && !canRunAsSystem) {
            setError(tr("systemRunAsRestricted"));
            setSelection("trigger");
            return null;
        }
        if (!name.trim()) {
            setError(tr("nameRequired"));
            return null;
        }
        if (!isSchedule && events.length === 0) {
            setError(tr("eventsRequired"));
            setInvalidStep("trigger");
            setSelection("trigger");
            return null;
        }
        const conditionPayload = supportsCondition && hasCondition ? condition : undefined;
        if (isSchedule && !conditionPayload) {
            setError(tr("conditionRequired"));
            setInvalidStep("condition");
            setSelection("condition");
            return null;
        }
        for (let index = 0; index < actions.length; index += 1) {
            const action = actions[index];
            const fail = (key: string) => {
                setError(tr(key));
                setInvalidStep(`action-${index}`);
                setSelection(`action-${index}`);
            };
            if ((action.type === "create_task" || action.type === "notify") && !action.title?.trim()) {
                fail("actionTitleRequired");
                return null;
            }
            if (action.type === "log_activity" && !action.activityType?.trim()) {
                fail("actionActivityTypeRequired");
                return null;
            }
            if ((action.type === "add_tag" || action.type === "remove_tag") && !action.tagId) {
                fail("actionTagRequired");
                return null;
            }
            if (action.type === "create_note" && !action.body?.trim()) {
                fail("actionNoteRequired");
                return null;
            }
            if (action.type === "assign_owner" && !action.targetUserId) {
                fail("actionOwnerRequired");
                return null;
            }
            if (action.type === "change_stage" && !action.targetStageId) {
                fail("actionStageRequired");
                return null;
            }
        }
        const throttleMinutes = Number(throttle);
        const trigger: RuleTrigger = isSchedule
            ? { type: "schedule", cadence }
            : {
                  type: "entity_change",
                  events,
                  ...(canFilterStage && targetStageId ? { targetStageId } : {}),
                  ...(throttle && throttleMinutes > 0 ? { throttleMinutes } : {}),
              };
        return {
            name: name.trim(),
            description: editing?.description,
            enabled,
            recordType,
            trigger,
            condition: conditionPayload,
            actions: actions.map((action) =>
                action.type === "create_task" && action.dueInDays == null ? { ...action, dueInDays: 3 } : action,
            ),
            executionMode,
        };
    };

    const save = async () => {
        const payload = validate();
        if (!payload) return;
        setSaving(true);
        try {
            if (editing) {
                await updateRule(editing.id, payload);
                toastSuccess(t("updated"));
            } else {
                await createRule(payload);
                toastSuccess(t("created"));
            }
            router.push(LIST_URL);
            router.refresh();
        } catch (err) {
            toastError(err instanceof Error ? err.message : t("saveFailed"));
            setSaving(false);
        }
    };

    if (accessDenied) {
        return (
            <div className="rounded-2xl border border-border bg-card px-4 py-10 text-center text-sm text-muted-foreground">
                {tr("noAccess")}
            </div>
        );
    }
    if (missing) {
        return (
            <div className="rounded-2xl border border-border bg-card px-4 py-10 text-center">
                <p className="text-sm text-muted-foreground">{t("notFound")}</p>
                <Button variant="outline" size="sm" className="mt-3" onClick={() => router.push(LIST_URL)}>
                    {t("backToList")}
                </Button>
            </div>
        );
    }
    if (loading) {
        return (
            <div className="space-y-4">
                <Skeleton className="h-10 w-full max-w-xl" />
                <div className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_380px]">
                    <Skeleton className="hidden h-[480px] rounded-2xl lg:block" />
                    <Skeleton className="h-[480px] rounded-2xl" />
                </div>
            </div>
        );
    }

    return (
        <div className="flex flex-col gap-4">
            <div className="flex flex-wrap items-center gap-3">
                <Button variant="ghost" size="icon-sm" aria-label={t("backToList")} onClick={() => router.push(LIST_URL)}>
                    <ChevronLeftIcon className="size-4" />
                </Button>
                <Input
                    value={name}
                    onChange={(event) => setName(event.target.value)}
                    placeholder={tr("ruleNamePlaceholder")}
                    aria-label={tr("ruleName")}
                    maxLength={128}
                    className="h-9 w-full max-w-md flex-1"
                />
                <label className="flex items-center gap-2 text-sm text-muted-foreground">
                    <Switch checked={enabled} onCheckedChange={setEnabled} aria-label={t("enabledLabel")} />
                    {t("enabledLabel")}
                </label>
                <Button variant="brand" onClick={save} disabled={saving} className="ml-auto">
                    {saving ? <Loader2Icon className="size-4 animate-spin" /> : t("save")}
                </Button>
            </div>
            {error && <p className="text-sm text-destructive">{error}</p>}

            <div className="relative grid items-start gap-6 lg:grid-cols-[minmax(0,1fr)_400px]">
                <div className="hidden h-[calc(100dvh-19rem)] min-h-[480px] overflow-hidden rounded-2xl border border-border bg-muted/20 lg:block">
                    <ReactFlow
                        nodes={nodes}
                        edges={edges}
                        nodeTypes={NODE_TYPES}
                        onNodeClick={onNodeClick}
                        colorMode={resolvedTheme === "dark" ? "dark" : "light"}
                        fitView
                        fitViewOptions={{ padding: 0.25, maxZoom: 1 }}
                        nodesDraggable={false}
                        nodesConnectable={false}
                        panOnScroll
                        proOptions={{ hideAttribution: false }}
                    >
                        <Background gap={24} />
                        <Controls position="bottom-right" showInteractive={false} />
                    </ReactFlow>
                </div>

                <ol
                    className="flex flex-col gap-2 lg:sr-only lg:focus-within:not-sr-only lg:focus-within:absolute lg:focus-within:left-3 lg:focus-within:top-3 lg:focus-within:z-20 lg:focus-within:w-80 lg:focus-within:rounded-2xl lg:focus-within:border lg:focus-within:border-border lg:focus-within:bg-card lg:focus-within:p-3 lg:focus-within:shadow-lg"
                    aria-label={t("outlineLabel")}
                >
                    {steps.map((step) => (
                        <li key={step.key}>
                            <button
                                type="button"
                                onClick={() => setSelection(step.key)}
                                aria-current={selection === step.key ? "step" : undefined}
                                className={cn(
                                    "flex w-full items-center gap-2.5 rounded-xl bg-card p-3 text-left ring-1 transition focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring",
                                    invalidStep === step.key
                                        ? "ring-destructive"
                                        : selection === step.key
                                            ? "ring-2 ring-brand"
                                            : "ring-border hover:bg-muted/40",
                                )}
                            >
                                <StepIcon step={step.key} />
                                <span className="min-w-0">
                                    <span className="block truncate text-sm font-medium text-foreground">{step.label}</span>
                                    <span className="block truncate text-xs text-muted-foreground">{step.summary}</span>
                                </span>
                            </button>
                        </li>
                    ))}
                    <li>
                        <Button type="button" variant="ghost" size="sm" onClick={addAction} className="gap-1 text-brand hover:text-brand-hover">
                            <PlusIcon className="size-4" />
                            {t("addStep")}
                        </Button>
                    </li>
                </ol>

                <aside className="rounded-2xl border border-border bg-card p-4 lg:sticky lg:top-24">
                    <AnimatePresence mode="wait" initial={false}>
                    <motion.div
                        key={selection === "trigger" || selection === "condition" ? selection : "action"}
                        initial={reduce ? false : { opacity: 0 }}
                        animate={{ opacity: 1 }}
                        exit={reduce ? { opacity: 1 } : { opacity: 0 }}
                        transition={{ duration: 0.15, ease: easeOut }}
                    >
                    {selection === "trigger" && (
                        <TriggerInspector
                            t={t}
                            tr={tr}
                            recordType={recordType}
                            triggerType={triggerType}
                            events={events}
                            cadence={cadence}
                            targetStageId={targetStageId}
                            throttle={throttle}
                            supportsSchedule={supportsSchedule}
                            canFilterStage={canFilterStage}
                            options={options}
                            canRunAsSystem={canRunAsSystem}
                            executionMode={executionMode}
                            onRecordType={changeRecordType}
                            onTriggerType={setTriggerType}
                            onToggleEvent={toggleEvent}
                            onCadence={setCadence}
                            onStage={setTargetStageId}
                            onThrottle={setThrottle}
                            onExecutionMode={setExecutionMode}
                        />
                    )}
                    {selection === "condition" && supportsCondition && (
                        <div className="space-y-3">
                            <InspectorTitle title={t("conditionStep")} hint={isSchedule ? tr("conditionRequiredLabel") : tr("conditionLabel")} />
                            <SegmentBuilder
                                definition={condition}
                                fields={fields}
                                onChange={(next) => {
                                    setCondition(next);
                                    setPreview(null);
                                }}
                                recordType={recordType}
                                options={options}
                                advanced
                            />
                            {hasCondition && (
                                <Button type="button" variant="ghost" size="sm" onClick={runPreview} disabled={previewing} className="text-muted-foreground hover:text-foreground">
                                    {previewing ? tr("previewing") : tr("previewButton")}
                                </Button>
                            )}
                            {preview && (
                                <div className="rounded-lg bg-muted/50 px-3 py-2 text-sm">
                                    <p className="font-medium text-foreground">{tr("previewCount", { count: preview.matchCount })}</p>
                                    {preview.sample.length > 0 && (
                                        <p className="mt-0.5 truncate text-xs text-muted-foreground">
                                            {preview.sample.map((record) => record.label).join(", ")}
                                        </p>
                                    )}
                                </div>
                            )}
                        </div>
                    )}
                    {selection.startsWith("action-") && (
                        <ActionInspector
                            t={t}
                            tr={tr}
                            index={Number(selection.slice("action-".length))}
                            actions={actions}
                            recordType={recordType}
                            fields={fields}
                            options={options}
                            onChange={setAction}
                            onRemove={removeAction}
                            onMove={moveAction}
                        />
                    )}
                    </motion.div>
                    </AnimatePresence>
                </aside>
            </div>
        </div>
    );
}

function StepIcon({ step }: { step: Selection }) {
    const Icon = step === "trigger" ? PlayIcon : step === "condition" ? FunnelIcon : BoltIcon;
    return (
        <span className={cn(
            "grid size-8 shrink-0 place-items-center rounded-lg",
            step === "trigger" ? "bg-brand text-brand-foreground" : "bg-muted text-foreground",
        )}>
            <Icon aria-hidden className="size-4" />
        </span>
    );
}

function InspectorTitle({ title, hint }: { title: string; hint?: string }) {
    return (
        <div>
            <h2 className="text-sm font-semibold text-foreground">{title}</h2>
            {hint && <p className="mt-0.5 text-xs text-muted-foreground">{hint}</p>}
        </div>
    );
}

type Translator = ReturnType<typeof useTranslations>;

function TriggerInspector({
    t, tr, recordType, triggerType, events, cadence, targetStageId, throttle,
    supportsSchedule, canFilterStage, options, canRunAsSystem, executionMode,
    onRecordType, onTriggerType, onToggleEvent, onCadence, onStage, onThrottle, onExecutionMode,
}: {
    t: Translator;
    tr: Translator;
    recordType: string;
    triggerType: string;
    events: string[];
    cadence: string;
    targetStageId?: number;
    throttle: string;
    supportsSchedule: boolean;
    canFilterStage: boolean;
    options: RuleBuilderOptions | null;
    canRunAsSystem: boolean;
    executionMode: "user" | "system";
    onRecordType: (value: string) => void;
    onTriggerType: (value: string) => void;
    onToggleEvent: (event: string) => void;
    onCadence: (value: string) => void;
    onStage: (value: number | undefined) => void;
    onThrottle: (value: string) => void;
    onExecutionMode: (value: "user" | "system") => void;
}) {
    const isSchedule = triggerType === "schedule";
    return (
        <div className="space-y-4">
            <InspectorTitle title={t("triggerStep")} hint={t("triggerHint")} />
            <div className="flex flex-wrap items-center gap-2">
                <Select value={recordType} onValueChange={onRecordType}>
                    <SelectTrigger size="sm" aria-label={tr("recordType")} className="w-32"><SelectValue /></SelectTrigger>
                    <SelectContent>
                        {RECORD_TYPES.map((type) => (
                            <SelectItem key={type} value={type}>{tr(`record.${type}`)}</SelectItem>
                        ))}
                    </SelectContent>
                </Select>
                {supportsSchedule && (
                    <Select value={triggerType} onValueChange={onTriggerType}>
                        <SelectTrigger size="sm" aria-label={tr("triggerKind")} className="w-44"><SelectValue /></SelectTrigger>
                        <SelectContent>
                            <SelectItem value="entity_change">{tr("kindEntityChange")}</SelectItem>
                            <SelectItem value="schedule">{tr("kindSchedule")}</SelectItem>
                        </SelectContent>
                    </Select>
                )}
            </div>
            {isSchedule ? (
                <div className="flex items-center gap-2 text-sm text-muted-foreground">
                    <span>{tr("everyPrefix")}</span>
                    <Select value={cadence} onValueChange={onCadence}>
                        <SelectTrigger size="sm" aria-label={tr("cadenceLabel")} className="w-32"><SelectValue /></SelectTrigger>
                        <SelectContent>
                            {CADENCES.map((value) => (
                                <SelectItem key={value} value={value}>{tr(`cadence.${value}`)}</SelectItem>
                            ))}
                        </SelectContent>
                    </Select>
                </div>
            ) : (
                <>
                    <div className="flex flex-wrap gap-1.5">
                        {eventsFor(recordType).map((event) => {
                            const on = events.includes(event);
                            return (
                                <button
                                    key={event}
                                    type="button"
                                    aria-pressed={on}
                                    onClick={() => onToggleEvent(event)}
                                    className={cn(
                                        "inline-flex items-center gap-1 rounded-full px-3 py-1 text-xs font-medium ring-1 transition active:scale-[0.97] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring",
                                        on
                                            ? "bg-brand/15 text-foreground ring-brand"
                                            : "bg-muted text-muted-foreground ring-border hover:text-foreground",
                                    )}
                                >
                                    {on && <CheckIcon aria-hidden className="size-3 text-brand" />}
                                    {tr(`event.${event}`)}
                                </button>
                            );
                        })}
                    </div>
                    {canFilterStage && (
                        <div className="flex items-center gap-2 text-sm text-muted-foreground">
                            <span>{tr("stageFilterLabel")}</span>
                            <Select
                                value={targetStageId ? String(targetStageId) : "any"}
                                onValueChange={(value) => onStage(value === "any" ? undefined : Number(value))}
                            >
                                <SelectTrigger size="sm" aria-label={tr("stageFilterLabel")} className="w-44"><SelectValue /></SelectTrigger>
                                <SelectContent>
                                    <SelectItem value="any">{tr("anyStage")}</SelectItem>
                                    {(options?.stages ?? []).map((stage) => (
                                        <SelectItem key={stage.id} value={String(stage.id)}>{stage.pipeline} · {stage.name}</SelectItem>
                                    ))}
                                </SelectContent>
                            </Select>
                        </div>
                    )}
                    <div className="flex items-center gap-1.5 text-sm text-muted-foreground">
                        <span>{tr("throttlePrefix")}</span>
                        <Input
                            type="number"
                            min={1}
                            value={throttle}
                            onChange={(event) => onThrottle(event.target.value)}
                            placeholder={tr("throttleOff")}
                            aria-label={tr("throttleLabel")}
                            className="h-9 w-20"
                        />
                        <span>{tr("throttleSuffix")}</span>
                    </div>
                </>
            )}

            <div className="space-y-2 border-t border-border pt-4">
                <Label>{tr("runAsLabel")}</Label>
                {canRunAsSystem ? (
                    <div role="radiogroup" aria-label={tr("runAsLabel")} className="grid gap-2">
                        {(["user", "system"] as const).map((mode) => {
                            const Icon = mode === "system" ? BoltIcon : UserIcon;
                            const selected = executionMode === mode;
                            return (
                                <button
                                    key={mode}
                                    type="button"
                                    role="radio"
                                    aria-checked={selected}
                                    onClick={() => onExecutionMode(mode)}
                                    className={cn(
                                        "flex items-start gap-2.5 rounded-xl p-3 text-left ring-1 transition active:scale-[0.99] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring",
                                        selected ? "bg-brand/5 ring-brand" : "bg-card ring-border hover:bg-muted/40",
                                    )}
                                >
                                    <Icon aria-hidden className={cn("mt-0.5 size-4 shrink-0", selected ? "text-brand" : "text-muted-foreground")} />
                                    <span className="min-w-0">
                                        <span className="block text-sm font-medium text-foreground">{tr(`runAs.${mode}.title`)}</span>
                                        <span className="block text-xs text-muted-foreground">{tr(`runAs.${mode}.hint`)}</span>
                                    </span>
                                </button>
                            );
                        })}
                    </div>
                ) : (
                    <p className="flex items-start gap-2 rounded-lg bg-muted/50 px-3 py-2 text-xs text-muted-foreground">
                        <UserIcon aria-hidden className="mt-0.5 size-3.5 shrink-0" />
                        {tr("runAs.user.hint")}
                    </p>
                )}
            </div>
        </div>
    );
}

function ActionInspector({
    t, tr, index, actions, recordType, fields, options, onChange, onRemove, onMove,
}: {
    t: Translator;
    tr: Translator;
    index: number;
    actions: RuleAction[];
    recordType: string;
    fields: SegmentFields | null;
    options: RuleBuilderOptions | null;
    onChange: (index: number, action: RuleAction) => void;
    onRemove: (index: number) => void;
    onMove: (index: number, direction: -1 | 1) => void;
}) {
    const action = actions[index];
    if (!action) return null;
    return (
        <div className="space-y-4">
            <div className="flex items-start justify-between gap-2">
                <InspectorTitle title={t("actionStep", { step: index + 1 })} />
                <div className="flex items-center gap-1">
                    <Button type="button" variant="ghost" size="icon-sm" aria-label={t("moveUp")} disabled={index === 0} onClick={() => onMove(index, -1)}>
                        <ArrowUpIcon className="size-4" />
                    </Button>
                    <Button type="button" variant="ghost" size="icon-sm" aria-label={t("moveDown")} disabled={index === actions.length - 1} onClick={() => onMove(index, 1)}>
                        <ArrowDownIcon className="size-4" />
                    </Button>
                    <Button type="button" variant="ghost" size="icon-sm" aria-label={tr("removeAction")} disabled={actions.length <= 1} onClick={() => onRemove(index)} className="text-muted-foreground">
                        <TrashIcon className="size-4" />
                    </Button>
                </div>
            </div>
            <Select value={action.type} onValueChange={(type) => onChange(index, { type })}>
                <SelectTrigger size="sm" aria-label={tr("actionType")} className="w-full"><SelectValue /></SelectTrigger>
                <SelectContent>
                    {actionsFor(recordType).map((type) => (
                        <SelectItem key={type} value={type}>{tr(`action.${type}`)}</SelectItem>
                    ))}
                </SelectContent>
            </Select>
            {(action.type === "create_task" || action.type === "notify") && (
                <Input
                    value={action.title ?? ""}
                    onChange={(event) => onChange(index, { ...action, title: event.target.value })}
                    placeholder={tr("actionTitlePlaceholder")}
                    aria-label={tr("actionTitlePlaceholder")}
                    maxLength={255}
                    className="h-9"
                />
            )}
            {action.type === "notify" && (
                <Input
                    value={action.body ?? ""}
                    onChange={(event) => onChange(index, { ...action, body: event.target.value })}
                    placeholder={tr("actionBodyPlaceholder")}
                    aria-label={tr("actionBodyPlaceholder")}
                    maxLength={2000}
                    className="h-9"
                />
            )}
            {action.type === "create_note" && (
                <Input
                    value={action.body ?? ""}
                    onChange={(event) => onChange(index, { ...action, body: event.target.value })}
                    placeholder={tr("actionNotePlaceholder")}
                    aria-label={tr("actionNotePlaceholder")}
                    maxLength={2000}
                    className="h-9"
                />
            )}
            {action.type === "create_task" && (
                <div className="flex items-center gap-1.5 text-sm text-muted-foreground">
                    <span>{tr("dueIn")}</span>
                    <Input
                        type="number"
                        min={1}
                        value={action.dueInDays ?? 3}
                        onChange={(event) => onChange(index, { ...action, dueInDays: Math.max(1, Number(event.target.value) || 3) })}
                        aria-label={tr("dueIn")}
                        className="h-9 w-16"
                    />
                    <span>{tr("days")}</span>
                </div>
            )}
            {action.type === "log_activity" && (
                <>
                    <Input
                        value={action.activityType ?? ""}
                        onChange={(event) => onChange(index, { ...action, activityType: event.target.value })}
                        placeholder={tr("activityTypePlaceholder")}
                        aria-label={tr("activityTypePlaceholder")}
                        maxLength={32}
                        className="h-9"
                    />
                    <Input
                        value={action.title ?? ""}
                        onChange={(event) => onChange(index, { ...action, title: event.target.value })}
                        placeholder={tr("activitySubjectPlaceholder")}
                        aria-label={tr("activitySubjectPlaceholder")}
                        maxLength={255}
                        className="h-9"
                    />
                </>
            )}
            {(action.type === "add_tag" || action.type === "remove_tag") && (
                <Select value={action.tagId ? String(action.tagId) : undefined} onValueChange={(value) => onChange(index, { ...action, tagId: Number(value) })}>
                    <SelectTrigger size="sm" aria-label={tr("tag")}><SelectValue placeholder={tr("pickTag")} /></SelectTrigger>
                    <SelectContent>
                        {(fields?.tags ?? []).map((tag) => (
                            <SelectItem key={tag.id} value={String(tag.id)}>{tag.name}</SelectItem>
                        ))}
                    </SelectContent>
                </Select>
            )}
            {action.type === "assign_owner" && (
                <Select value={action.targetUserId ? String(action.targetUserId) : undefined} onValueChange={(value) => onChange(index, { ...action, targetUserId: Number(value) })}>
                    <SelectTrigger size="sm" aria-label={tr("actionOwner")}><SelectValue placeholder={tr("pickOwner")} /></SelectTrigger>
                    <SelectContent>
                        {(options?.owners ?? []).map((owner) => (
                            <SelectItem key={owner.id} value={String(owner.id)}>{owner.name}</SelectItem>
                        ))}
                    </SelectContent>
                </Select>
            )}
            {action.type === "change_stage" && (
                <Select value={action.targetStageId ? String(action.targetStageId) : undefined} onValueChange={(value) => onChange(index, { ...action, targetStageId: Number(value) })}>
                    <SelectTrigger size="sm" aria-label={tr("actionStage")}><SelectValue placeholder={tr("pickStage")} /></SelectTrigger>
                    <SelectContent>
                        {(options?.stages ?? []).map((stage) => (
                            <SelectItem key={stage.id} value={String(stage.id)}>{stage.pipeline} · {stage.name}</SelectItem>
                        ))}
                    </SelectContent>
                </Select>
            )}
        </div>
    );
}
