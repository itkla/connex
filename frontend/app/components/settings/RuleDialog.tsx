"use client";

import { useState } from "react";
import { useTranslations } from "next-intl";
import { PlusIcon, TrashIcon } from "@heroicons/react/24/outline";

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
import SegmentBuilder, { EMPTY_DEFINITION } from "@/app/components/records/SegmentBuilder";
import type { Rule, RuleAction, RuleRequest, RuleTrigger, SegmentDefinition, SegmentFields } from "@/app/lib/types";

const DEAL_EVENTS = ["deal.created", "deal.stage_changed", "deal.won", "deal.lost", "deal.updated"];
const COMPANY_EVENTS = ["company.created", "company.updated"];
const CADENCES = ["hourly", "daily", "weekly"];
const EXECUTION_MODES = ["user", "system"] as const;

function eventsFor(recordType: string): string[] {
    return recordType === "deal" ? DEAL_EVENTS : COMPANY_EVENTS;
}

function actionsFor(recordType: string): string[] {
    return recordType === "deal" ? ["create_task", "log_activity", "add_tag", "notify"] : ["add_tag", "notify"];
}

function defaultAction(): RuleAction {
    return { type: "notify", title: "", body: "" };
}

type Props = {
    open: boolean;
    onOpenChange: (open: boolean) => void;
    editing: Rule | null;
    fields: SegmentFields | null;
    onSubmit: (payload: RuleRequest) => Promise<void>;
};

/**
 * Create/edit dialog for an automation rule. The field state lives in {@link RuleForm}, remounted per
 * target via {@code key} so it initializes from props without an effect; the save lifecycle lives here.
 */
