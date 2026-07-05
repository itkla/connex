"use client";

import { useState } from "react";
import { useTranslations } from "next-intl";
import { PlusIcon, SparklesIcon, XMarkIcon } from "@heroicons/react/24/outline";

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
    SelectGroup,
    SelectItem,
    SelectLabel,
    SelectTrigger,
    SelectValue,
} from "@/components/ui/select";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { cn } from "@/lib/utils";
import type {
    RuleBuilderOptions,
    SegmentCondition,
    SegmentDefinition,
    SegmentFields,
    SegmentMatch,
} from "@/app/lib/types";

type FieldKind = "string" | "number" | "id" | "enum" | "tag" | "date";

const PREDICATE_KEYS = ["warm_intro_available", "open_deal", "cooling", "no_activity"];
const STATUS_VALUES = ["open", "won", "lost"];
const DEFAULT_DAYS = 30;
const MAX_DEPTH = 4;
const MAX_GROUP_CONDITIONS = 16;

const FIELDS: Record<string, { field: string; kind: FieldKind }[]> = {
    company: [
        { field: "industry", kind: "string" },
        { field: "name", kind: "string" },
        { field: "tag", kind: "tag" },
    ],
    person: [
        { field: "name", kind: "string" },
        { field: "title", kind: "string" },
        { field: "email", kind: "string" },
        { field: "company", kind: "id" },
        { field: "tag", kind: "tag" },
    ],
    deal: [
        { field: "name", kind: "string" },
        { field: "value", kind: "number" },
        { field: "stage", kind: "id" },
        { field: "owner", kind: "id" },
        { field: "status", kind: "enum" },
        { field: "close_date", kind: "date" },
        { field: "tag", kind: "tag" },
    ],
};

type OperatorOption = { token: string; op: string; negate: boolean };

function operatorsForKind(kind: FieldKind): OperatorOption[] {
    switch (kind) {
        case "string":
            return [
                { token: "is", op: "equals", negate: false },
                { token: "isNot", op: "equals", negate: true },
                { token: "contains", op: "contains", negate: false },
                { token: "notContains", op: "contains", negate: true },
                { token: "startsWith", op: "starts_with", negate: false },
                { token: "isSet", op: "is_set", negate: false },
                { token: "isEmpty", op: "is_set", negate: true },
            ];
        case "number":
            return [
                { token: "eq", op: "equals", negate: false },
                { token: "gt", op: "gt", negate: false },
                { token: "gte", op: "gte", negate: false },
                { token: "lt", op: "lt", negate: false },
                { token: "lte", op: "lte", negate: false },
            ];
        case "id":
        case "enum":
            return [
                { token: "is", op: "is", negate: false },
                { token: "isNot", op: "is", negate: true },
            ];
        case "tag":
            return [
                { token: "has", op: "has", negate: false },
                { token: "notHas", op: "has", negate: true },
            ];
        case "date":
            return [
                { token: "before", op: "before", negate: false },
                { token: "after", op: "after", negate: false },
                { token: "within", op: "within_days", negate: false },
                { token: "isSet", op: "is_set", negate: false },
                { token: "isEmpty", op: "is_set", negate: true },
            ];
    }
}

function fieldsFor(recordType: string): { field: string; kind: FieldKind }[] {
    return FIELDS[recordType] ?? FIELDS.company;
}

function kindOf(recordType: string, field: string | undefined): FieldKind {
    return fieldsFor(recordType).find((entry) => entry.field === field)?.kind ?? "string";
}

function operatorToken(recordType: string, condition: SegmentCondition): string {
    if (condition.type === "predicate") {
        return condition.negate ? "isNot" : "is";
    }
    const options = operatorsForKind(kindOf(recordType, condition.field));
    const match = options.find((option) => option.op === condition.op && option.negate === !!condition.negate);
    return (match ?? options[0]).token;
}

export const EMPTY_DEFINITION: SegmentDefinition = { match: "all", conditions: [] };

/**
 * Runtime guard for a {@link SegmentDefinition}: a non-array object with a conditions array and a
 * valid match. Guards callers that read {@code .conditions} against legacy or malformed saved-view
 * payloads (e.g. an older array shape) that would otherwise throw.
 */
export function isSegmentDefinition(value: unknown): value is SegmentDefinition {
    return (
        value != null &&
        typeof value === "object" &&
        !Array.isArray(value) &&
        Array.isArray((value as SegmentDefinition).conditions) &&
        ((value as SegmentDefinition).match === "all" || (value as SegmentDefinition).match === "any")
    );
}

