"use client";

import { useState } from "react";
import { useTranslations } from "next-intl";
import { AnimatePresence, motion, useReducedMotion } from "motion/react";
import { BoltIcon, CheckIcon, PlusIcon, TrashIcon, UserIcon } from "@heroicons/react/24/outline";

import {
    Dialog,
    DialogClose,
    DialogContent,
    DialogDescription,
    DialogFooter,
    DialogHeader,
    DialogTitle,
} from "@/components/ui/dialog";
import {
    Select,
    SelectContent,
    SelectItem,
    SelectTrigger,
    SelectValue,
} from "@/components/ui/select";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { cn } from "@/lib/utils";
import { previewRule } from "@/app/lib/api";
import SegmentBuilder, { EMPTY_DEFINITION } from "@/app/components/records/SegmentBuilder";
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

const RECORD_TYPES = ["deal", "company", "person", "task"];
const EVENTS: Record<string, string[]> = {
    deal: ["deal.created", "deal.stage_changed", "deal.updated", "deal.won", "deal.lost", "deal.owner_changed", "deal.value_changed"],
    company: ["company.created", "company.updated"],
    person: ["person.created", "person.updated", "person.job_changed"],
    task: ["task.created", "task.completed"],
};
const ACTIONS: Record<string, string[]> = {
    deal: ["create_task", "log_activity", "add_tag", "remove_tag", "create_note", "assign_owner", "change_stage", "notify"],
    company: ["add_tag", "remove_tag", "notify"],
    person: ["create_task", "log_activity", "add_tag", "remove_tag", "create_note", "notify"],
    task: ["notify"],
};
const CADENCES = ["hourly", "daily", "weekly"];
const EXECUTION_MODES = ["user", "system"] as const;
const SEGMENT_RECORD_TYPES = ["company", "person", "deal"];
const SCHEDULE_RECORD_TYPES = ["company", "person", "deal"];

function eventsFor(recordType: string): string[] {
    return EVENTS[recordType] ?? [];
}

function actionsFor(recordType: string): string[] {
    return ACTIONS[recordType] ?? ["notify"];
}

function defaultAction(recordType: string): RuleAction {
    return actionsFor(recordType).includes("notify") ? { type: "notify", title: "", body: "" } : { type: actionsFor(recordType)[0] };
}

type Props = {
    open: boolean;
    onOpenChange: (open: boolean) => void;
    editing: Rule | null;
    fields: SegmentFields | null;
    options: RuleBuilderOptions | null;
    onSubmit: (payload: RuleRequest) => Promise<void>;
};

/**
 * Create/edit dialog for an automation rule. The field state lives in {@link RuleForm}, remounted per
 * target via {@code key} so it initializes from props without an effect; the save lifecycle lives here.
 */
export default function RuleDialog({ open, onOpenChange, editing, fields, options, onSubmit }: Props) {
    const [isSaving, setIsSaving] = useState(false);

    const handleOpenChange = (next: boolean) => {
        if (!next && isSaving) return;
        onOpenChange(next);
    };

    const handleSubmit = async (payload: RuleRequest) => {
        setIsSaving(true);
        try {
            await onSubmit(payload);
            setIsSaving(false);
            onOpenChange(false);
        } catch {
            setIsSaving(false);
        }
    };

    return (
        <Dialog open={open} onOpenChange={handleOpenChange}>
            <DialogContent className="sm:max-w-xl">
                {open && (
                    <RuleForm
                        key={editing ? `edit-${editing.id}` : "new"}
                        editing={editing}
                        fields={fields}
                        options={options}
                        isSaving={isSaving}
                        onSubmit={handleSubmit}
                    />
                )}
            </DialogContent>
        </Dialog>
    );
}

