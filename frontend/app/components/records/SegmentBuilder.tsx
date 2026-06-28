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
import type { SegmentCondition, SegmentDefinition, SegmentFields, SegmentMatch } from "@/app/lib/types";

const PREDICATE_KEYS = ["warm_intro_available", "open_deal", "cooling", "no_activity"];
const FIELD_KEYS = ["industry", "name", "tag"];
const DEFAULT_DAYS = 30;

export const EMPTY_DEFINITION: SegmentDefinition = { match: "all", conditions: [] };

type OperatorOption = { token: string; op?: string; negate: boolean };

function operatorOptions(condition: SegmentCondition): OperatorOption[] {
    if (condition.type === "predicate") {
        return [{ token: "is", negate: false }, { token: "isNot", negate: true }];
    }
    switch (condition.field) {
        case "industry":
            return [{ token: "is", op: "equals", negate: false }, { token: "isNot", op: "equals", negate: true }];
        case "name":
            return [{ token: "contains", op: "contains", negate: false }, { token: "notContains", op: "contains", negate: true }];
        case "tag":
            return [{ token: "has", op: "has", negate: false }, { token: "notHas", op: "has", negate: true }];
        default:
            return [];
    }
}

function operatorToken(condition: SegmentCondition): string {
    if (condition.type === "predicate") {
        return condition.negate ? "isNot" : "is";
    }
    if (condition.op === "contains") {
        return condition.negate ? "notContains" : "contains";
    }
    if (condition.op === "has") {
        return condition.negate ? "notHas" : "has";
    }
    return condition.negate ? "isNot" : "is";
}

function newCondition(subject: string): SegmentCondition {
    if (subject.startsWith("predicate:")) {
        const key = subject.slice("predicate:".length);
        return key === "no_activity"
            ? { type: "predicate", key, days: DEFAULT_DAYS, negate: false }
            : { type: "predicate", key, negate: false };
    }
    const field = subject.slice("field:".length);
    if (field === "name") {
        return { type: "field", field, op: "contains", value: "", negate: false };
    }
    if (field === "tag") {
        return { type: "field", field, op: "has", value: "", negate: false };
    }
    return { type: "field", field: "industry", op: "equals", value: "", negate: false };
}

function subjectValue(condition: SegmentCondition): string {
    return condition.type === "predicate" ? `predicate:${condition.key}` : `field:${condition.field}`;
}

/**
 * Builds the chip label for a condition: e.g. "Industry is Fintech", "Has an open deal",
 * "Tag is not Priority". {@code resolveTagName} maps a tag id to its name.
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
    const value = condition.field === "tag" ? resolveTagName(condition.value ?? "") : (condition.value ?? "");
    return t("chipField", { field, op: t(`op.${operatorToken(condition)}`), value });
}

/**
 * Dialog-based builder for a smart-segment definition: conditions combined with ALL/ANY, each a
 * graph-aware predicate or a field comparison, optionally negated. Drives the parent via {@code onChange};
 * the parent evaluates the definition server-side and intersects the matching ids with its list.
 */