function newCondition(recordType: string, subject: string): SegmentCondition {
    if (subject.startsWith("predicate:")) {
        const key = subject.slice("predicate:".length);
        return key === "no_activity"
            ? { type: "predicate", key, days: DEFAULT_DAYS, negate: false }
            : { type: "predicate", key, negate: false };
    }
    const field = subject.slice("field:".length);
    const kind = kindOf(recordType, field);
    const op = defaultOp(field, kind);
    if (kind === "date" && op === "within_days") {
        return { type: "field", field, op, days: DEFAULT_DAYS, negate: false };
    }
    return { type: "field", field, op, value: "", negate: false };
}

function defaultOp(field: string, kind: FieldKind): string {
    switch (kind) {
        case "string":
            return field === "industry" ? "equals" : "contains";
        case "number":
            return "gte";
        case "date":
            return "before";
        case "tag":
            return "has";
        default:
            return "is";
    }
}

function subjectValue(condition: SegmentCondition): string {
    return condition.type === "predicate" ? `predicate:${condition.key}` : `field:${condition.field}`;
}

function defaultSubject(recordType: string): string {
    return recordType === "company" ? "predicate:no_activity" : `field:${fieldsFor(recordType)[0].field}`;
}

function countConditions(group: SegmentDefinition): number {
    const own = group.conditions?.length ?? 0;
    return own + (group.groups ?? []).reduce((sum, nested) => sum + countConditions(nested), 0);
}

/**
 * Builds the chip label for a company condition: e.g. "Industry is Fintech", "Has an open deal",
 * "Tag is not Priority". {@code resolveTagName} maps a tag id to its name. Used by the company
 * Smart-Segments chips; operator-only comparisons (is set / is empty) render without a value.
 */
export function segmentConditionLabel(
    condition: SegmentCondition,
    t: (key: string, values?: Record<string, string | number>) => string,
    resolveTagName: (id: string) => string,
): string {
    if (condition.type === "predicate") {
        const key = condition.key ?? "";
        const label = condition.negate ? t(`${key}.labelNot`) : t(`${key}.label`);
        if (condition.key === "no_activity") {
            return t("chipDays", { label, days: condition.days ?? DEFAULT_DAYS });
        }
        return label;
    }
    const field = t(`field.${condition.field}`);
    const op = t(`op.${operatorToken("company", condition)}`);
    if (condition.op === "is_set") {
        return `${field} ${op}`;
    }
    const value = condition.field === "tag" ? resolveTagName(condition.value ?? "") : (condition.value ?? "");
    return t("chipField", { field, op, value });
}

/**
 * Dialog-based builder for a segment / rule WHEN definition: conditions combined with ALL/ANY, each a
 * graph-aware predicate (company only) or a field comparison, optionally negated, with nested groups
 * for mixing AND and OR. Drives the parent via {@code onChange}; {@code recordType} selects the field
 * catalog and {@code options} supply the id-typed value pickers.
 */
export default function SegmentBuilder({
    definition,
    fields,
    onChange,
    recordType = "company",
    options,
}: {
    definition: SegmentDefinition;
    fields: SegmentFields | null;
    onChange: (definition: SegmentDefinition) => void;
    recordType?: string;
    options?: RuleBuilderOptions | null;
}) {
    const t = useTranslations("SmartSegments");
    const [open, setOpen] = useState(false);
    const total = countConditions(definition);

    return (
        <>
            <Button variant="outline" size="sm" className="gap-1.5" onClick={() => setOpen(true)}>
                <SparklesIcon className="size-4" />
                {t("title")}
                {total > 0 && (
                    <span className="flex size-5 items-center justify-center rounded-full bg-brand text-xs font-semibold text-white">
                        {total}
                    </span>
                )}
            </Button>
            <Dialog open={open} onOpenChange={setOpen}>
                <DialogContent className="sm:max-w-2xl">
                    <DialogHeader>
                        <DialogTitle>{t("builderTitle")}</DialogTitle>
                        <DialogDescription>{t("builderDescription")}</DialogDescription>
                    </DialogHeader>

                    <div className="max-h-[60vh] overflow-y-auto px-1">
                        <GroupEditor
                            group={definition}
                            recordType={recordType}
                            fields={fields}
                            options={options}
                            depth={1}
                            onChange={onChange}
                        />
                    </div>

                    <DialogFooter>
                        {total > 0 && (
                            <Button variant="outline" onClick={() => onChange({ match: definition.match, conditions: [] })}>
                                {t("clearConditions")}
                            </Button>
                        )}
                        <DialogClose asChild>
                            <Button className="bg-brand text-white hover:bg-brand-hover">{t("done")}</Button>
                        </DialogClose>
                    </DialogFooter>
                </DialogContent>
            </Dialog>
        </>
    );
}

