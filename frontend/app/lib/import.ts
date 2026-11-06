import Papa from 'papaparse';

import type { ImportColumnMapping, ImportEntity } from '@/app/lib/types';

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

/** Client-side name parts composed into the backend's single person name field before review. */
export const PERSON_NAME_PARTS = ['givenName', 'familyName'] as const;

/** Explicit ordering for composing a person's name; full-name columns are never split. */
export type PersonNameOrder = 'given_family' | 'family_given';

/** Japanese name-part headers default to family first; otherwise use the interface locale. */
export function defaultPersonNameOrder(headers: string[], locale: string): PersonNameOrder {
    return locale === 'ja' || headers.some((header) => ['姓', '名'].includes(normalizeHeader(header)))
        ? 'family_given' : 'given_family';
}

const SYNONYMS: Record<string, readonly string[]> = {
    givenName: ['firstname', 'givenname', '名'],
    familyName: ['lastname', 'familyname', 'surname', '姓'],
    email: [
        'email', 'emailaddress', 'mail', 'primaryemail', 'workemail', 'businessemail', 'personalemail', 'otheremail',
        'メール', 'メールアドレス', 'eメール', 'eメールアドレス',
    ],
    phone: [
        'phone', 'tel', 'telephone', 'mobile', 'cell', 'phonenumber', 'telephonenumber',
        'mobilephone', 'mobilephonenumber', 'mobilenumber', 'homephone', 'otherphone',
        'businessphone', 'workphone', 'officephone', 'primaryphone', 'cellphone', 'cellphonenumber',
        'mainphone', 'corporatephone', 'companyphone', 'companyphonenumber',
        '電話', '電話番号', '携帯電話', '携帯電話番号',
    ],
    title: ['title', 'jobtitle', 'position', 'role', 'businesstitle', 'jobrole', 'jobposition', '役職', '肩書'],
    company: ['company', 'companyname', 'organization', 'organisation', 'account', 'accountname', 'employer', '会社', '会社名', '企業名'],
    website: [
        'website', 'url', 'domain', 'companydomainname', 'homepage', 'web', 'site', 'websiteurl',
        'webaddress', 'companywebsite', 'companywebsiteurl', 'companydomain', 'domainname',
        'ウェブサイト', 'ホームページ', 'ドメイン',
    ],
    industry: ['industry', 'sector', 'vertical', 'industrysector', 'businesssector', 'businessvertical', '業種'],
    address: [
        'address', 'streetaddress', 'billingaddress', 'shippingaddress', 'mailingaddress',
        'companyaddress', 'officeaddress', 'businessaddress', 'location', '住所', '所在地',
    ],
    tags: ['tags', 'tag', 'labels', 'label', 'タグ', 'ラベル'],
    value: ['value', 'amount', 'dealvalue', '金額', '案件金額'],
    currency: ['currency', 'ccy', '通貨'],
    pipeline: ['pipeline', 'パイプライン'],
    stage: ['stage', 'dealstage', 'ステージ', '案件ステージ'],
    expectedCloseDate: ['expectedclosedate', 'closedate', 'closingdate', 'expectedclose', '完了予定日'],
    closedAt: ['closedat', 'actualclosedate', 'closeddate', '決着日時'],
    createdAt: ['createdat', 'createddate', 'creationdate', '作成日時'],
    people: ['people', 'contacts', 'attendees', 'participants', '連絡先', '参加者'],
};

const NAME_SYNONYMS: Record<ImportEntity, readonly string[]> = {
    persons: ['name', 'fullname', 'contact', 'contactname', 'contactfullname', 'displayname', '氏名', '名前', '姓名', 'フルネーム'],
    companies: ['name', 'fullname', 'displayname', 'company', 'companyname', 'organizationname', 'organisationname', 'accountname', '会社', '会社名', '企業名'],
    deals: ['name', 'dealname', 'opportunityname', '案件名'],
};

function normalizeHeader(header: string): string {
    return header.normalize('NFKC').trim().toLowerCase().replace(/[\s_-]/gu, '');
}

