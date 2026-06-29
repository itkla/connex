import Papa from 'papaparse';

import type { ImportEntity } from '@/app/lib/types';

/**
 * A parsed CSV: trimmed header names and the data rows keyed by header. Empty rows are dropped.
 */
export type ParsedCsv = {
    headers: string[];
    rows: Record<string, string>[];
};

/** Inferred data type for a CSV column, used to suggest a custom-field type when auto-creating. */
export type InferredType = 'text' | 'number' | 'date' | 'boolean' | 'url';

/** Standard Connex fields that a column can map to, per entity. Keys mirror the backend exactly. */
export const STANDARD_FIELDS: Record<ImportEntity, readonly string[]> = {
    persons: ['name', 'email', 'phone', 'title', 'company', 'imageUrl'],
    companies: ['name', 'website', 'industry', 'phone', 'address', 'logoUrl'],
    deals: ['name', 'value', 'currency', 'pipeline', 'stage', 'company', 'expectedCloseDate', 'people'],
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
    imageUrl: ['imageurl', 'image', 'avatar', 'photo', 'picture'],
    logoUrl: ['logourl', 'logo'],
    tags: ['tags', 'tag', 'labels', 'label'],
    value: ['value', 'amount', 'dealvalue', 'revenue', 'price', 'worth'],
    currency: ['currency', 'ccy'],
    pipeline: ['pipeline'],
    stage: ['stage', 'status'],
    expectedCloseDate: ['expectedclosedate', 'closedate', 'closingdate', 'expectedclose'],
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
 * Parses a CSV {@link File} client-side. Headers are trimmed; fully-empty rows are dropped.
 * Rejects on a fatal parse error.
 */
export function parseCsv(file: File): Promise<ParsedCsv> {
    return new Promise((resolve, reject) => {
        Papa.parse<Record<string, string>>(file, {
            header: true,
            skipEmptyLines: 'greedy',
            transformHeader: (header) => header.trim(),
            complete: (results) => {
                const headers = (results.meta.fields ?? []).map((h) => h.trim()).filter(Boolean);
                const rows = results.data.filter((row) =>
                    Object.values(row).some((value) => value != null && String(value).trim() !== ''),
                );
                for (const row of rows) {
                    delete (row as Record<string, unknown>).__parsed_extra;
                }
                resolve({ headers, rows });
            },
            error: (error: unknown) => reject(error instanceof Error ? error : new Error(String(error))),
        });
    });
}