function GroupEditor({
    group,
    recordType,
    fields,
    options,
    depth,
    onChange,
    onRemove,
}: {
    group: SegmentDefinition;
    recordType: string;
    fields: SegmentFields | null;
    options?: RuleBuilderOptions | null;
    depth: number;
    onChange: (group: SegmentDefinition) => void;
    onRemove?: () => void;
}) {
    const t = useTranslations("SmartSegments");
    const conditions = group.conditions ?? [];
    const groups = group.groups ?? [];
    const nested = depth > 1;

    const setConditions = (next: SegmentCondition[]) => onChange({ ...group, conditions: next });
    const setGroups = (next: SegmentDefinition[]) => onChange({ ...group, groups: next.length ? next : undefined });
    const canAddCondition = conditions.length < MAX_GROUP_CONDITIONS;
    const canAddGroup = depth < MAX_DEPTH - 1;

    return (
        <div className={cn("flex flex-col gap-2", nested && "rounded-xl bg-muted/40 p-3 ring-1 ring-border")}>
            <div className="flex items-center gap-2 text-sm text-muted-foreground">
                <span>{t("matchPrefix")}</span>
                <Select value={group.match} onValueChange={(value) => onChange({ ...group, match: value as SegmentMatch })}>
                    <SelectTrigger size="sm" aria-label={t("a11yMatch")} className="w-[5.5rem]"><SelectValue /></SelectTrigger>
                    <SelectContent>
                        <SelectItem value="all">{t("matchAll")}</SelectItem>
                        <SelectItem value="any">{t("matchAny")}</SelectItem>
                    </SelectContent>
                </Select>
                <span>{t("matchSuffix")}</span>
                {onRemove && (
                    <Button
                        variant="ghost"
                        size="icon-sm"
                        aria-label={t("removeGroup")}
                        onClick={onRemove}
                        className="ml-auto shrink-0 text-muted-foreground"
                    >
                        <XMarkIcon className="size-4" />
                    </Button>
                )}
            </div>

            {conditions.length === 0 && groups.length === 0 && (
                <p className="py-2 text-sm text-muted-foreground">{t("noConditions")}</p>
            )}

            {conditions.map((condition, index) => (
                <ConditionRow
                    key={index}
                    condition={condition}
                    recordType={recordType}
                    fields={fields}
                    options={options}
                    onChange={(next) => setConditions(conditions.map((existing, i) => (i === index ? next : existing)))}
                    onRemove={() => setConditions(conditions.filter((_, i) => i !== index))}
                />
            ))}

            {groups.map((child, index) => (
                <GroupEditor
                    key={`group-${index}`}
                    group={child}
                    recordType={recordType}
                    fields={fields}
                    options={options}
                    depth={depth + 1}
                    onChange={(next) => setGroups(groups.map((existing, i) => (i === index ? next : existing)))}
                    onRemove={() => setGroups(groups.filter((_, i) => i !== index))}
                />
            ))}

            <div className="flex flex-wrap gap-1">
                {canAddCondition && (
                    <Button
                        variant="ghost"
                        size="sm"
                        className="gap-1 self-start text-brand hover:text-brand-hover"
                        onClick={() => setConditions([...conditions, newCondition(recordType, defaultSubject(recordType))])}
                    >
                        <PlusIcon className="size-4" />
                        {t("addCondition")}
                    </Button>
                )}
                {canAddGroup && (
                    <Button
                        variant="ghost"
                        size="sm"
                        className="gap-1 self-start text-muted-foreground hover:text-foreground"
                        onClick={() => setGroups([...groups, { match: group.match === "all" ? "any" : "all", conditions: [newCondition(recordType, `field:${fieldsFor(recordType)[0].field}`)] }])}
                    >
                        <PlusIcon className="size-4" />
                        {t("addGroup")}
                    </Button>
                )}
            </div>
        </div>
    );
}

