"use client";

import { useEffect, useState } from "react";
import { useTranslations } from "next-intl";
import { AnimatePresence, motion, useReducedMotion } from "motion/react";
import {
    AdjustmentsHorizontalIcon,
    PlusIcon,
    SparklesIcon,
    TagIcon,
    XMarkIcon,
} from "@heroicons/react/24/outline";

import {
    ResponsiveDialog,
    ResponsiveDialogClose,
    ResponsiveDialogContent,
    ResponsiveDialogDescription,
    ResponsiveDialogFooter,
    ResponsiveDialogHeader,
    ResponsiveDialogTitle,
    ResponsiveDialogTrigger,
} from "@/components/ui/responsive-dialog";
import {
    Select,
    SelectContent,
    SelectItem,
    SelectTrigger,
    SelectValue,
} from "@/components/ui/select";
import {
    DropdownMenu,
    DropdownMenuContent,
    DropdownMenuGroup,
    DropdownMenuItem,
    DropdownMenuLabel,
    DropdownMenuSeparator,
    DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { cn } from "@/lib/utils";
import { getSegmentCatalog } from "@/app/lib/api";
import { toastError } from "@/app/lib/toast";
import { easeOut } from "@/app/lib/motion";
import type {
    RuleBuilderOptions,
    SavedViewRecordType,
    SegmentCatalog,
    SegmentCatalogField,
    SegmentCatalogPredicate,
    SegmentCondition,
    SegmentDefinition,
    SegmentFields,
    SegmentMatch,
} from "@/app/lib/types";

const DEFAULT_DAYS = 30;

type OperatorVariant = { token: string; negate: boolean };

/**
 * The presentation mapping from a backend operator to the UI operator token(s) it renders as. Negated
 * variants (is/is-not, contains/doesn't-contain, is-set/is-empty) are a client-side concern layered on
 * the server's operator allow-list, keyed by stable tokens the {@code SmartSegments} messages label.
 * Operators without an entry here (e.g. {@code in}, which needs a multi-value control not yet built)
 * are skipped, so the catalog can advertise them before the builder can render them.
 */
const OPERATOR_VARIANTS: Record<string, OperatorVariant[]> = {
    contains: [{ token: "contains", negate: false }, { token: "notContains", negate: true }],
    starts_with: [{ token: "startsWith", negate: false }],
    is_set: [{ token: "isSet", negate: false }, { token: "isEmpty", negate: true }],
    gt: [{ token: "gt", negate: false }],
    gte: [{ token: "gte", negate: false }],
    lt: [{ token: "lt", negate: false }],
    lte: [{ token: "lte", negate: false }],
    is: [{ token: "is", negate: false }, { token: "isNot", negate: true }],
    before: [{ token: "before", negate: false }],
    after: [{ token: "after", negate: false }],
    within_days: [{ token: "within", negate: false }],
    has: [{ token: "has", negate: false }, { token: "notHas", negate: true }],
};

/**
 * The operator token variants for a backend operator, keyed by field kind because {@code equals}
 * reads as "is / is not" for text but "equals" for numbers.
 */
function variantsFor(kind: string, op: string): OperatorVariant[] {
    if (op === "equals") {
        return kind === "number"
            ? [{ token: "eq", negate: false }]
            : [{ token: "is", negate: false }, { token: "isNot", negate: true }];
    }
    return OPERATOR_VARIANTS[op] ?? [];
}

type OperatorOption = { token: string; op: string; negate: boolean };

const PREDICATE_OPERATORS: OperatorOption[] = [
    { token: "is", op: "is", negate: false },
    { token: "isNot", op: "is", negate: true },
];

const catalogCache = new Map<string, SegmentCatalog>();

/**
 * Loads and caches the builder catalog (fields, predicates, enum options, limits) for a record type.
 * The catalog is workspace-independent and static, so it is memoized across mounts; a load failure
 * surfaces a toast and leaves the builder in a disabled empty state rather than throwing.
 */
function useSegmentCatalog(recordType: string): SegmentCatalog | null {
    const t = useTranslations("SmartSegments");
    const [, bumpVersion] = useState(0);

    useEffect(() => {
        if (catalogCache.has(recordType)) {
            return;
        }
        let active = true;
        getSegmentCatalog(recordType as SavedViewRecordType)
            .then((loaded) => {
                catalogCache.set(recordType, loaded);
                if (active) bumpVersion((version) => version + 1);
            })
            .catch(() => {
                if (active) {
                    toastError(t("catalogFailed"));
                    bumpVersion((version) => version + 1);
                }
            });
        return () => {
            active = false;
        };
    }, [recordType, t]);

    return catalogCache.get(recordType) ?? null;
}

function fieldSpec(catalog: SegmentCatalog | null, field: string | undefined): SegmentCatalogField | undefined {
    return catalog?.fields.find((entry) => entry.field === field);
}

function predicateSpec(catalog: SegmentCatalog | null, key: string | undefined): SegmentCatalogPredicate | undefined {
    return catalog?.predicates.find((entry) => entry.key === key);
}

function operatorOptions(field: SegmentCatalogField, advanced: boolean): OperatorOption[] {
    const options: OperatorOption[] = [];
    for (const op of field.operators) {
        const variants = variantsFor(field.kind, op);
        if (variants.length === 0) continue;
        if (!advanced && op === "is_set") continue;
        for (const variant of variants) {
            options.push({ token: variant.token, op, negate: variant.negate });
        }
    }
    return options;
}

function defaultOp(field: SegmentCatalogField): string {
    switch (field.kind) {
        case "string":
            return field.valueSource === "none" ? "contains" : "equals";
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

function operatorTokenFor(op: string | undefined, negate: boolean | undefined): string {
    const variants = OPERATOR_VARIANTS[op ?? ""];
    const match = variants?.find((variant) => variant.negate === !!negate);
    return match?.token ?? "is";
}

function currentOperatorToken(condition: SegmentCondition, field: SegmentCatalogField | undefined): string {
    if (condition.type === "predicate") {
        return condition.negate ? "isNot" : "is";
    }
    if (!field) return "is";
    const options = operatorOptions(field, true);
    const match = options.find((option) => option.op === condition.op && option.negate === !!condition.negate);
    return (match ?? options[0])?.token ?? "is";
}

function newCondition(catalog: SegmentCatalog, subject: string): SegmentCondition {
    if (subject.startsWith("predicate:")) {
        const key = subject.slice("predicate:".length);
        const spec = predicateSpec(catalog, key);
        return spec?.acceptsDays
            ? { type: "predicate", key, days: spec.defaultDays ?? DEFAULT_DAYS, negate: false }
            : { type: "predicate", key, negate: false };
    }
    const field = subject.slice("field:".length);
    const spec = fieldSpec(catalog, field);
    if (!spec) {
        return { type: "field", field, op: "contains", value: "", negate: false };
    }
    const op = defaultOp(spec);
    if (spec.kind === "date" && op === "within_days") {
        return { type: "field", field, op, days: DEFAULT_DAYS, negate: false };
    }
    return { type: "field", field, op, value: "", negate: false };
}

function subjectValue(condition: SegmentCondition): string {
    return condition.type === "predicate" ? `predicate:${condition.key}` : `field:${condition.field}`;
}

function firstSubject(catalog: SegmentCatalog): string {
    if (catalog.predicates.length > 0) return `predicate:${catalog.predicates[0].key}`;
    return `field:${catalog.fields[0]?.field ?? "name"}`;
}

function countConditions(group: SegmentDefinition): number {
    const own = group.conditions?.length ?? 0;
    return own + (group.groups ?? []).reduce((sum, nested) => sum + countConditions(nested), 0);
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

/**
 * Builds the natural-language label for a condition: e.g. "Industry is Fintech", "Has an open deal",
 * "Tag is not Priority". {@code resolveTagName} maps a tag id to its name. Used by the removable
 * condition chips outside the builder; operator-only comparisons (is set / is empty) render without a
 * value. Derives the operator token from the condition's op + negate, independent of the catalog.
 */
export function segmentConditionLabel(
    condition: SegmentCondition,
    t: (key: string, values?: Record<string, string | number>) => string,
    resolveTagName: (id: string) => string,
    resolveOwnerName?: (id: string) => string,
): string {
    if (condition.type === "predicate") {
        const key = condition.key ?? "";
        const label = condition.negate ? t(`${key}.labelNot`) : t(`${key}.label`);
        if (condition.days != null) {
            return t("chipDays", { label, days: condition.days });
        }
        return label;
    }
    const field = t(`field.${condition.field}`);
    const op = t(`op.${operatorTokenFor(condition.op, condition.negate)}`);
    if (condition.op === "is_set") {
        return `${field} ${op}`;
    }
    const rawValue = condition.value ?? "";
    let value = rawValue;
    if (condition.field === "tag") {
        value = resolveTagName(rawValue);
    } else if (condition.field === "owner" && resolveOwnerName) {
        value = resolveOwnerName(rawValue);
    }
    return t("chipField", { field, op, value });
}

/**
 * Guided, responsive builder for a segment / rule WHEN definition. Conditions are combined with
 * ALL/ANY, each a graph-aware signal (when the record type has predicates) or a field comparison,
 * optionally negated, with nested groups for mixing AND and OR. The vocabulary — fields, operators,
 * predicates, enum options, and limits — is rendered from the server catalog for {@code recordType};
 * value pickers draw on {@code fields} (tags / industries) and {@code options} (owners / stages /
 * companies). Renders as a centered dialog on desktop and a bottom drawer on mobile.
 */
export default function SegmentBuilder({
    definition,
    fields,
    onChange,
    recordType = "company",
    options,
    advanced = false,
}: {
    definition: SegmentDefinition;
    fields: SegmentFields | null;
    onChange: (definition: SegmentDefinition) => void;
    recordType?: string;
    options?: RuleBuilderOptions | null;
    advanced?: boolean;
}) {
    const t = useTranslations("SmartSegments");
    const [open, setOpen] = useState(false);
    const catalog = useSegmentCatalog(recordType);
    const total = countConditions(definition);

    return (
        <ResponsiveDialog open={open} onOpenChange={setOpen}>
            <ResponsiveDialogTrigger asChild>
                <Button variant="outline" size="sm" className="gap-1.5">
                    <SparklesIcon className="size-4" />
                    {t("title")}
                    {total > 0 && (
                        <span className="flex size-5 items-center justify-center rounded-full bg-brand text-xs font-semibold text-brand-foreground">
                            {total}
                        </span>
                    )}
                </Button>
            </ResponsiveDialogTrigger>
            <ResponsiveDialogContent className="gap-0 overflow-hidden p-0 sm:max-w-2xl">
                <div className="px-6 pt-6">
                    <ResponsiveDialogHeader className="mb-4">
                        <ResponsiveDialogTitle>{t("builderTitle")}</ResponsiveDialogTitle>
                        <ResponsiveDialogDescription>{t("builderDescription")}</ResponsiveDialogDescription>
                    </ResponsiveDialogHeader>
                </div>

                <div className="max-h-[60dvh] overflow-y-auto px-6 pb-4">
                    {catalog ? (
                        <GroupEditor
                            group={definition}
                            catalog={catalog}
                            fields={fields}
                            options={options}
                            advanced={advanced}
                            depth={1}
                            onChange={onChange}
                        />
                    ) : (
                        <p className="py-8 text-center text-sm text-muted-foreground">{t("loading")}</p>
                    )}
                </div>

                <ResponsiveDialogFooter className="border-t border-border/60 bg-popover px-6 py-4">
                    {total > 0 && (
                        <Button
                            variant="ghost"
                            onClick={() => onChange({ match: definition.match, conditions: [] })}
                            className="text-muted-foreground"
                        >
                            {t("clearConditions")}
                        </Button>
                    )}
                    <ResponsiveDialogClose asChild>
                        <Button variant="brand">{t("done")}</Button>
                    </ResponsiveDialogClose>
                </ResponsiveDialogFooter>
            </ResponsiveDialogContent>
        </ResponsiveDialog>
    );
}

function GroupEditor({
    group,
    catalog,
    fields,
    options,
    advanced,
    depth,
    onChange,
    onRemove,
}: {
    group: SegmentDefinition;
    catalog: SegmentCatalog;
    fields: SegmentFields | null;
    options?: RuleBuilderOptions | null;
    advanced: boolean;
    depth: number;
    onChange: (group: SegmentDefinition) => void;
    onRemove?: () => void;
}) {
    const t = useTranslations("SmartSegments");
    const reduce = useReducedMotion();
    const conditions = group.conditions ?? [];
    const groups = group.groups ?? [];
    const nested = depth > 1;

    const setConditions = (next: SegmentCondition[]) => onChange({ ...group, conditions: next });
    const setGroups = (next: SegmentDefinition[]) => onChange({ ...group, groups: next.length ? next : undefined });
    const canAddCondition = conditions.length < catalog.limits.maxGroupConditions;
    const canAddGroup = advanced && groups.length < catalog.limits.maxGroups && depth < catalog.limits.maxDepth - 1;
    const empty = conditions.length === 0 && groups.length === 0;

    const addCondition = (subject: string) => setConditions([...conditions, newCondition(catalog, subject)]);

    return (
        <div className={cn("flex flex-col gap-3", nested && "rounded-2xl bg-muted/40 p-3 ring-1 ring-border")}>
            <div className="flex items-center gap-2">
                <span className="text-sm text-muted-foreground">{t("matchPrefix")}</span>
                <Select value={group.match} onValueChange={(value) => onChange({ ...group, match: value as SegmentMatch })}>
                    <SelectTrigger size="sm" aria-label={t("a11yMatch")} className="w-[5.5rem]">
                        <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                        <SelectItem value="all">{t("matchAll")}</SelectItem>
                        <SelectItem value="any">{t("matchAny")}</SelectItem>
                    </SelectContent>
                </Select>
                <span className="text-sm text-muted-foreground">{t("matchSuffix")}</span>
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

            {empty && (
                <div className="rounded-2xl border border-dashed border-border px-4 py-8 text-center">
                    <p className="text-sm text-muted-foreground">{t("noConditions")}</p>
                </div>
            )}

            <AnimatePresence initial={false}>
                {conditions.map((condition, index) => (
                    <motion.div
                        key={index}
                        initial={reduce ? false : { opacity: 0, y: -4 }}
                        animate={{ opacity: 1, y: 0 }}
                        transition={{ duration: 0.18, ease: easeOut }}
                    >
                        <ConditionCard
                            condition={condition}
                            catalog={catalog}
                            fields={fields}
                            options={options}
                            advanced={advanced}
                            onChange={(next) => setConditions(conditions.map((existing, i) => (i === index ? next : existing)))}
                            onRemove={() => setConditions(conditions.filter((_, i) => i !== index))}
                        />
                    </motion.div>
                ))}
            </AnimatePresence>

            {groups.map((child, index) => (
                <GroupEditor
                    key={`group-${index}`}
                    group={child}
                    catalog={catalog}
                    fields={fields}
                    options={options}
                    advanced={advanced}
                    depth={depth + 1}
                    onChange={(next) => setGroups(groups.map((existing, i) => (i === index ? next : existing)))}
                    onRemove={() => setGroups(groups.filter((_, i) => i !== index))}
                />
            ))}

            <div className="flex flex-wrap items-center gap-2">
                {canAddCondition && <AddConditionMenu catalog={catalog} onAdd={addCondition} />}
                {canAddGroup && (
                    <Button
                        variant="ghost"
                        size="sm"
                        className="gap-1.5 text-muted-foreground hover:text-foreground"
                        onClick={() =>
                            setGroups([
                                ...groups,
                                {
                                    match: group.match === "all" ? "any" : "all",
                                    conditions: [newCondition(catalog, firstSubject(catalog))],
                                },
                            ])
                        }
                    >
                        <AdjustmentsHorizontalIcon className="size-4" />
                        {t("addGroup")}
                    </Button>
                )}
            </div>
        </div>
    );
}

function AddConditionMenu({
    catalog,
    onAdd,
}: {
    catalog: SegmentCatalog;
    onAdd: (subject: string) => void;
}) {
    const t = useTranslations("SmartSegments");
    const hasPredicates = catalog.predicates.length > 0;

    return (
        <DropdownMenu>
            <DropdownMenuTrigger asChild>
                <Button variant="outline" size="sm" className="gap-1.5">
                    <PlusIcon className="size-4" />
                    {t("addCondition")}
                </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="start" className="w-60">
                {hasPredicates && (
                    <>
                        <DropdownMenuGroup>
                            <DropdownMenuLabel className="flex items-center gap-1.5 text-muted-foreground">
                                <SparklesIcon className="size-3.5" />
                                {t("groupPredicates")}
                            </DropdownMenuLabel>
                            {catalog.predicates.map((predicate) => (
                                <DropdownMenuItem key={predicate.key} onClick={() => onAdd(`predicate:${predicate.key}`)}>
                                    {t(`${predicate.key}.label`)}
                                </DropdownMenuItem>
                            ))}
                        </DropdownMenuGroup>
                        <DropdownMenuSeparator />
                    </>
                )}
                <DropdownMenuGroup>
                    <DropdownMenuLabel className="flex items-center gap-1.5 text-muted-foreground">
                        <TagIcon className="size-3.5" />
                        {t("groupFields")}
                    </DropdownMenuLabel>
                    {catalog.fields.map((field) => (
                        <DropdownMenuItem key={field.field} onClick={() => onAdd(`field:${field.field}`)}>
                            {t(`field.${field.field}`)}
                        </DropdownMenuItem>
                    ))}
                </DropdownMenuGroup>
            </DropdownMenuContent>
        </DropdownMenu>
    );
}

function ConditionCard({
    condition,
    catalog,
    fields,
    options,
    advanced,
    onChange,
    onRemove,
}: {
    condition: SegmentCondition;
    catalog: SegmentCatalog;
    fields: SegmentFields | null;
    options?: RuleBuilderOptions | null;
    advanced: boolean;
    onChange: (condition: SegmentCondition) => void;
    onRemove: () => void;
}) {
    const t = useTranslations("SmartSegments");
    const hasPredicates = catalog.predicates.length > 0;
    const spec = condition.type === "field" ? fieldSpec(catalog, condition.field) : undefined;
    const operators = condition.type === "predicate"
        ? PREDICATE_OPERATORS
        : spec ? operatorOptions(spec, advanced) : [];

    const onOperator = (token: string) => {
        const option = operators.find((candidate) => candidate.token === token);
        if (!option) return;
        if (condition.type === "predicate") {
            onChange({ ...condition, negate: option.negate });
        } else {
            onChange({
                ...condition,
                op: option.op,
                negate: option.negate,
                ...(option.op === "within_days" && condition.days == null ? { days: DEFAULT_DAYS } : {}),
            });
        }
    };

    return (
        <div className="rounded-2xl border border-border bg-card p-3 shadow-xs">
            <div className="flex items-start gap-2">
                <div className="flex min-w-0 flex-1 flex-col gap-2 sm:flex-row sm:items-center">
                    <Select value={subjectValue(condition)} onValueChange={(value) => onChange(newCondition(catalog, value))}>
                        <SelectTrigger size="sm" aria-label={t("a11ySubject")} className="w-full sm:w-52">
                            <SelectValue />
                        </SelectTrigger>
                        <SelectContent>
                            {hasPredicates && catalog.predicates.map((predicate) => (
                                <SelectItem key={predicate.key} value={`predicate:${predicate.key}`}>
                                    {t(`${predicate.key}.label`)}
                                </SelectItem>
                            ))}
                            {catalog.fields.map((field) => (
                                <SelectItem key={field.field} value={`field:${field.field}`}>
                                    {t(`field.${field.field}`)}
                                </SelectItem>
                            ))}
                        </SelectContent>
                    </Select>

                    {operators.length > 0 && (
                        <Select value={currentOperatorToken(condition, spec)} onValueChange={onOperator}>
                            <SelectTrigger size="sm" aria-label={t("a11yOperator")} className="w-full sm:w-40">
                                <SelectValue />
                            </SelectTrigger>
                            <SelectContent>
                                {operators.map((option) => (
                                    <SelectItem key={option.token} value={option.token}>{t(`op.${option.token}`)}</SelectItem>
                                ))}
                            </SelectContent>
                        </Select>
                    )}

                    <ValueInput
                        condition={condition}
                        spec={spec}
                        predicate={condition.type === "predicate" ? predicateSpec(catalog, condition.key) : undefined}
                        catalog={catalog}
                        fields={fields}
                        options={options}
                        onChange={onChange}
                    />
                </div>

                <Button
                    variant="ghost"
                    size="icon-sm"
                    aria-label={t("removeCondition")}
                    onClick={onRemove}
                    className="shrink-0 text-muted-foreground"
                >
                    <XMarkIcon className="size-4" />
                </Button>
            </div>
        </div>
    );
}

function ValueInput({
    condition,
    spec,
    predicate,
    catalog,
    fields,
    options,
    onChange,
}: {
    condition: SegmentCondition;
    spec: SegmentCatalogField | undefined;
    predicate: SegmentCatalogPredicate | undefined;
    catalog: SegmentCatalog;
    fields: SegmentFields | null;
    options?: RuleBuilderOptions | null;
    onChange: (condition: SegmentCondition) => void;
}) {
    const t = useTranslations("SmartSegments");

    if (condition.type === "predicate") {
        if (!predicate?.acceptsDays) {
            return <div className="hidden flex-1 sm:block" />;
        }
        return <DaysInput condition={condition} onChange={onChange} />;
    }

    if (condition.op === "is_set" || !spec) {
        return <div className="hidden flex-1 sm:block" />;
    }

    if (spec.kind === "date") {
        if (condition.op === "within_days") {
            return <DaysInput condition={condition} onChange={onChange} />;
        }
        return (
            <Input
                type="date"
                value={condition.value ?? ""}
                onChange={(event) => onChange({ ...condition, value: event.target.value })}
                aria-label={t("datePlaceholder")}
                className="h-9 w-full min-w-0 flex-1"
            />
        );
    }

    if (spec.kind === "number") {
        return (
            <Input
                type="number"
                value={condition.value ?? ""}
                onChange={(event) => onChange({ ...condition, value: event.target.value })}
                placeholder={t("numberPlaceholder")}
                aria-label={t("numberPlaceholder")}
                className="h-9 w-full min-w-0 flex-1"
            />
        );
    }

    if (spec.kind === "enum") {
        const values = catalog.enumOptions[spec.field] ?? [];
        return (
            <ValueSelect value={condition.value} placeholder={t("pickStatus")} onChange={(value) => onChange({ ...condition, value })}>
                {values.map((status) => (
                    <SelectItem key={status} value={status}>{t(`status.${status}`)}</SelectItem>
                ))}
            </ValueSelect>
        );
    }

    if (spec.kind === "tag") {
        return (
            <ValueSelect value={condition.value} placeholder={t("pickTag")} onChange={(value) => onChange({ ...condition, value })}>
                {(fields?.tags ?? []).map((tag) => (
                    <SelectItem key={tag.id} value={String(tag.id)}>{tag.name}</SelectItem>
                ))}
            </ValueSelect>
        );
    }

    if (spec.kind === "id") {
        if (spec.valueSource === "owners") {
            return (
                <ValueSelect value={condition.value} placeholder={t("pickOwner")} onChange={(value) => onChange({ ...condition, value })}>
                    {(options?.owners ?? []).map((owner) => (
                        <SelectItem key={owner.id} value={String(owner.id)}>{owner.name}</SelectItem>
                    ))}
                </ValueSelect>
            );
        }
        if (spec.valueSource === "companies") {
            return (
                <ValueSelect value={condition.value} placeholder={t("pickCompany")} onChange={(value) => onChange({ ...condition, value })}>
                    {(options?.companies ?? []).map((company) => (
                        <SelectItem key={company.id} value={String(company.id)}>{company.name}</SelectItem>
                    ))}
                </ValueSelect>
            );
        }
        return (
            <ValueSelect value={condition.value} placeholder={t("pickStage")} onChange={(value) => onChange({ ...condition, value })}>
                {(options?.stages ?? []).map((stage) => (
                    <SelectItem key={stage.id} value={String(stage.id)}>{stage.pipeline} · {stage.name}</SelectItem>
                ))}
            </ValueSelect>
        );
    }

    if (spec.valueSource === "industries") {
        return (
            <ValueSelect value={condition.value} placeholder={t("pickIndustry")} onChange={(value) => onChange({ ...condition, value })}>
                {(fields?.industries ?? []).map((industry) => (
                    <SelectItem key={industry} value={industry}>{industry}</SelectItem>
                ))}
            </ValueSelect>
        );
    }

    return (
        <Input
            value={condition.value ?? ""}
            onChange={(event) => onChange({ ...condition, value: event.target.value })}
            placeholder={t("valuePlaceholder")}
            aria-label={t("valuePlaceholder")}
            maxLength={255}
            className="h-9 w-full min-w-0 flex-1"
        />
    );
}

function ValueSelect({
    value,
    placeholder,
    onChange,
    children,
}: {
    value: string | undefined;
    placeholder: string;
    onChange: (value: string) => void;
    children: React.ReactNode;
}) {
    return (
        <Select value={value || undefined} onValueChange={onChange}>
            <SelectTrigger size="sm" aria-label={placeholder} className="w-full min-w-0 flex-1">
                <SelectValue placeholder={placeholder} />
            </SelectTrigger>
            <SelectContent>{children}</SelectContent>
        </Select>
    );
}

function DaysInput({
    condition,
    onChange,
}: {
    condition: SegmentCondition;
    onChange: (condition: SegmentCondition) => void;
}) {
    const t = useTranslations("SmartSegments");
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
