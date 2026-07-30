import type {
    HistoryImportColumnMapping,
    HistoryImportKind,
    HistoryImportRowAnalysis,
} from '@/app/lib/types';

export const HISTORY_IMPORT_REVIEW_PAGE_SIZE = 100;

export type HistoryImportField = {
    key: string;
    required: boolean;
};

const COMMON_FIELDS: readonly HistoryImportField[] = [
    { key: 'occurredAt', required: true },
    { key: 'participantEmail', required: false },
    { key: 'participantPhone', required: false },
    { key: 'sourceId', required: false },
];

const KIND_FIELDS: Record<HistoryImportKind, readonly HistoryImportField[]> = {
    activities: [
        { key: 'subject', required: true },
        { key: 'type', required: false },
        { key: 'notes', required: false },
    ],
    notes: [
        { key: 'content', required: true },
        { key: 'title', required: false },
    ],
    tasks: [
        { key: 'description', required: true },
        { key: 'dueDate', required: false },
        { key: 'completed', required: false },
    ],
};

const SYNONYMS: Record<string, readonly string[]> = {
    occurredAt: ['occurredat', 'timestamp', 'datetime', 'date', 'createdat', 'activitydate'],
    participantEmail: ['participantemail', 'contactemail', 'email', 'emailaddress'],
    participantPhone: ['participantphone', 'contactphone', 'phone', 'telephone', 'mobile'],
    sourceId: ['sourceid', 'externalid', 'eventid', 'recordid', 'activityid'],
    subject: ['subject', 'summary', 'activitysubject'],
    type: ['type', 'activitytype', 'channel'],
    notes: ['notes', 'details', 'body'],
    content: ['content', 'note', 'notebody', 'body'],
    title: ['title', 'notetitle'],
    description: ['description', 'task', 'taskdescription', 'subject'],
    dueDate: ['duedate', 'deadline'],
    completed: ['completed', 'done', 'iscomplete', 'status'],
};

const EXACT_SYNONYMS = new Map(
    Object.entries(SYNONYMS).map(([field, synonyms]) => [
        field,
        new Set(synonyms),
    ]),
);

function normalizeHeader(header: string): string {
    return header.trim().toLowerCase().replace(/[^a-z0-9]/g, '');
}

/** Fields available to one interaction-history import kind. */
export function historyImportFields(kind: HistoryImportKind): readonly HistoryImportField[] {
    return [...COMMON_FIELDS, ...KIND_FIELDS[kind]];
}

/** Suggests a history field for a CSV header using exact, then contained, normalized synonyms. */
export function suggestHistoryImportField(
    header: string,
    kind: HistoryImportKind,
): string | null {
    const normalized = normalizeHeader(header);
    if (!normalized) return null;
    const fields = historyImportFields(kind);
    for (const field of fields) {
        const exact = EXACT_SYNONYMS.get(field.key);
        if (exact?.has(normalized) || field.key.toLowerCase() === normalized) {
            return field.key;
        }
    }
    for (const field of fields) {
        const synonyms = SYNONYMS[field.key] ?? [field.key.toLowerCase()];
        if (synonyms.some((synonym) => normalized.includes(synonym) || synonym.includes(normalized))) {
            return field.key;
        }
    }
    return null;
}

/** Builds the backend mapping while omitting ignored columns. */
export function buildHistoryImportMapping(
    headers: readonly string[],
    targets: Readonly<Record<string, string>>,
): HistoryImportColumnMapping[] {
    return headers.flatMap((column) => {
        const field = targets[column];
        return field && field !== 'ignore' ? [{ column, field }] : [];
    });
}

/** Returns whether required kind fields and at least one participant identity are mapped once. */
export function historyImportMappingIsComplete(
    kind: HistoryImportKind,
    targets: Readonly<Record<string, string>>,
): boolean {
    const selectedValues = Object.values(targets).filter((field) => field !== 'ignore');
    const selected = new Set(selectedValues);
    if (selected.size !== selectedValues.length) return false;
    if (!selected.has('participantEmail') && !selected.has('participantPhone')) return false;
    return historyImportFields(kind)
        .filter((field) => field.required)
        .every((field) => selected.has(field.key));
}

/** Returns one bounded review page with attention rows ordered before settled rows. */
export function historyImportReviewPage(
    rows: readonly HistoryImportRowAnalysis[],
    requestedPage: number,
): {
    rows: HistoryImportRowAnalysis[];
    page: number;
    pageCount: number;
    from: number;
    to: number;
} {
    const attention = rows.filter((row) =>
        row.status === 'needs_review' || row.status === 'invalid',
    );
    const settled = rows.filter((row) =>
        row.status === 'ready' || row.status === 'already_imported',
    );
    const ordered = [...attention, ...settled];
    const pageCount = Math.max(
        1,
        Math.ceil(ordered.length / HISTORY_IMPORT_REVIEW_PAGE_SIZE),
    );
    const page = Math.min(Math.max(1, requestedPage), pageCount);
    const start = (page - 1) * HISTORY_IMPORT_REVIEW_PAGE_SIZE;
    const pageRows = ordered.slice(
        start,
        start + HISTORY_IMPORT_REVIEW_PAGE_SIZE,
    );
    return {
        rows: pageRows,
        page,
        pageCount,
        from: pageRows.length === 0 ? 0 : start + 1,
        to: start + pageRows.length,
    };
}