function RuleForm({
    editing,
    fields,
    options,
    isSaving,
    onSubmit,
}: {
    editing: Rule | null;
    fields: SegmentFields | null;
    options: RuleBuilderOptions | null;
    isSaving: boolean;
    onSubmit: (payload: RuleRequest) => void;
}) {
    const t = useTranslations("WorkspaceRules");
    const reduce = useReducedMotion() ?? false;
    const [name, setName] = useState(editing?.name ?? "");
    const [recordType, setRecordType] = useState<string>(editing?.recordType ?? "deal");
    const [triggerType, setTriggerType] = useState<string>(editing?.trigger.type ?? "entity_change");
    const [events, setEvents] = useState<string[]>(editing?.trigger.events ?? []);
    const [cadence, setCadence] = useState<string>(editing?.trigger.cadence ?? "daily");
    const [targetStageId, setTargetStageId] = useState<number | undefined>(editing?.trigger.targetStageId);
    const [throttle, setThrottle] = useState<string>(editing?.trigger.throttleMinutes ? String(editing.trigger.throttleMinutes) : "");
    const [condition, setCondition] = useState<SegmentDefinition>(editing?.condition ?? EMPTY_DEFINITION);
    const [actions, setActions] = useState<RuleAction[]>(editing?.actions?.length ? editing.actions : [defaultAction(editing?.recordType ?? "deal")]);
    const [executionMode, setExecutionMode] = useState<"user" | "system">(editing?.executionMode ?? "user");
    const [error, setError] = useState<string | null>(null);
    const [preview, setPreview] = useState<RulePreview | null>(null);
    const [previewing, setPreviewing] = useState(false);

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
    };

    const toggleEvent = (event: string) => {
        setEvents((prev) => (prev.includes(event) ? prev.filter((value) => value !== event) : [...prev, event]));
    };

    const setAction = (index: number, action: RuleAction) =>
        setActions((prev) => prev.map((existing, i) => (i === index ? action : existing)));
    const addAction = () => setActions((prev) => [...prev, defaultAction(recordType)]);
    const removeAction = (index: number) => setActions((prev) => (prev.length > 1 ? prev.filter((_, i) => i !== index) : prev));

    const runPreview = async () => {
        setPreviewing(true);
        setPreview(null);
        try {
            const result = await previewRule(recordType as SavedViewRecordType, condition);
            setPreview(result);
        } catch {
            setError(t("previewFailed"));
        } finally {
            setPreviewing(false);
        }
    };

    const previewTrigger = isSchedule
        ? t("summarySchedule", { cadence: t(`cadence.${cadence}`) })
        : t("summaryEntity", {
              record: t(`record.${recordType}`),
              events: events.length ? events.map((event) => t(`event.${event}`)).join(", ") : t("previewAnyChange"),
          });
    const summary = t("summaryFull", {
        trigger: previewTrigger,
        actions: actions.map((action) => t(`action.${action.type}`)).join(", "),
    });

    const submit = () => {
        setError(null);
        if (!name.trim()) {
            setError(t("nameRequired"));
            return;
        }
        if (triggerType === "entity_change" && events.length === 0) {
            setError(t("eventsRequired"));
            return;
        }
        const conditionPayload = supportsCondition && hasCondition ? condition : undefined;
        if (isSchedule && !conditionPayload) {
            setError(t("conditionRequired"));
            return;
        }
        for (const action of actions) {
            if ((action.type === "create_task" || action.type === "notify") && !action.title?.trim()) {
                setError(t("actionTitleRequired"));
                return;
            }
            if (action.type === "log_activity" && !action.activityType?.trim()) {
                setError(t("actionActivityTypeRequired"));
                return;
            }
            if ((action.type === "add_tag" || action.type === "remove_tag") && !action.tagId) {
                setError(t("actionTagRequired"));
                return;
            }
            if (action.type === "create_note" && !action.body?.trim()) {
                setError(t("actionNoteRequired"));
                return;
            }
            if (action.type === "assign_owner" && !action.targetUserId) {
                setError(t("actionOwnerRequired"));
                return;
            }
            if (action.type === "change_stage" && !action.targetStageId) {
                setError(t("actionStageRequired"));
                return;
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
        const normalizedActions = actions.map((action) =>
            action.type === "create_task" && action.dueInDays == null ? { ...action, dueInDays: 3 } : action,
        );
        onSubmit({
            name: name.trim(),
            description: editing?.description,
            enabled: editing?.enabled ?? true,
            recordType,
            trigger,
            condition: conditionPayload,
            actions: normalizedActions,
            executionMode,
        });
    };

    return (
        <>
            <DialogHeader>
                <DialogTitle>{editing ? t("editTitle") : t("createTitle")}</DialogTitle>
                <DialogDescription>{t("createDescription")}</DialogDescription>
            </DialogHeader>

            <div className="flex max-h-[62vh] flex-col gap-5 overflow-y-auto px-1 py-1">
                <div className="space-y-1.5">
                    <Label htmlFor="rule-name">{t("ruleName")}</Label>
                    <Input
                        id="rule-name"
                        value={name}
                        onChange={(event) => setName(event.target.value)}
                        placeholder={t("ruleNamePlaceholder")}
                        maxLength={128}
                    />
                </div>

                <p className="flex items-start gap-2 rounded-lg bg-muted/50 px-3 py-2.5 text-sm leading-relaxed text-foreground">
                    <BoltIcon aria-hidden className="mt-0.5 size-4 shrink-0 text-brand" />
                    <span>{summary}</span>
                </p>

                <Field label={t("whenLabel")}>
                    <div className="flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
                        <Select value={recordType} onValueChange={changeRecordType}>
                            <SelectTrigger size="sm" aria-label={t("recordType")} className="w-32"><SelectValue /></SelectTrigger>
                            <SelectContent>
                                {RECORD_TYPES.map((type) => (
                                    <SelectItem key={type} value={type}>{t(`record.${type}`)}</SelectItem>
                                ))}
                            </SelectContent>
                        </Select>
                        {supportsSchedule && (
                            <Select value={triggerType} onValueChange={setTriggerType}>
                                <SelectTrigger size="sm" aria-label={t("triggerKind")} className="w-48"><SelectValue /></SelectTrigger>
                                <SelectContent>
                                    <SelectItem value="entity_change">{t("kindEntityChange")}</SelectItem>
                                    <SelectItem value="schedule">{t("kindSchedule")}</SelectItem>
                                </SelectContent>
                            </Select>
                        )}
                    </div>

                    <AnimatePresence mode="wait" initial={false}>
                        <motion.div
                            key={triggerType}
                            initial={reduce ? false : { opacity: 0 }}
                            animate={{ opacity: 1 }}
                            exit={reduce ? { opacity: 1 } : { opacity: 0 }}
                            transition={{ duration: 0.15, ease: [0.23, 1, 0.32, 1] }}
                            className="space-y-2.5"
                        >
                            {triggerType === "entity_change" ? (
                                <>
                                    <div className="flex flex-wrap gap-1.5">
                                        {eventsFor(recordType).map((event) => {
                                            const on = events.includes(event);
                                            return (
                                                <button
                                                    key={event}
                                                    type="button"
                                                    aria-pressed={on}
                                                    onClick={() => toggleEvent(event)}
                                                    className={cn(
                                                        "inline-flex items-center gap-1 rounded-full px-3 py-1 text-xs font-medium ring-1 transition active:scale-[0.97] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring",
                                                        on
                                                            ? "bg-brand/15 text-foreground ring-brand"
                                                            : "bg-muted text-muted-foreground ring-border hover:text-foreground",
                                                    )}
                                                >
                                                    {on && <CheckIcon aria-hidden className="size-3 text-brand" />}
                                                    {t(`event.${event}`)}
                                                </button>
                                            );
                                        })}
                                    </div>
                                    {canFilterStage && (
                                        <div className="flex items-center gap-2 text-sm text-muted-foreground">
                                            <span>{t("stageFilterLabel")}</span>
                                            <Select
                                                value={targetStageId ? String(targetStageId) : "any"}
                                                onValueChange={(value) => setTargetStageId(value === "any" ? undefined : Number(value))}
                                            >
                                                <SelectTrigger size="sm" aria-label={t("stageFilterLabel")} className="w-48"><SelectValue /></SelectTrigger>
                                                <SelectContent>
                                                    <SelectItem value="any">{t("anyStage")}</SelectItem>
                                                    {(options?.stages ?? []).map((stage) => (
                                                        <SelectItem key={stage.id} value={String(stage.id)}>{stage.pipeline} · {stage.name}</SelectItem>
                                                    ))}
                                                </SelectContent>
                                            </Select>
                                        </div>
                                    )}
                                    <div className="flex items-center gap-1.5 text-sm text-muted-foreground">
                                        <span>{t("throttlePrefix")}</span>
                                        <Input
                                            type="number"
                                            min={1}
                                            value={throttle}
                                            onChange={(event) => setThrottle(event.target.value)}
                                            placeholder={t("throttleOff")}
                                            aria-label={t("throttleLabel")}
                                            className="h-9 w-20"
                                        />
                                        <span>{t("throttleSuffix")}</span>
                                    </div>
                                </>
                            ) : (
                                <div className="flex items-center gap-2 text-sm text-muted-foreground">
                                    <span>{t("everyPrefix")}</span>
                                    <Select value={cadence} onValueChange={setCadence}>
                                        <SelectTrigger size="sm" aria-label={t("cadenceLabel")} className="w-32"><SelectValue /></SelectTrigger>
                                        <SelectContent>
                                            {CADENCES.map((value) => (
                                                <SelectItem key={value} value={value}>{t(`cadence.${value}`)}</SelectItem>
                                            ))}
                                        </SelectContent>
                                    </Select>
                                </div>
                            )}
                        </motion.div>
                    </AnimatePresence>
                </Field>

                {supportsCondition && (
                    <Field label={isSchedule ? t("conditionRequiredLabel") : t("conditionLabel")}>
                        <div className="flex flex-wrap items-center gap-2">
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
                                    {previewing ? t("previewing") : t("previewButton")}
                                </Button>
                            )}
                        </div>
                        {preview && (
                            <div className="rounded-lg bg-muted/50 px-3 py-2 text-sm">
                                <p className="font-medium text-foreground">{t("previewCount", { count: preview.matchCount })}</p>
                                {preview.sample.length > 0 && (
                                    <p className="mt-0.5 truncate text-xs text-muted-foreground">
                                        {preview.sample.map((record) => record.label).join(", ")}
                                    </p>
                                )}
                            </div>
                        )}
                    </Field>
                )}

                <Field label={t("thenLabel")}>
                    <div className="divide-y divide-border overflow-hidden rounded-xl ring-1 ring-border">
                        {actions.map((action, index) => (
                            <ActionRow
                                key={index}
                                action={action}
                                recordType={recordType}
                                fields={fields}
                                options={options}
                                onChange={(next) => setAction(index, next)}
                                onRemove={actions.length > 1 ? () => removeAction(index) : undefined}
                            />
                        ))}
                    </div>
                    <Button
                        type="button"
                        variant="ghost"
                        size="sm"
                        onClick={addAction}
                        className="gap-1 text-brand hover:text-brand-hover"
                    >
                        <PlusIcon className="size-4" />
                        {t("addAction")}
                    </Button>
                </Field>

                <Field label={t("runAsLabel")}>
                    <div role="radiogroup" aria-label={t("runAsLabel")} className="grid grid-cols-2 gap-2">
                        {EXECUTION_MODES.map((mode) => {
                            const Icon = mode === "system" ? BoltIcon : UserIcon;
                            const selected = executionMode === mode;
                            return (
                                <button
                                    key={mode}
                                    type="button"
                                    role="radio"
                                    aria-checked={selected}
                                    onClick={() => setExecutionMode(mode)}
                                    className={cn(
                                        "flex items-start gap-2.5 rounded-xl p-3 text-left ring-1 transition active:scale-[0.99] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring",
                                        selected ? "bg-brand/5 ring-brand" : "bg-card ring-border hover:bg-muted/40",
                                    )}
                                >
                                    <Icon aria-hidden className={cn("mt-0.5 size-4 shrink-0", selected ? "text-brand" : "text-muted-foreground")} />
                                    <span className="min-w-0">
                                        <span className="block text-sm font-medium text-foreground">{t(`runAs.${mode}.title`)}</span>
                                        <span className="block text-xs text-muted-foreground">{t(`runAs.${mode}.hint`)}</span>
                                    </span>
                                </button>
                            );
                        })}
                    </div>
                </Field>

                {error && <p className="text-sm text-destructive">{error}</p>}
            </div>

            <DialogFooter>
                <DialogClose asChild>
                    <Button variant="outline" disabled={isSaving}>{t("cancel")}</Button>
                </DialogClose>
                <Button onClick={submit} disabled={isSaving} className="bg-brand text-white hover:bg-brand-hover">
                    {isSaving ? t("saving") : t("save")}
                </Button>
            </DialogFooter>
        </>
    );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
    return (
        <div className="space-y-2">
            <h3 className="text-[13px] font-medium text-foreground">{label}</h3>
            {children}
        </div>
    );
}

