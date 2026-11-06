import { ApiError } from '@/app/lib/api';
import type { CustomFieldSchemaEntry, ImportColumnMapping, ImportPreviewResult, ImportProblem, ImportRowAnalysis } from '@/app/lib/types';

export const REVIEW_PAGE_SIZE = 20;
export const REVIEW_FILTERS = ['all', 'decision', 'invalid', 'create', 'match', 'skip'] as const;
export type ReviewFilter = typeof REVIEW_FILTERS[number];

/** Prefers structured codes; legacy diagnostics expose only recognized refusals or generic guidance. */
export function importPresentationProblems(row: Pick<ImportRowAnalysis, 'problems' | 'errors'> & { reason?: string }): ImportProblem[] {
    if (row.problems?.length) return row.problems;
    const diagnostics = row.errors?.length ? row.errors : row.reason ? [row.reason] : [];
    const codes = diagnostics.flatMap((diagnostic) => diagnostic.split('; ')).map((diagnostic) => {
        if (diagnostic === 'CURRENCY_INVALID') return 'CURRENCY_INVALID';
        if (diagnostic === 'Company name variant requires an explicit company selection') return 'COMPANY_VARIANT_CHOICE_REQUIRED';
        return 'ROW_INVALID';
    });
    return [...new Set(codes)].map((code) => ({ code }));
}

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

/** Server-resolved custom keys remain authoritative when labels or catalogue keys collide. */
export function importMappingErrorColumns(error: unknown, headers: string[]): Set<string> {
    if (!(error instanceof ApiError)
        || !['IMPORT_MAPPING_DUPLICATE', 'IMPORT_CUSTOM_COLLISION'].includes(error.code ?? '')
        || !error.fieldErrors) return new Set();
    const columns = new Set<string>();
    for (const header of headers) {
        if (Object.hasOwn(error.fieldErrors, header)) columns.add(header);
    }
    return columns;
}

/** Refreshing a schema preserves explicit mappings and identifies targets that need replacement. */
export function unavailableCustomColumns(mapping: ImportColumnMapping[], fields: CustomFieldSchemaEntry[]): Set<string> {
    const available = new Set(fields.map((field) => `custom:${field.definitionId}`));
    const columns = new Set<string>();
    for (const item of mapping) {
        if (item.field?.startsWith('custom:') && !available.has(item.field)) columns.add(item.column);
    }
    return columns;
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
    const indices: number[] = [];
    for (const row of rows) {
        const current = row.companyDependency;
        if (current?.key === dependency.key && !current.truncated
            && current.candidates?.some((candidate) => candidate.recordId === companyId)) {
            indices.push(row.rowIndex);
        }
    }
    return indices;
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