function ConditionRow({
    condition,
    recordType,
    fields,
    options,
    onChange,
    onRemove,
}: {
    condition: SegmentCondition;
    recordType: string;
    fields: SegmentFields | null;
    options?: RuleBuilderOptions | null;
    onChange: (condition: SegmentCondition) => void;
    onRemove: () => void;
}) {
    const t = useTranslations("SmartSegments");
    const showPredicates = recordType === "company";
    const kind = condition.type === "field" ? kindOf(recordType, condition.field) : "string";
    const operators = condition.type === "predicate"
        ? [{ token: "is", op: "is", negate: false }, { token: "isNot", op: "is", negate: true }]
        : operatorsForKind(kind);

    const onOperator = (token: string) => {
        const option = operators.find((candidate) => candidate.token === token);
        if (!option) return;
        if (condition.type === "predicate") {
            onChange({ ...condition, negate: option.negate });
        } else {
            onChange({ ...condition, op: option.op, negate: option.negate });
        }
    };

    return (
        <div className="flex items-center gap-2">
            <Select value={subjectValue(condition)} onValueChange={(value) => onChange(newCondition(recordType, value))}>
                <SelectTrigger size="sm" aria-label={t("a11ySubject")} className="w-48 shrink-0"><SelectValue /></SelectTrigger>
                <SelectContent>
                    {showPredicates && (
                        <SelectGroup>
                            <SelectLabel>{t("groupPredicates")}</SelectLabel>
                            {PREDICATE_KEYS.map((key) => (
                                <SelectItem key={key} value={`predicate:${key}`}>{t(`${key}.label`)}</SelectItem>
                            ))}
                        </SelectGroup>
                    )}
                    <SelectGroup>
                        <SelectLabel>{t("groupFields")}</SelectLabel>
                        {fieldsFor(recordType).map((entry) => (
                            <SelectItem key={entry.field} value={`field:${entry.field}`}>{t(`field.${entry.field}`)}</SelectItem>
                        ))}
                    </SelectGroup>
                </SelectContent>
            </Select>

            <Select value={operatorToken(recordType, condition)} onValueChange={onOperator}>
                <SelectTrigger size="sm" aria-label={t("a11yOperator")} className="w-36 shrink-0"><SelectValue /></SelectTrigger>
                <SelectContent>
                    {operators.map((option) => (
                        <SelectItem key={option.token} value={option.token}>{t(`op.${option.token}`)}</SelectItem>
                    ))}
                </SelectContent>
            </Select>

            <ValueInput condition={condition} kind={kind} fields={fields} options={options} onChange={onChange} />

            <Button variant="ghost" size="icon-sm" aria-label={t("removeCondition")} onClick={onRemove} className="shrink-0 text-muted-foreground">
                <XMarkIcon className="size-4" />
            </Button>
        </div>
    );
}

