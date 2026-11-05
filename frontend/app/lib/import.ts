import Papa from 'papaparse';

import type { ImportEntity } from '@/app/lib/types';

/** Supported CSV encodings, with Shift-JIS decoded using the browser's Windows-932 mapping. */
export type CsvEncoding = 'utf-8' | 'shift_jis';

/** A CSV rejected for decoding errors, replacement characters, or suspicious fallback text. */
export class CsvEncodingError extends Error {
    constructor() {
        super('CSV text could not be decoded reliably in a supported encoding');
        this.name = 'CsvEncodingError';
    }
}

/**
 * A parsed CSV: trimmed header names and the data rows keyed by header. Empty rows are dropped.
 */
export type ParsedCsv = {
    encoding: CsvEncoding;
    headers: string[];
    rows: Record<string, string>[];
};

/** Inferred data type for a CSV column, used to suggest a custom-field type when auto-creating. */
export type InferredType = 'text' | 'number' | 'date' | 'boolean' | 'url';

/** Mapping target for a column the import should leave out entirely. */
export const IGNORE_COLUMN = 'ignore';

/** Mapping target for a column that should define a new custom field, gated on `CUSTOM_FIELD_MANAGE`. */
export const CREATE_CUSTOM_FIELD = '__create__';

/**
 * The mapping target a chosen column actually resolves to.
 *
 * A "create custom field" choice made by someone who may no longer make it resolves to ignored.
 * The wizard hides that option from a viewer without `CUSTOM_FIELD_MANAGE`, but a role change
 * takes effect while the dialog is open, so the choice can outlive the permission that allowed
 * it. Resolving it here keeps the mapped count, the column selector and the committed mapping
 * agreeing with one another, and keeps the wizard from submitting a mapping whose only possible
 * outcome is a refusal at commit time.
 *
 * @param target - the column's chosen mapping target
 * @param canCreateCustomField - whether the viewer holds `CUSTOM_FIELD_MANAGE`
 * @returns the target to map, count and display
 */
export function resolveColumnTarget(
    target: string | undefined,
    canCreateCustomField: boolean,
): string {
    if (target === undefined) return IGNORE_COLUMN;
    if (target === CREATE_CUSTOM_FIELD && !canCreateCustomField) return IGNORE_COLUMN;
    return target;
}

/** Standard Connex fields that a column can map to, per entity. Keys mirror the backend exactly. */
export const STANDARD_FIELDS: Record<ImportEntity, readonly string[]> = {
    persons: ['name', 'email', 'phone', 'title', 'company'],
    companies: ['name', 'website', 'industry', 'phone', 'address'],
    deals: ['name', 'value', 'currency', 'pipeline', 'stage', 'company', 'expectedCloseDate', 'closedAt', 'createdAt', 'people'],
};

const SYNONYMS: Record<string, readonly string[]> = {
    name: ['name', 'fullname', 'contact', 'contactname', 'dealname', 'companyname'],
    email: ['email', 'emailaddress', 'mail'],
    phone: ['phone', 'tel', 'telephone', 'mobile', 'cell', 'phonenumber'],
    title: ['title', 'jobtitle', 'role', 'position'],
    company: ['company', 'organization', 'organisation', 'account', 'employer'],
    website: ['website', 'url', 'web', 'site', 'domain', 'homepage'],
    industry: ['industry', 'sector', 'vertical'],
    address: ['address', 'location'],
    tags: ['tags', 'tag', 'labels', 'label'],
    value: ['value', 'amount', 'dealvalue', 'revenue', 'price', 'worth'],
    currency: ['currency', 'ccy'],
    pipeline: ['pipeline'],
    stage: ['stage', 'status'],
    expectedCloseDate: ['expectedclosedate', 'closedate', 'closingdate', 'expectedclose'],
    closedAt: ['closedat', 'actualclosedate', 'closeddate'],
    createdAt: ['createdat', 'createddate', 'creationdate'],
    people: ['people', 'contacts', 'attendees', 'participants'],
};

function normalizeHeader(header: string): string {
    return header.trim().toLowerCase().replace(/[^a-z0-9]/g, '');
}

/**
 * Suggests a Connex field key for a CSV header, or null if nothing matches with confidence.
 * "tags" is offered for every entity; other fields come from the entity's standard set.
 */
