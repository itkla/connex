import { ApiError } from '@/app/lib/api';
import type { CustomFieldSchemaEntry, ImportColumnMapping, ImportPreviewResult, ImportRowAnalysis } from '@/app/lib/types';

export const REVIEW_PAGE_SIZE = 20;
export const REVIEW_FILTERS = ['all', 'decision', 'invalid', 'create', 'match', 'skip'] as const;
export type ReviewFilter = typeof REVIEW_FILTERS[number];

/** Joins shared bounded dependency candidates locally without repeating them in the response. */
export function importReviewRows(preview: ImportPreviewResult): ImportRowAnalysis[] {
    return preview.rows.map((row) => row.companyDependency ? { ...row, companyDependency: {
        ...row.companyDependency,
        candidates: preview.companyCandidates?.[row.companyDependency.key] ?? [],
    } } : row);
}

/** Keeps every source row reachable, including skipped and combined rows. */
export function reviewPage(rows: ImportRowAnalysis[], filter: ReviewFilter, page: number) {
    const filtered = rows.filter((row) => filter === 'all'
        || (filter === 'decision' ? needsImportDecision(row) : row.status === filter));
    const pages = Math.max(1, Math.ceil(filtered.length / REVIEW_PAGE_SIZE));
    const current = Math.min(Math.max(0, page), pages - 1);
    return { rows: filtered.slice(current * REVIEW_PAGE_SIZE, (current + 1) * REVIEW_PAGE_SIZE),
        total: filtered.length, pages, current };
}

/** Automatic identity matches need no wall of secondary name suggestions. */
export function needsImportDecision(row: ImportRowAnalysis): boolean {
    return Boolean(row.companyDependency?.candidates?.length && row.companyDependency.selectedId == null)
        || row.status === 'create' && Boolean(row.candidates?.length);
}

/** Duplicate single-value targets are refused before preview; tags intentionally allow several columns. */
export function duplicateMappingColumns(mapping: ImportColumnMapping[]): Set<string> {
    const byTarget = new Map<string, string[]>();
    for (const item of mapping) {
        const target = item.field ?? (item.createCustomField
            ? `new:${item.customFieldLabel?.trim() ?? item.column.trim()}:${item.customFieldType ?? 'text'}` : null);
        if (!target || target === 'tags') continue;
        byTarget.set(target, [...(byTarget.get(target) ?? []), item.column]);
    }
    return new Set([...byTarget.values()].filter((columns) => columns.length > 1).flat());
}

/** Exact, unique field labels may be suggested; ambiguous labels stay unmapped. */
export function existingCustomTarget(header: string, fields: CustomFieldSchemaEntry[]): string | null {
    const normalize = (value: string) => value.normalize('NFKC').toLocaleLowerCase('en').trim();
    const matches = fields.filter((field) => normalize(field.label) === normalize(header));
    return matches.length === 1 ? `custom:${matches[0].definitionId}` : null;
}

/** Bulk choices affect only rows whose complete visible candidate set includes that company. */
export function companyChoiceRows(rows: ImportRowAnalysis[], source: ImportRowAnalysis, companyId: number): number[] {
    const dependency = source.companyDependency;
    if (!dependency || dependency.truncated) return [];
    return rows.filter((row) => row.companyDependency?.key === dependency.key
        && !row.companyDependency.truncated
        && row.companyDependency.candidates?.some((candidate) => candidate.recordId === companyId))
        .map((row) => row.rowIndex);
}

/** Only stable API codes choose presentation copy; server diagnostic text stays out of the UI. */
export function importFailureKey(error: unknown): string {
    if (!(error instanceof ApiError)) return 'failures.UNAVAILABLE';
    switch (error.code) {
        case 'IMPORT_REVIEW_TOO_LARGE': return 'failures.REVIEW_TOO_LARGE';
        case 'IMPORT_CUSTOM_COLLISION': return 'failures.CUSTOM_COLLISION';
        case 'IMPORT_CUSTOM_CONFLICT': return 'failures.CUSTOM_CONFLICT';
        case 'IMPORT_CUSTOM_UNAVAILABLE': return 'failures.CUSTOM_UNAVAILABLE';
        case 'IMPORT_MAPPING_DUPLICATE': return 'failures.MAPPING_DUPLICATE';
        case 'IMPORT_PARTICIPANT_AMBIGUOUS': return 'failures.PARTICIPANT_AMBIGUOUS';
        case 'IMPORT_ROW_CHOICE_INVALID': return 'failures.ROW_CHOICE_INVALID';
    }
    if (error.status === 409) return 'failures.REVIEW_CHANGED';
    if (error.status === 403) return 'failures.PERMISSION';
    if (error.status === 429) return 'failures.RATE_LIMIT';
    if (error.status === 400) return 'failures.INVALID_REQUEST';
    return 'failures.UNAVAILABLE';
}