export default function RuleDialog({ open, onOpenChange, editing, fields, onSubmit }: Props) {
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
    isSaving,
    onSubmit,
}: {
    editing: Rule | null;
    fields: SegmentFields | null;
    isSaving: boolean;
    onSubmit: (payload: RuleRequest) => void;
}) {
    const t = useTranslations("WorkspaceRules");
    const [name, setName] = useState(editing?.name ?? "");
    const [recordType, setRecordType] = useState<string>(editing?.recordType ?? "deal");
    const [triggerType, setTriggerType] = useState<string>(editing?.trigger.type ?? "entity_change");
    const [events, setEvents] = useState<string[]>(editing?.trigger.events ?? []);
    const [cadence, setCadence] = useState<string>(editing?.trigger.cadence ?? "daily");
    const [condition, setCondition] = useState<SegmentDefinition>(editing?.condition ?? EMPTY_DEFINITION);
    const [actions, setActions] = useState<RuleAction[]>(editing?.actions?.length ? editing.actions : [defaultAction()]);
    const [executionMode, setExecutionMode] = useState<"user" | "system">(editing?.executionMode ?? "user");
    const [error, setError] = useState<string | null>(null);

    const isCompany = recordType === "company";
    const isSchedule = triggerType === "schedule";

    const changeRecordType = (next: string) => {
        setRecordType(next);
        setEvents([]);
        if (next === "deal") {
            setTriggerType("entity_change");
        }
        setActions((prev) => prev.map((action) => (actionsFor(next).includes(action.type) ? action : defaultAction())));
    };

    const toggleEvent = (event: string) => {
        setEvents((prev) => (prev.includes(event) ? prev.filter((value) => value !== event) : [...prev, event]));
    };

    const setAction = (index: number, action: RuleAction) =>
        setActions((prev) => prev.map((existing, i) => (i === index ? action : existing)));
    const addAction = () => setActions((prev) => [...prev, defaultAction()]);
    const removeAction = (index: number) => setActions((prev) => (prev.length > 1 ? prev.filter((_, i) => i !== index) : prev));

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
        const conditionPayload = isCompany && condition.conditions.length > 0 ? condition : undefined;
        if (isSchedule && !conditionPayload) {
            setError(t("conditionRequired"));
            return;
        }
        const trigger: RuleTrigger = isSchedule
            ? { type: "schedule", cadence }
            : { type: "entity_change", events };
        onSubmit({ name: name.trim(), recordType, trigger, condition: conditionPayload, actions, executionMode });
    };

    return (
        <>
            <DialogHeader>
                <DialogTitle>{editing ? t("editTitle") : t("createTitle")}</DialogTitle>
                <DialogDescription>{t("createDescription")}</DialogDescription>
            </DialogHeader>

            <div className="flex max-h-[60vh] flex-col gap-5 overflow-y-auto px-1">
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

                <Section label={t("whenLabel")}>
                    <div className="flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
                        <Select value={recordType} onValueChange={changeRecordType}>
                            <SelectTrigger size="sm" aria-label={t("recordType")} className="w-32"><SelectValue /></SelectTrigger>
                            <SelectContent>
                                <SelectItem value="deal">{t("record.deal")}</SelectItem>
                                <SelectItem value="company">{t("record.company")}</SelectItem>
                            </SelectContent>
                        </Select>
                        {isCompany && (
                            <Select value={triggerType} onValueChange={setTriggerType}>
                                <SelectTrigger size="sm" aria-label={t("triggerKind")} className="w-48"><SelectValue /></SelectTrigger>
                                <SelectContent>
                                    <SelectItem value="entity_change">{t("kindEntityChange")}</SelectItem>
                                    <SelectItem value="schedule">{t("kindSchedule")}</SelectItem>
                                </SelectContent>
                            </Select>
                        )}
                    </div>

                    {triggerType === "entity_change" ? (
                        <div className="flex flex-wrap gap-1.5">
                            {eventsFor(recordType).map((event) => (
                                <button
                                    key={event}
                                    type="button"
                                    aria-pressed={events.includes(event)}
                                    onClick={() => toggleEvent(event)}
                                    className={cn(
                                        "rounded-full px-3 py-1 text-xs font-medium ring-1 transition active:scale-[0.97]",
                                        events.includes(event)
                                            ? "bg-brand text-white ring-brand"
                                            : "bg-muted text-muted-foreground ring-border hover:text-foreground",
                                    )}
                                >
                                    {t(`event.${event}`)}
                                </button>
                            ))}
                        </div>
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
                </Section>

                {isCompany && (
                    <Section label={isSchedule ? t("conditionRequiredLabel") : t("conditionLabel")}>
                        <SegmentBuilder definition={condition} fields={fields} onChange={setCondition} />
                    </Section>
                )}

                <Section label={t("thenLabel")}>
                    <div className="flex flex-col gap-2">
                        {actions.map((action, index) => (
                            <ActionRow
                                key={index}
                                action={action}
                                recordType={recordType}
                                fields={fields}
                                onChange={(next) => setAction(index, next)}
                                onRemove={actions.length > 1 ? () => removeAction(index) : undefined}
                            />
                        ))}
                        <Button
                            type="button"
                            variant="ghost"
                            size="sm"
                            onClick={addAction}
                            className="self-start gap-1 text-brand hover:text-brand-hover"
                        >
                            <PlusIcon className="size-4" />
                            {t("addAction")}
                        </Button>
                    </div>
                </Section>

                <Section label={t("runAsLabel")}>
                    <div className="grid grid-cols-2 gap-2">
                        {EXECUTION_MODES.map((mode) => (
                            <button
                                key={mode}
                                type="button"
                                aria-pressed={executionMode === mode}
                                onClick={() => setExecutionMode(mode)}
                                className={cn(
                                    "rounded-lg px-3 py-2 text-left ring-1 transition active:scale-[0.99]",
                                    executionMode === mode ? "bg-brand/10 ring-brand" : "bg-card ring-border hover:bg-muted/50",
                                )}
                            >
                                <span className="block text-sm font-medium text-foreground">{t(`runAs.${mode}.title`)}</span>
                                <span className="block text-xs text-muted-foreground">{t(`runAs.${mode}.hint`)}</span>
                            </button>
                        ))}
                    </div>
                </Section>

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

function Section({ label, children }: { label: string; children: React.ReactNode }) {
    return (
        <div className="space-y-2">
            <h3 className="text-xs font-medium tracking-[0.12em] text-muted-foreground uppercase">{label}</h3>
            {children}
        </div>
    );
}

function ActionRow({
    action,
    recordType,
    fields,
    onChange,
    onRemove,
}: {
    action: RuleAction;
    recordType: string;
    fields: SegmentFields | null;
    onChange: (action: RuleAction) => void;
    onRemove?: () => void;
}) {
    const t = useTranslations("WorkspaceRules");
    return (
        <div className="flex items-start gap-2 rounded-lg bg-muted/40 p-2 ring-1 ring-border">
            <Select value={action.type} onValueChange={(type) => onChange({ type })}>
                <SelectTrigger size="sm" aria-label={t("actionType")} className="w-40 shrink-0 bg-card"><SelectValue /></SelectTrigger>
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
                        className="h-9 bg-card"
                    />
                )}
                {action.type === "notify" && (
                    <Input
                        value={action.body ?? ""}
                        onChange={(event) => onChange({ ...action, body: event.target.value })}
                        placeholder={t("actionBodyPlaceholder")}
                        aria-label={t("actionBodyPlaceholder")}
                        maxLength={2000}
                        className="h-9 bg-card"
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
                            className="h-9 w-16 bg-card"
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
                            className="h-9 bg-card"
                        />
                        <Input
                            value={action.title ?? ""}
                            onChange={(event) => onChange({ ...action, title: event.target.value })}
                            placeholder={t("activitySubjectPlaceholder")}
                            aria-label={t("activitySubjectPlaceholder")}
                            maxLength={255}
                            className="h-9 bg-card"
                        />
                    </>
                )}
                {action.type === "add_tag" && (
                    <Select value={action.tagId ? String(action.tagId) : undefined} onValueChange={(value) => onChange({ ...action, tagId: Number(value) })}>
                        <SelectTrigger size="sm" aria-label={t("tag")} className="bg-card"><SelectValue placeholder={t("pickTag")} /></SelectTrigger>
                        <SelectContent>
                            {(fields?.tags ?? []).map((tag) => (
                                <SelectItem key={tag.id} value={String(tag.id)}>{tag.name}</SelectItem>
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