export default function SegmentBuilder({
    definition,
    fields,
    onChange,
}: {
    definition: SegmentDefinition;
    fields: SegmentFields | null;
    onChange: (definition: SegmentDefinition) => void;
}) {
    const t = useTranslations("SmartSegments");
    const [open, setOpen] = useState(false);

    const conditions = definition.conditions;
    const setConditions = (next: SegmentCondition[]) => onChange({ ...definition, conditions: next });
    const setCondition = (index: number, next: SegmentCondition) =>
        setConditions(conditions.map((condition, i) => (i === index ? next : condition)));

    return (
        <>
            <Button variant="outline" size="sm" className="gap-1.5" onClick={() => setOpen(true)}>
                <SparklesIcon className="size-4" />
                {t("title")}
                {conditions.length > 0 && (
                    <span className="flex size-5 items-center justify-center rounded-full bg-brand text-xs font-semibold text-white">
                        {conditions.length}
                    </span>
                )}
            </Button>
            <Dialog open={open} onOpenChange={setOpen}>
                <DialogContent className="sm:max-w-2xl">
                    <DialogHeader>
                        <DialogTitle>{t("builderTitle")}</DialogTitle>
                        <DialogDescription>{t("builderDescription")}</DialogDescription>
                    </DialogHeader>

                    <div className="flex items-center gap-2 text-sm text-muted-foreground">
                        <span>{t("matchPrefix")}</span>
                        <Select value={definition.match} onValueChange={(value) => onChange({ ...definition, match: value as SegmentMatch })}>
                            <SelectTrigger size="sm" aria-label={t("a11yMatch")} className="w-[5.5rem]"><SelectValue /></SelectTrigger>
                            <SelectContent>
                                <SelectItem value="all">{t("matchAll")}</SelectItem>
                                <SelectItem value="any">{t("matchAny")}</SelectItem>
                            </SelectContent>
                        </Select>
                        <span>{t("matchSuffix")}</span>
                    </div>

                    <div className="flex flex-col gap-2">
                        {conditions.length === 0 && (
                            <p className="py-3 text-sm text-muted-foreground">{t("noConditions")}</p>
                        )}
                        {conditions.map((condition, index) => (
                            <ConditionRow
                                key={index}
                                condition={condition}
                                fields={fields}
                                onChange={(next) => setCondition(index, next)}
                                onRemove={() => setConditions(conditions.filter((_, i) => i !== index))}
                            />
                        ))}
                        <Button
                            variant="ghost"
                            size="sm"
                            className="mt-1 gap-1 self-start text-brand hover:text-brand-hover"
                            onClick={() => setConditions([...conditions, newCondition("predicate:warm_intro_available")])}
                        >
                            <PlusIcon className="size-4" />
                            {t("addCondition")}
                        </Button>
                    </div>

                    <DialogFooter>
                        {conditions.length > 0 && (
                            <Button variant="outline" onClick={() => setConditions([])}>{t("clearConditions")}</Button>
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

function ConditionRow({
    condition,
    fields,
    onChange,
    onRemove,
}: {
    condition: SegmentCondition;
    fields: SegmentFields | null;
    onChange: (condition: SegmentCondition) => void;
    onRemove: () => void;
}) {
    const t = useTranslations("SmartSegments");
    const operators = operatorOptions(condition);
    const isField = condition.type === "field";

    return (
        <div className="flex items-center gap-2">
            <Select value={subjectValue(condition)} onValueChange={(value) => onChange(newCondition(value))}>
                <SelectTrigger size="sm" aria-label={t("a11ySubject")} className="w-48 shrink-0"><SelectValue /></SelectTrigger>
                <SelectContent>
                    <SelectGroup>
                        <SelectLabel>{t("groupPredicates")}</SelectLabel>
                        {PREDICATE_KEYS.map((key) => (
                            <SelectItem key={key} value={`predicate:${key}`}>{t(`${key}.label`)}</SelectItem>
                        ))}
                    </SelectGroup>
                    <SelectGroup>
                        <SelectLabel>{t("groupFields")}</SelectLabel>
                        {FIELD_KEYS.map((field) => (
                            <SelectItem key={field} value={`field:${field}`}>{t(`field.${field}`)}</SelectItem>
                        ))}
                    </SelectGroup>
                </SelectContent>
            </Select>

            <Select
                value={operatorToken(condition)}
                onValueChange={(token) => {
                    const option = operators.find((candidate) => candidate.token === token);
                    if (option) onChange({ ...condition, op: option.op, negate: option.negate });
                }}
            >
                <SelectTrigger size="sm" aria-label={t("a11yOperator")} className="w-36 shrink-0"><SelectValue /></SelectTrigger>
                <SelectContent>
                    {operators.map((option) => (
                        <SelectItem key={option.token} value={option.token}>{t(`op.${option.token}`)}</SelectItem>
                    ))}
                </SelectContent>
            </Select>

            {isField && condition.field === "industry" && (
                <Select value={condition.value || undefined} onValueChange={(value) => onChange({ ...condition, value })}>
                    <SelectTrigger size="sm" aria-label={t("field.industry")} className="flex-1 min-w-0"><SelectValue placeholder={t("pickIndustry")} /></SelectTrigger>
                    <SelectContent>
                        {(fields?.industries ?? []).map((industry) => (
                            <SelectItem key={industry} value={industry}>{industry}</SelectItem>
                        ))}
                    </SelectContent>
                </Select>
            )}
            {isField && condition.field === "name" && (
                <Input
                    value={condition.value ?? ""}
                    onChange={(event) => onChange({ ...condition, value: event.target.value })}
                    placeholder={t("nameValue")}
                    aria-label={t("field.name")}
                    className="h-9 flex-1 min-w-0"
                />
            )}
            {isField && condition.field === "tag" && (
                <Select value={condition.value || undefined} onValueChange={(value) => onChange({ ...condition, value })}>
                    <SelectTrigger size="sm" aria-label={t("field.tag")} className="flex-1 min-w-0"><SelectValue placeholder={t("pickTag")} /></SelectTrigger>
                    <SelectContent>
                        {(fields?.tags ?? []).map((tag) => (
                            <SelectItem key={tag.id} value={String(tag.id)}>{tag.name}</SelectItem>
                        ))}
                    </SelectContent>
                </Select>
            )}
            {condition.type === "predicate" && condition.key === "no_activity" && (
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
            )}
            {condition.type === "predicate" && condition.key !== "no_activity" && <div className="flex-1" />}

            <Button variant="ghost" size="icon-sm" aria-label={t("removeCondition")} onClick={onRemove} className="shrink-0 text-muted-foreground">
                <XMarkIcon className="size-4" />
            </Button>
        </div>
    );
}
