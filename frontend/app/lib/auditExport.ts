/** Maximum number of loaded audit rows a single UI export can select. */
export const AUDIT_EXPORT_SELECTION_LIMIT = 200;

/** Rejects an empty or invalid selection so a filtered export cannot become an unfiltered one. */
export function auditExportSelection(ids: readonly number[]): string {
    if (ids.length === 0 || ids.length > AUDIT_EXPORT_SELECTION_LIMIT
        || ids.some((id) => !Number.isSafeInteger(id) || id <= 0 || id > 2_147_483_647)) {
        throw new Error("Invalid audit export selection");
    }
    return ids.join(",");
}
