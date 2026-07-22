import type { ActionContext, CreateDefaults, RecordType } from "./types";

/** Coerces a {@link SelectionId} to a numeric record id, or undefined when it is not a finite number. */
function toNumericId(id: string | number): number | undefined {
    const value = typeof id === "number" ? id : Number(id);
    return Number.isFinite(value) ? value : undefined;
}

/**
 * Derives context-aware create prefills from the record the current page is focused on. Prefills are
 * always user-editable downstream, and they are scoped to the active workspace's record so nothing
 * leaks across workspaces. Creating the same type the context already is yields no self-prefill.
 *
 * @param context - the current action context snapshot
 * @param target - the record type being created
 * @returns the prefills to seed the create form with, or undefined when the context offers none
 */
export function deriveCreateDefaults(context: ActionContext, target: RecordType): CreateDefaults | undefined {
    const record = context.record;
    if (!record || record.type === target) return undefined;
    const id = toNumericId(record.id);
    if (id === undefined) return undefined;
    switch (record.type) {
        case "company":
            return { companyId: id, companyLabel: record.label };
        case "person":
            return { personId: id, personLabel: record.label };
        case "deal":
            return { dealId: id, dealLabel: record.label };
        default:
            return undefined;
    }
}
