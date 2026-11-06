import type { RuleBuilderOptions, SegmentCondition, SegmentDefinition, SegmentFields } from '@/app/lib/types';

const DEFAULT_DAYS = 30;

const OPERATOR_TOKENS: Record<string, { positive: string; negative?: string }> = {
    equals: { positive: 'is', negative: 'isNot' },
    is: { positive: 'is', negative: 'isNot' },
    contains: { positive: 'contains', negative: 'notContains' },
    starts_with: { positive: 'startsWith' },
    is_set: { positive: 'isSet', negative: 'isEmpty' },
    gt: { positive: 'gt' },
    gte: { positive: 'gte' },
    lt: { positive: 'lt' },
    lte: { positive: 'lte' },
    before: { positive: 'before' },
    after: { positive: 'after' },
    within_days: { positive: 'within' },
    has: { positive: 'has', negative: 'notHas' },
};

export const EMPTY_DEFINITION: SegmentDefinition = { match: "all", conditions: [] };

/** Resolves the record's own name separately from its linked company. */
export function segmentFieldLabel(field: string | undefined, recordType: string, t: (key: string) => string): string {
    return t(field === "name" ? `recordName.${recordType}` : `field.${field}`);
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
    recordType = "company",
): string {
    if (condition.type === "predicate") {
        const key = condition.key ?? "";
        const label = condition.negate ? t(`${key}.labelNot`) : t(`${key}.label`);
        if (condition.days != null) {
            return t("chipDays", { label, days: condition.days });
        }
        return label;
    }
    const field = segmentFieldLabel(condition.field, recordType, t);
    const tokens = OPERATOR_TOKENS[condition.op ?? ''] ?? { positive: 'is' };
    const op = t(`op.${condition.negate && tokens.negative ? tokens.negative : tokens.positive}`);
    const describe = (label: string) => condition.negate && !tokens.negative
        ? t('summaryNot', { conditions: label }) : label;
    if (condition.op === "is_set") {
        return describe(`${field} ${op}`);
    }
    if (condition.op === "within_days") {
        return describe(t("chipField", { field, op, value: t("durationDays", { days: condition.days ?? DEFAULT_DAYS }) }));
    }
    const value = condition.field === "tag"
        ? resolveTagName(condition.value ?? "")
        : condition.field === "owner" && resolveOwnerName
            ? resolveOwnerName(condition.value ?? "")
            : condition.field === "status" && ["open", "won", "lost"].includes(condition.value ?? "")
                ? t(`status.${condition.value}`)
            : (condition.value ?? "");
    return describe(t("chipField", { field, op, value }));
}

/** Describes a saved condition tree without changing its fields, values or grouping. */
export function segmentDefinitionLabel(
    definition: SegmentDefinition,
    recordType: string,
    t: (key: string, values?: Record<string, string | number>) => string,
    fields: SegmentFields | null,
    options?: RuleBuilderOptions | null,
): string {
    const parts = (definition.conditions ?? []).map((condition) => {
        const references = condition.field === "company" ? options?.companies
            : condition.field === "stage" ? options?.stages : undefined;
        const display = ["company", "stage"].includes(condition.field ?? "") && condition.value
            ? { ...condition, value: references?.find((item) => String(item.id) === condition.value)?.name ?? t("unavailableValue") }
            : condition;
        return segmentConditionLabel(display, t,
            (id) => fields?.tags.find((tag) => String(tag.id) === id)?.name ?? t("unavailableValue"),
            (id) => options?.owners.find((owner) => String(owner.id) === id)?.name ?? t("unavailableValue"),
            recordType);
    });
    for (const group of definition.groups ?? []) {
        const label = segmentDefinitionLabel(group, recordType, t, fields, options);
        if (label) parts.push(`(${label})`);
    }
    const label = parts.join(t(definition.match === "any" ? "summaryOr" : "summaryAnd"));
    return definition.negate && label ? t('summaryNot', { conditions: label }) : label;
}

