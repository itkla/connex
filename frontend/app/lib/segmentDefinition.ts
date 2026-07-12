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
        values: condition.values?.map((value) => value.trim()).filter((value) => value !== ''),
    };
}

/** Removes incomplete field conditions while preserving nested-group and negation semantics. */
export function evaluableSegmentDefinition(definition: SegmentDefinition): SegmentDefinition {
    const groups = (definition.groups ?? [])
        .map(evaluableSegmentDefinition)
        .filter(hasSegmentConditions);
    return {
        match: definition.match,
        conditions: definition.conditions
            .map(evaluableCondition)
            .filter((condition): condition is SegmentCondition => condition !== null),
        groups: groups.length > 0 ? groups : undefined,
        negate: definition.negate,
    };
}

/** Whether a segment definition contains any evaluable condition at any nesting level. */
export function hasSegmentConditions(definition: SegmentDefinition): boolean {
    return definition.conditions.length > 0 || (definition.groups?.some(hasSegmentConditions) ?? false);
}