function ActionRow({
    action,
    recordType,
    fields,
    options,
    onChange,
    onRemove,
}: {
    action: RuleAction;
    recordType: string;
    fields: SegmentFields | null;
    options: RuleBuilderOptions | null;
    onChange: (action: RuleAction) => void;
    onRemove?: () => void;
}) {
    const t = useTranslations("WorkspaceRules");
    return (
        <div className="flex items-start gap-2 p-3">
            <Select value={action.type} onValueChange={(type) => onChange({ type })}>
                <SelectTrigger size="sm" aria-label={t("actionType")} className="w-40 shrink-0"><SelectValue /></SelectTrigger>
                <SelectContent>
                    {actionsFor(recordType).map((type) => (
                        <SelectItem key={type} value={type}>{t(`action.${type}`)}</SelectItem>
                    ))}
                </SelectContent>
            </Select>
            <div className="flex min-w-0 flex-1 flex-col gap-2">
                {(action.type === "create_task" || action.type === "notify") && (
                    <Input
                        value={action.title ?? ""}
                        onChange={(event) => onChange({ ...action, title: event.target.value })}
                        placeholder={t("actionTitlePlaceholder")}
                        aria-label={t("actionTitlePlaceholder")}
                        maxLength={255}
                        className="h-9"
                    />
                )}
                {action.type === "notify" && (
                    <Input
                        value={action.body ?? ""}
                        onChange={(event) => onChange({ ...action, body: event.target.value })}
                        placeholder={t("actionBodyPlaceholder")}
                        aria-label={t("actionBodyPlaceholder")}
                        maxLength={2000}
                        className="h-9"
                    />
                )}
                {action.type === "create_note" && (
                    <Input
                        value={action.body ?? ""}
                        onChange={(event) => onChange({ ...action, body: event.target.value })}
                        placeholder={t("actionNotePlaceholder")}
                        aria-label={t("actionNotePlaceholder")}
                        maxLength={2000}
                        className="h-9"
                    />
                )}
                {action.type === "create_task" && (
                    <div className="flex items-center gap-1.5 text-sm text-muted-foreground">
                        <span>{t("dueIn")}</span>
                        <Input
                            type="number"
                            min={1}
                            value={action.dueInDays ?? 3}
                            onChange={(event) => onChange({ ...action, dueInDays: Math.max(1, Number(event.target.value) || 3) })}
                            aria-label={t("dueIn")}
                            className="h-9 w-16"
                        />
                        <span>{t("days")}</span>
                    </div>
                )}
                {action.type === "log_activity" && (
                    <>
                        <Input
                            value={action.activityType ?? ""}
                            onChange={(event) => onChange({ ...action, activityType: event.target.value })}
                            placeholder={t("activityTypePlaceholder")}
                            aria-label={t("activityTypePlaceholder")}
                            maxLength={32}
                            className="h-9"
                        />
                        <Input
                            value={action.title ?? ""}
                            onChange={(event) => onChange({ ...action, title: event.target.value })}
                            placeholder={t("activitySubjectPlaceholder")}
                            aria-label={t("activitySubjectPlaceholder")}
                            maxLength={255}
                            className="h-9"
                        />
                    </>
                )}
                {(action.type === "add_tag" || action.type === "remove_tag") && (
                    <Select value={action.tagId ? String(action.tagId) : undefined} onValueChange={(value) => onChange({ ...action, tagId: Number(value) })}>
                        <SelectTrigger size="sm" aria-label={t("tag")}><SelectValue placeholder={t("pickTag")} /></SelectTrigger>
                        <SelectContent>
                            {(fields?.tags ?? []).map((tag) => (
                                <SelectItem key={tag.id} value={String(tag.id)}>{tag.name}</SelectItem>
                            ))}
                        </SelectContent>
                    </Select>
                )}
                {action.type === "assign_owner" && (
                    <Select value={action.targetUserId ? String(action.targetUserId) : undefined} onValueChange={(value) => onChange({ ...action, targetUserId: Number(value) })}>
                        <SelectTrigger size="sm" aria-label={t("actionOwner")}><SelectValue placeholder={t("pickOwner")} /></SelectTrigger>
                        <SelectContent>
                            {(options?.owners ?? []).map((owner) => (
                                <SelectItem key={owner.id} value={String(owner.id)}>{owner.name}</SelectItem>
                            ))}
                        </SelectContent>
                    </Select>
                )}
                {action.type === "change_stage" && (
                    <Select value={action.targetStageId ? String(action.targetStageId) : undefined} onValueChange={(value) => onChange({ ...action, targetStageId: Number(value) })}>
                        <SelectTrigger size="sm" aria-label={t("actionStage")}><SelectValue placeholder={t("pickStage")} /></SelectTrigger>
                        <SelectContent>
                            {(options?.stages ?? []).map((stage) => (
                                <SelectItem key={stage.id} value={String(stage.id)}>{stage.pipeline} · {stage.name}</SelectItem>
                            ))}
                        </SelectContent>
                    </Select>
                )}
            </div>
            {onRemove && (
                <Button type="button" variant="ghost" size="icon-sm" onClick={onRemove} aria-label={t("removeAction")} className="shrink-0 text-muted-foreground">
                    <TrashIcon className="size-4" />
                </Button>
            )}
        </div>
    );
}