/** Mapping options for an entity, including person name parts composed only on the client. */
export function importFieldOptions(entity: ImportEntity): readonly string[] {
    return [...STANDARD_FIELDS[entity], ...(entity === 'persons' ? PERSON_NAME_PARTS : []), 'tags'];
}

/**
 * Suggests a Connex field key for a CSV header, or null if nothing matches with confidence.
 * Only exact known aliases match after width, case and separator normalization.
 */
export function suggestField(header: string, entity: ImportEntity): string | null {
    const normalized = normalizeHeader(header);
    if (!normalized) return null;
    for (const field of importFieldOptions(entity)) {
        const synonyms = field === 'name' ? NAME_SYNONYMS[entity] : SYNONYMS[field] ?? [];
        if (synonyms.some((alias) => normalizeHeader(alias) === normalized)) return field;
    }
    return null;
}

/**
 * Suggests initial mappings without choosing between competing columns for a single-valued target.
 * An unambiguous full-name column takes precedence over split-name suggestions.
 */
export function suggestColumnTargets(headers: string[], entity: ImportEntity): Record<string, string> {
    const suggestions = headers.map((header) => ({ header, field: suggestField(header, entity) }));
    const counts = new Map<string, number>();
    for (const { field } of suggestions) {
        if (field) counts.set(field, (counts.get(field) ?? 0) + 1);
    }
    return Object.fromEntries(suggestions.map(({ header, field }) => [
        header,
        field && (field === 'tags' || counts.get(field) === 1)
            && !(counts.has('name') && isPersonNamePart(field))
            ? field : IGNORE_COLUMN,
    ]));
}

function isPersonNamePart(field: string | null | undefined): boolean {
    return PERSON_NAME_PARTS.some((part) => part === field);
}

/** The exact rows/mapping sent to both preview and commit, with name conflicts left uncommittable. */
export type PreparedImport = {
    rows: ParsedCsv['rows'];
    mapping: ImportColumnMapping[];
    nameColumn: string | null;
    usesNameParts: boolean;
    nameConflict: boolean;
};

/**
 * Composes selected person name parts into one collision-free name column for the existing API.
 * Source values remain intact, and the derived values and mapping participate in the review proof.
 * Empty parts are omitted; an existing full-name column is kept byte-for-byte and never split.
 */
export function prepareImport(
    parsed: ParsedCsv,
    entity: ImportEntity,
    mapping: ImportColumnMapping[],
    order: PersonNameOrder,
): PreparedImport {
    const fullName = mapping.find((column) => column.field === 'name');
    const parts = entity === 'persons' ? mapping.filter((column) => isPersonNamePart(column.field)) : [];
    const given = parts.filter((column) => column.field === 'givenName');
    const family = parts.filter((column) => column.field === 'familyName');
    const nameConflict = parts.length > 0 && (fullName !== undefined || given.length > 1 || family.length > 1);
    if (parts.length === 0 || nameConflict) {
        return { rows: parsed.rows, mapping, nameColumn: fullName?.column ?? null, usesNameParts: parts.length > 0, nameConflict };
    }
    const occupied = new Set(parsed.headers);
    for (const row of parsed.rows) {
        for (const column of Object.keys(row)) occupied.add(column);
    }
    let nameColumn = '__connex_import_name__';
    while (occupied.has(nameColumn)) nameColumn += '_';
    const ordered = order === 'family_given' ? [...family, ...given] : [...given, ...family];
    const rows = parsed.rows.map((row) => ({
        ...row,
        [nameColumn]: ordered.flatMap(({ column }) => {
            const part = (row[column] ?? '').trim();
            return part ? [part] : [];
        }).join(' '),
    }));
    return {
        rows,
        mapping: [...mapping.filter((column) => !isPersonNamePart(column.field)), { column: nameColumn, field: 'name' }],
        nameColumn,
        usesNameParts: true,
        nameConflict: false,
    };
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

/** Localizes the currency row refusal for both review and downloadable import errors. */
export function importRowErrorMessage(error: string | readonly string[], invalidCurrencyMessage: string): string {
    const errors = typeof error === 'string' ? error.split('; ') : error;
    return errors.map((part) => part === 'CURRENCY_INVALID' ? invalidCurrencyMessage : part).join('; ');
}