export function suggestField(header: string, entity: ImportEntity): string | null {
    const normalized = normalizeHeader(header);
    if (!normalized) return null;
    const candidates = [...STANDARD_FIELDS[entity], 'tags'];
    for (const field of candidates) {
        const synonyms = SYNONYMS[field] ?? [field.toLowerCase()];
        if (synonyms.some((s) => s === normalized || normalized === field.toLowerCase())) return field;
    }
    for (const field of candidates) {
        const synonyms = SYNONYMS[field] ?? [field.toLowerCase()];
        if (synonyms.some((s) => normalized.includes(s) || s.includes(normalized))) return field;
    }
    return null;
}

function isBoolish(value: string): boolean {
    return /^(true|false|yes|no|1|0)$/i.test(value);
}

function isNumeric(value: string): boolean {
    const cleaned = value.replace(/[,\s]/g, '');
    return cleaned !== '' && !Number.isNaN(Number(cleaned));
}

function isIsoDate(value: string): boolean {
    if (!/^\d{4}-\d{2}-\d{2}/.test(value)) return false;
    return !Number.isNaN(Date.parse(value));
}

function isUrl(value: string): boolean {
    return /^https?:\/\//i.test(value) || /^[a-z0-9-]+(\.[a-z0-9-]+)+/i.test(value);
}

/** Infers a column's type from a sample of its values, for custom-field-type suggestion. */
export function inferColumnType(values: string[]): InferredType {
    const sample = values.map((v) => (v ?? '').trim()).filter(Boolean).slice(0, 50);
    if (sample.length === 0) return 'text';
    if (sample.every(isBoolish)) return 'boolean';
    if (sample.every(isNumeric)) return 'number';
    if (sample.every(isIsoDate)) return 'date';
    if (sample.every(isUrl)) return 'url';
    return 'text';
}

/** Sample values for a column, drawn from the first {@code limit} rows. */
export function columnSamples(rows: Record<string, string>[], header: string, limit = 8): string[] {
    const out: string[] = [];
    for (const row of rows) {
        const value = (row[header] ?? '').trim();
        if (value) out.push(value);
        if (out.length >= limit) break;
    }
    return out;
}

/**
 * Parses a CSV {@link File} client-side, trying strict UTF-8 before Windows-932 (Shift-JIS).
 * Headers are trimmed; fully-empty rows are dropped. Rejects undecodable or replacement text
 * before any headers or values can reach column mapping or import review.
 * Successful Shift-JIS fallback alone cannot identify the source encoding. Only on fallback,
 * rejects a CJK unified ideograph between ASCII letters or halfwidth katakana adjacent to an
 * ASCII letter as suspected Western mojibake. This heuristic can reject genuine mixed-script
 * Shift-JIS and cannot detect every unsupported encoding; re-save refused files as CSV UTF-8.
 */
export async function parseCsv(file: File): Promise<ParsedCsv> {
    const bytes = await file.arrayBuffer();
    let encoding: CsvEncoding = 'utf-8';
    let text: string;
    try {
        text = new TextDecoder('utf-8', { fatal: true }).decode(bytes);
    } catch {
        encoding = 'shift_jis';
        try {
            text = new TextDecoder('shift_jis', { fatal: true }).decode(bytes);
        } catch {
            throw new CsvEncodingError();
        }
    }
    if (text.includes('\uFFFD')) throw new CsvEncodingError();
    if (encoding === 'shift_jis'
        && /[A-Za-z]\p{Unified_Ideograph}[A-Za-z]|[A-Za-z][\uFF66-\uFF9F]|[\uFF66-\uFF9F][A-Za-z]/u.test(text)) {
        throw new CsvEncodingError();
    }

    const results = Papa.parse<Record<string, string>>(text, {
        header: true,
        skipEmptyLines: 'greedy',
        transformHeader: (header) => header.trim(),
    });
    const headers = (results.meta.fields ?? []).map((h) => h.trim()).filter(Boolean);
    const rows = results.data.filter((row) =>
        Object.values(row).some((value) => value != null && String(value).trim() !== ''),
    );
    for (const row of rows) {
        delete row.__parsed_extra;
    }
    return { encoding, headers, rows };
}
