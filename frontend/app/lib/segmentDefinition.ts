import type { SegmentCondition, SegmentDefinition } from '@/app/lib/types';

function isEvaluableCondition(condition: SegmentCondition): boolean {
    if (condition.type === 'predicate') return true;
    if (condition.op === 'is_set' || condition.op === 'within_days') return true;
    if (condition.op === 'in') return condition.values?.some((value) => value.trim() !== '') ?? false;
    return (condition.value ?? '').trim() !== '';
}

function evaluableCondition(condition: SegmentCondition): SegmentCondition | null {
    if (!isEvaluableCondition(condition)) return null;
    if (condition.op !== 'in') return condition;
    return {
        ...condition,
        values: condition.values?.flatMap((value) => {
            const trimmedValue = value.trim();
            return trimmedValue ? [trimmedValue] : [];
        }),
    };
}

/** Removes incomplete field conditions while preserving nested-group and negation semantics. */
export function evaluableSegmentDefinition(definition: SegmentDefinition): SegmentDefinition {
    const groups = (definition.groups ?? []).flatMap((group) => {
        const evaluableGroup = evaluableSegmentDefinition(group);
        return hasSegmentConditions(evaluableGroup) ? [evaluableGroup] : [];
    });
    return {
        match: definition.match,
        conditions: definition.conditions.flatMap((condition) => {
            const evaluable = evaluableCondition(condition);
            return evaluable ? [evaluable] : [];
        }),
        groups: groups.length > 0 ? groups : undefined,
        negate: definition.negate,
    };
}

/** Whether a segment definition contains any evaluable condition at any nesting level. */
export function hasSegmentConditions(definition: SegmentDefinition): boolean {
    return definition.conditions.length > 0 || (definition.groups?.some(hasSegmentConditions) ?? false);
}
