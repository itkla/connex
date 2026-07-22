import type { SegmentCondition, SegmentDefinition } from '@/app/lib/types';

const MAX_SEGMENT_DEPTH = 4;
const MAX_SEGMENT_CONDITIONS = 32;
const MAX_GROUP_CONDITIONS = 16;
const MAX_GROUPS = 8;

type SegmentConditionEntry = {
    condition: SegmentCondition;
    groupPath: number[];
    conditionIndex: number;
};

function isRecord(value: unknown): value is Record<string, unknown> {
    return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function optionalString(value: unknown): value is string | undefined {
    return value === undefined || typeof value === 'string';
}

function normalizeCondition(value: unknown): SegmentCondition | null {
    if (!isRecord(value) || (value.type !== 'predicate' && value.type !== 'field')) return null;
    if (!optionalString(value.key)
        || !optionalString(value.field)
        || !optionalString(value.op)
        || !optionalString(value.value)
        || (value.days !== undefined && typeof value.days !== 'number')
        || (value.negate !== undefined && typeof value.negate !== 'boolean')
        || (value.values !== undefined
            && (!Array.isArray(value.values) || !value.values.every((entry) => typeof entry === 'string')))) {
        return null;
    }
    if (value.type === 'predicate' && typeof value.key !== 'string') return null;
    if (value.type === 'field' && (typeof value.field !== 'string' || typeof value.op !== 'string')) return null;
    return {
        type: value.type,
        ...(value.key !== undefined ? { key: value.key } : {}),
        ...(value.days !== undefined ? { days: value.days } : {}),
        ...(value.field !== undefined ? { field: value.field } : {}),
        ...(value.op !== undefined ? { op: value.op } : {}),
        ...(value.value !== undefined ? { value: value.value } : {}),
        ...(value.values !== undefined ? { values: [...value.values] } : {}),
        ...(value.negate === true ? { negate: true } : {}),
    };
}

function normalizeDefinition(
    value: unknown,
    depth: number,
    conditionCount: { value: number },
): SegmentDefinition | null {
    if (!isRecord(value) || depth > MAX_SEGMENT_DEPTH || (value.match !== 'all' && value.match !== 'any')) return null;
    if (value.conditions !== undefined && value.conditions !== null && !Array.isArray(value.conditions)) return null;
    if (value.groups !== undefined && value.groups !== null && !Array.isArray(value.groups)) return null;
    if (value.negate !== undefined && typeof value.negate !== 'boolean') return null;

    const rawConditions = value.conditions ?? [];
    const rawGroups = value.groups ?? [];
    if (rawConditions.length > MAX_GROUP_CONDITIONS || rawGroups.length > MAX_GROUPS) return null;
    conditionCount.value += rawConditions.length;
    if (conditionCount.value > MAX_SEGMENT_CONDITIONS) return null;

    const conditions: SegmentCondition[] = [];
    for (const condition of rawConditions) {
        const normalized = normalizeCondition(condition);
        if (!normalized) return null;
        conditions.push(normalized);
    }
    const groups: SegmentDefinition[] = [];
    for (const group of rawGroups) {
        const normalized = normalizeDefinition(group, depth + 1, conditionCount);
        if (!normalized) return null;
        groups.push(normalized);
    }
    return {
        match: value.match,
        conditions,
        ...(groups.length > 0 ? { groups } : {}),
        ...(value.negate === true ? { negate: true } : {}),
    };
}

function isEvaluableCondition(condition: SegmentCondition): boolean {
    if (condition.type === 'predicate') return true;
    if (condition.op === 'is_set' || condition.op === 'within_days') return true;
    if (condition.op === 'in') return condition.values?.some((value) => value.trim() !== '') ?? false;
    return (condition.value ?? '').trim() !== '';
}

/** Normalizes a bounded segment tree from a saved-view or API boundary, or returns null when malformed. */
export function normalizeSegmentDefinition(value: unknown): SegmentDefinition | null {
    return normalizeDefinition(value, 1, { value: 0 });
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

/** Returns every evaluable condition with its stable location in the recursive definition tree. */
export function segmentConditionEntries(
    definition: SegmentDefinition,
    groupPath: number[] = [],
): SegmentConditionEntry[] {
    const own = definition.conditions.flatMap((condition, conditionIndex) =>
        isEvaluableCondition(condition) ? [{ condition, groupPath, conditionIndex }] : [],
    );
    const nested = (definition.groups ?? []).flatMap((group, groupIndex) =>
        segmentConditionEntries(group, [...groupPath, groupIndex]),
    );
    return [...own, ...nested];
}

function hasMembers(definition: SegmentDefinition): boolean {
    return definition.conditions.length > 0 || (definition.groups?.some(hasMembers) ?? false);
}

/** Removes one condition by recursive path and prunes any groups left structurally empty. */
export function removeSegmentCondition(
    definition: SegmentDefinition,
    groupPath: number[],
    conditionIndex: number,
): SegmentDefinition {
    if (groupPath.length === 0) {
        const groups = (definition.groups ?? []).filter(hasMembers);
        return {
            ...definition,
            conditions: definition.conditions.filter((_, index) => index !== conditionIndex),
            groups: groups.length > 0 ? groups : undefined,
        };
    }
    const [targetGroup, ...remainingPath] = groupPath;
    const groups = (definition.groups ?? []).flatMap((group, groupIndex) => {
        if (groupIndex !== targetGroup) return [group];
        const updated = removeSegmentCondition(group, remainingPath, conditionIndex);
        return hasMembers(updated) ? [updated] : [];
    });
    return {
        ...definition,
        groups: groups.length > 0 ? groups : undefined,
    };
}