function ValueInput({
    condition,
    kind,
    fields,
    options,
    onChange,
}: {
    condition: SegmentCondition;
    kind: FieldKind;
    fields: SegmentFields | null;
    options?: RuleBuilderOptions | null;
    onChange: (condition: SegmentCondition) => void;
}) {
    const t = useTranslations("SmartSegments");

    if (condition.type === "predicate") {
        if (condition.key !== "no_activity") {
            return <div className="flex-1" />;
        }
        return (
            <div className="flex flex-1 items-center gap-1.5 text-sm text-muted-foreground">
                <span>{t("inLast")}</span>
                <Input
                    type="number"
                    min={1}
                    value={condition.days ?? DEFAULT_DAYS}
                    onChange={(event) => onChange({ ...condition, days: Math.max(1, Number(event.target.value) || DEFAULT_DAYS) })}
                    aria-label={t("days")}
                    className="h-9 w-16"
                />
                <span>{t("days")}</span>
            </div>
        );
    }

    if (condition.op === "is_set") {
        return <div className="flex-1" />;
    }

    if (kind === "string" && condition.field === "industry") {
        return (
            <Select value={condition.value || undefined} onValueChange={(value) => onChange({ ...condition, value })}>
                <SelectTrigger size="sm" aria-label={t("field.industry")} className="min-w-0 flex-1"><SelectValue placeholder={t("pickIndustry")} /></SelectTrigger>
                <SelectContent>
                    {(fields?.industries ?? []).map((industry) => (
                        <SelectItem key={industry} value={industry}>{industry}</SelectItem>
                    ))}
                </SelectContent>
            </Select>
        );
    }

    if (kind === "string") {
        return (
            <Input
                value={condition.value ?? ""}
                onChange={(event) => onChange({ ...condition, value: event.target.value })}
                placeholder={t("valuePlaceholder")}
                aria-label={t("valuePlaceholder")}
                maxLength={255}
                className="h-9 min-w-0 flex-1"
            />
        );
    }

    if (kind === "number") {
        return (
            <Input
                type="number"
                value={condition.value ?? ""}
                onChange={(event) => onChange({ ...condition, value: event.target.value })}
                placeholder={t("numberPlaceholder")}
                aria-label={t("numberPlaceholder")}
                className="h-9 min-w-0 flex-1"
            />
        );
    }

    if (kind === "date") {
        if (condition.op === "within_days") {
            return (
                <div className="flex flex-1 items-center gap-1.5 text-sm text-muted-foreground">
                    <Input
                        type="number"
                        min={1}
                        value={condition.days ?? DEFAULT_DAYS}
                        onChange={(event) => onChange({ ...condition, days: Math.max(1, Number(event.target.value) || DEFAULT_DAYS) })}
                        aria-label={t("days")}
                        className="h-9 w-16"
                    />
                    <span>{t("days")}</span>
                </div>
            );
        }
        return (
            <Input
                type="date"
                value={condition.value ?? ""}
                onChange={(event) => onChange({ ...condition, value: event.target.value })}
                aria-label={t("datePlaceholder")}
                className="h-9 min-w-0 flex-1"
            />
        );
    }

    if (kind === "enum") {
        return (
            <Select value={condition.value || undefined} onValueChange={(value) => onChange({ ...condition, value })}>
                <SelectTrigger size="sm" aria-label={t("pickStatus")} className="min-w-0 flex-1"><SelectValue placeholder={t("pickStatus")} /></SelectTrigger>
                <SelectContent>
                    {STATUS_VALUES.map((status) => (
                        <SelectItem key={status} value={status}>{t(`status.${status}`)}</SelectItem>
                    ))}
                </SelectContent>
            </Select>
        );
    }

    if (kind === "tag") {
        return (
            <Select value={condition.value || undefined} onValueChange={(value) => onChange({ ...condition, value })}>
                <SelectTrigger size="sm" aria-label={t("field.tag")} className="min-w-0 flex-1"><SelectValue placeholder={t("pickTag")} /></SelectTrigger>
                <SelectContent>
                    {(fields?.tags ?? []).map((tag) => (
                        <SelectItem key={tag.id} value={String(tag.id)}>{tag.name}</SelectItem>
                    ))}
                </SelectContent>
            </Select>
        );
    }

    if (condition.field === "owner") {
        return (
            <Select value={condition.value || undefined} onValueChange={(value) => onChange({ ...condition, value })}>
                <SelectTrigger size="sm" aria-label={t("pickOwner")} className="min-w-0 flex-1"><SelectValue placeholder={t("pickOwner")} /></SelectTrigger>
                <SelectContent>
                    {(options?.owners ?? []).map((owner) => (
                        <SelectItem key={owner.id} value={String(owner.id)}>{owner.name}</SelectItem>
                    ))}
                </SelectContent>
            </Select>
        );
    }

    if (condition.field === "company") {
        return (
            <Select value={condition.value || undefined} onValueChange={(value) => onChange({ ...condition, value })}>
                <SelectTrigger size="sm" aria-label={t("pickCompany")} className="min-w-0 flex-1"><SelectValue placeholder={t("pickCompany")} /></SelectTrigger>
                <SelectContent>
                    {(options?.companies ?? []).map((company) => (
                        <SelectItem key={company.id} value={String(company.id)}>{company.name}</SelectItem>
                    ))}
                </SelectContent>
            </Select>
        );
    }

    return (
        <Select value={condition.value || undefined} onValueChange={(value) => onChange({ ...condition, value })}>
            <SelectTrigger size="sm" aria-label={t("pickStage")} className="min-w-0 flex-1"><SelectValue placeholder={t("pickStage")} /></SelectTrigger>
            <SelectContent>
                {groupStages(options?.stages ?? []).map((pipeline) => (
                    <SelectGroup key={pipeline.name}>
                        <SelectLabel>{pipeline.name}</SelectLabel>
                        {pipeline.stages.map((stage) => (
                            <SelectItem key={stage.id} value={String(stage.id)}>{stage.name}</SelectItem>
                        ))}
                    </SelectGroup>
                ))}
            </SelectContent>
        </Select>
    );
}

function groupStages(stages: RuleBuilderOptions["stages"]): { name: string; stages: { id: number; name: string }[] }[] {
    const byPipeline = new Map<string, { id: number; name: string }[]>();
    for (const stage of stages) {
        const list = byPipeline.get(stage.pipeline) ?? [];
        list.push({ id: stage.id, name: stage.name });
        byPipeline.set(stage.pipeline, list);
    }
    return Array.from(byPipeline.entries()).map(([name, list]) => ({ name, stages: list }));
}
