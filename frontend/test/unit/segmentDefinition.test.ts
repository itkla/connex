import { describe, expect, it } from "vitest";
import {
    evaluableSegmentDefinition,
    hasSegmentConditions,
    normalizeSegmentDefinition,
    removeSegmentCondition,
    segmentConditionEntries,
} from "@/app/lib/segmentDefinition";
import type { SegmentCondition, SegmentDefinition } from "@/app/lib/types";

const predicate = (key: string): SegmentCondition => ({ type: "predicate", key });
const field = (overrides: Partial<SegmentCondition>): SegmentCondition => ({
    type: "field",
    field: "name",
    op: "eq",
    ...overrides,
});

describe("normalizeSegmentDefinition", () => {
    it("normalizes a well-formed tree and strips unknown extras", () => {
        const result = normalizeSegmentDefinition({
            match: "all",
            conditions: [{ type: "predicate", key: "warm", junk: "dropped" }],
            groups: [{ match: "any", conditions: [{ type: "field", field: "name", op: "contains", value: "a" }] }],
        });
        expect(result).toEqual({
            match: "all",
            conditions: [{ type: "predicate", key: "warm" }],
            groups: [{ match: "any", conditions: [{ type: "field", field: "name", op: "contains", value: "a" }] }],
        });
    });

    it("rejects malformed roots and conditions", () => {
        expect(normalizeSegmentDefinition(null)).toBeNull();
        expect(normalizeSegmentDefinition([])).toBeNull();
        expect(normalizeSegmentDefinition({ match: "some", conditions: [] })).toBeNull();
        expect(normalizeSegmentDefinition({ match: "all", conditions: [{ type: "predicate" }] })).toBeNull();
        expect(normalizeSegmentDefinition({ match: "all", conditions: [{ type: "field", field: "x" }] })).toBeNull();
        expect(
            normalizeSegmentDefinition({ match: "all", conditions: [{ type: "field", field: "x", op: "eq", days: "7" }] }),
        ).toBeNull();
    });

    it("enforces the nesting depth cap of 4", () => {
        const nest = (depth: number): Record<string, unknown> =>
            depth === 0
                ? { match: "all", conditions: [{ type: "predicate", key: "p" }] }
                : { match: "all", conditions: [], groups: [nest(depth - 1)] };
        expect(normalizeSegmentDefinition(nest(3))).not.toBeNull();
        expect(normalizeSegmentDefinition(nest(4))).toBeNull();
    });

    it("enforces the per-group and total condition caps", () => {
        const manyConditions = Array.from({ length: 17 }, () => ({ type: "predicate", key: "p" }));
        expect(normalizeSegmentDefinition({ match: "all", conditions: manyConditions })).toBeNull();

        const sixteen = Array.from({ length: 16 }, () => ({ type: "predicate", key: "p" }));
        const threeGroups = {
            match: "all",
            conditions: sixteen,
            groups: [
                { match: "all", conditions: sixteen },
                { match: "all", conditions: sixteen },
            ],
        };
        expect(normalizeSegmentDefinition(threeGroups)).toBeNull();

        const nineGroups = {
            match: "all",
            conditions: [],
            groups: Array.from({ length: 9 }, () => ({ match: "all", conditions: [] })),
        };
        expect(normalizeSegmentDefinition(nineGroups)).toBeNull();
    });

    it("keeps negate only when explicitly true", () => {
        expect(normalizeSegmentDefinition({ match: "all", conditions: [], negate: true })).toEqual({
            match: "all",
            conditions: [],
            negate: true,
        });
        expect(normalizeSegmentDefinition({ match: "all", conditions: [], negate: "yes" })).toBeNull();
    });
});

describe("evaluableSegmentDefinition", () => {
    it("drops incomplete field conditions but keeps predicates and structure", () => {
        const definition: SegmentDefinition = {
            match: "all",
            conditions: [predicate("warm"), field({ value: "" }), field({ value: "acme" })],
            groups: [{ match: "any", conditions: [field({ value: "   " })] }],
            negate: true,
        };
        const evaluable = evaluableSegmentDefinition(definition);
        expect(evaluable.conditions).toEqual([predicate("warm"), field({ value: "acme" })]);
        expect(evaluable.groups).toBeUndefined();
        expect(evaluable.negate).toBe(true);
    });

    it("trims empty entries out of in-lists and keeps is_set/within_days without values", () => {
        const evaluable = evaluableSegmentDefinition({
            match: "all",
            conditions: [
                field({ op: "in", values: [" a ", "", "b"] }),
                field({ op: "in", values: ["", "  "] }),
                field({ op: "is_set" }),
                field({ op: "within_days", days: 7 }),
            ],
        });
        expect(evaluable.conditions).toHaveLength(3);
        expect(evaluable.conditions[0].values).toEqual(["a", "b"]);
    });
});

describe("hasSegmentConditions / segmentConditionEntries", () => {
    const definition: SegmentDefinition = {
        match: "all",
        conditions: [predicate("warm"), field({ value: "" })],
        groups: [{ match: "any", conditions: [field({ value: "x" })] }],
    };

    it("detects conditions at any nesting level", () => {
        expect(hasSegmentConditions(definition)).toBe(true);
        expect(hasSegmentConditions({ match: "all", conditions: [] })).toBe(false);
        expect(
            hasSegmentConditions({
                match: "all",
                conditions: [],
                groups: [{ match: "any", conditions: [predicate("p")] }],
            }),
        ).toBe(true);
    });

    it("indexes only evaluable conditions with stable group paths", () => {
        const entries = segmentConditionEntries(definition);
        expect(entries).toHaveLength(2);
        expect(entries[0]).toMatchObject({ groupPath: [], conditionIndex: 0 });
        expect(entries[1]).toMatchObject({ groupPath: [0], conditionIndex: 0 });
    });
});

describe("removeSegmentCondition", () => {
    it("removes a root condition by index", () => {
        const result = removeSegmentCondition(
            { match: "all", conditions: [predicate("a"), predicate("b")] },
            [],
            0,
        );
        expect(result.conditions).toEqual([predicate("b")]);
    });

    it("prunes a nested group left structurally empty", () => {
        const result = removeSegmentCondition(
            {
                match: "all",
                conditions: [predicate("keep")],
                groups: [{ match: "any", conditions: [predicate("only")] }],
            },
            [0],
            0,
        );
        expect(result.groups).toBeUndefined();
        expect(result.conditions).toEqual([predicate("keep")]);
    });
});
