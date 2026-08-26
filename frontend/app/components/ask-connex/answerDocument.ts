import type {
    AiChatAnswerBlock,
    AiChatAnswerBlockKind,
    AiChatAnswerRow,
    AiChatCitation,
    AiChatCoverage,
    AiChatProgressItem,
} from '@/app/lib/types';
import { parseMysqlDateTime } from '@/app/lib/utils';

/**
 * Localized vocabulary for native answer documents and their evidence surfaces.
 *
 * Every string the answer document renders arrives through this object so the presentational
 * components stay free of `next-intl` and can be rendered directly in tests. The two time
 * functions are supplied by the provider, which owns the shared render clock.
 */
export type AskConnexAnswerDocumentLabels = {
    absoluteTime: (instant: string) => string;
    blockKind: (kind: AiChatAnswerBlockKind) => string;
    boundedRows: (shown: number, total: number) => string;
    viewAll: string;
    citationKind: (kind: AiChatCitation['kind']) => string;
    comparisonAgainst: string;
    comparisonValue: string;
    copyDraft: string;
    copyDraftDone: string;
    coverage: string;
    coverageStatus: (status: AiChatCoverage['status']) => string;
    diffAfter: string;
    diffBefore: string;
    dismiss: string;
    evidence: string;
    evidenceDetail: string;
    exclusions: string;
    exclusion: (exclusion: AiChatCoverage['exclusions'][number]) => string;
    freshness: string;
    freshnessCurrent: string;
    moreDetail: string;
    openRecord: string;
    period: (start: string, end: string) => string;
    progressCount: (count: number) => string;
    progressSource: (source: AiChatProgressItem['source']) => string;
    progressStatus: (status: AiChatProgressItem['status']) => string;
    relativeTime: (instant: string) => string;
    sourceLimits: string;
    sources: string;
    source: (source: AiChatCoverage['sources'][number]) => string;
    truncated: string;
    unsupported: string;
    whatChecked: string;
    withheldEvidence: string;
};

/** The placeholder shown where the server established no value for a structured field. */
export const ANSWER_ROW_PLACEHOLDER = '—';

/**
 * How much of a long answer list this surface shows, and where the rest of it lives.
 *
 * The quick drawer carries a cap and a way into the workspace; the workspace itself carries neither,
 * because it is the place the drawer hands off to and has nowhere further to send a reader.
 */
export type AskConnexAnswerBounds = {
    /** Rows or items to show before withholding the rest, or null to show every one. */
    cap: number | null;
    /** Opens the full workspace on the same chat, or null when this surface already is it. */
    onOpenFullView: (() => void) | null;
};

/** The workspace's own bounds: everything rendered, nowhere further to hand off to. */
export const UNBOUNDED_ANSWER: AskConnexAnswerBounds = { cap: null, onOpenFullView: null };

/** The kinds whose meaning is carried by `rows` rather than by `body`/`items`. */
const STRUCTURED_KINDS: ReadonlySet<AiChatAnswerBlockKind> = new Set<AiChatAnswerBlockKind>([
    'metric',
    'comparison',
    'timeline',
    'diff',
    'extraction',
]);

/**
 * The kinds that assert something about the workspace's own data, so an unsourced one has to say so
 * rather than read as established.
 *
 * The rule is what the block claims, not how it is laid out: `answer` is included because a reply
 * that names no blocks is delivered as a single `answer` block carrying the whole answer, which
 * makes it the most common unsourced claim of all; `extraction` and `diff` are included because
 * both state values read out of the workspace. `inference` and `recommendation` are excluded
 * deliberately — both are already labelled as interpretation and neither claims to be directly
 * evidenced — as are `list`, `draft`, and `limitation`, which assert nothing of their own.
 */
const EVIDENCE_BEARING_KINDS: ReadonlySet<AiChatAnswerBlockKind> = new Set<AiChatAnswerBlockKind>([
    'answer',
    'fact',
    'metric',
    'comparison',
    'timeline',
    'extraction',
    'diff',
]);

/** Whether `kind` renders from structured rows. */
export function isStructuredBlockKind(kind: AiChatAnswerBlockKind): boolean {
    return STRUCTURED_KINDS.has(kind);
}

/**
 * The block's structured rows, tolerating a payload that omitted the field entirely. An older
 * backend, or a message hydrated from a cache written before `rows` existed, sends nothing here.
 */
export function answerRows(block: AiChatAnswerBlock): AiChatAnswerRow[] {
    return Array.isArray(block.rows) ? block.rows : [];
}

/** One row's authorized citations, tolerating a payload that omitted the field. */
export function rowCitations(row: AiChatAnswerRow): AiChatCitation[] {
    return Array.isArray(row.evidence) ? row.evidence : [];
}

/** The block's own authorized citations, tolerating a payload that omitted the field. */
export function blockEvidence(block: AiChatAnswerBlock): AiChatCitation[] {
    return Array.isArray(block.evidence) ? block.evidence : [];
}

/**
 * Whether a factual block reached the viewer with nothing to back it up. Row-level citations count,
 * so a metric grid whose evidence hangs off individual tiles is supported even when the block
 * itself carries none.
 *
 * Every row citation this weighs has to be one the surface actually shows, or an unsourced-looking
 * block would be silently excused by evidence the reader cannot see. A bounded surface therefore
 * keeps the citations of the rows it withheld on screen — see {@link withheldRowEvidence} — rather
 * than dropping them with the rows.
 */
export function isUnsupportedBlock(block: AiChatAnswerBlock): boolean {
    if (!EVIDENCE_BEARING_KINDS.has(block.kind)) return false;
    if (blockEvidence(block).length > 0) return false;
    return answerRows(block).every((row) => rowCitations(row).length === 0);
}

/**
 * The citations belonging to rows a bounded surface did not render, deduplicated and in row order.
 *
 * Bounding is a display limit, not an evidence limit: hiding the one cited row of a long table would
 * otherwise leave five uncited rows reading as established fact, with no marker and no warning. The
 * withheld rows' sources stay reachable on the truncation line instead, attributed to the rows that
 * were withheld rather than to the ones on screen.
 *
 * @param withheld the rows the surface did not render, in document order
 */
export function withheldRowEvidence(withheld: readonly AiChatAnswerRow[]): AiChatCitation[] {
    const seen = new Set<string>();
    const citations: AiChatCitation[] = [];
    for (const row of withheld) {
        for (const citation of rowCitations(row)) {
            const key = citationKey(citation);
            if (seen.has(key)) continue;
            seen.add(key);
            citations.push(citation);
        }
    }
    return citations;
}

/**
 * The document-level limits an evidence inspector has to repeat, so a reader deciding whether to
 * trust one citation sees the same bounds the answer as a whole declared. Truncation and the
 * access-shaped exclusions are the ones that change how a single source should be read; the rest
 * stay in the coverage disclosure.
 */
export function evidenceCaveats(
    coverage: AiChatCoverage | null | undefined,
    labels: AskConnexAnswerDocumentLabels,
): string[] {
    if (!coverage) return [];
    const caveats: string[] = [];
    if (coverage.truncated) caveats.push(labels.truncated);
    const exclusions = Array.isArray(coverage.exclusions) ? coverage.exclusions : [];
    for (const exclusion of exclusions) {
        if (
            exclusion === 'private_data'
            || exclusion === 'restricted_records'
            || exclusion === 'unavailable_sources'
            || exclusion === 'tool_failure'
        ) {
            caveats.push(labels.exclusion(exclusion));
        }
    }
    return caveats;
}

/**
 * The timestamp a time-bearing answer field should render, or `null` when it is not one.
 *
 * Coverage timestamps and row timestamps are declared by the model, and the shared display helpers
 * echo their input verbatim when they cannot read it — which would put raw model text in front of a
 * reader, including a shared-session viewer. Both readers used to render an instant must accept the
 * value, otherwise the answer document drops it rather than showing it.
 * @param value model-declared timestamp
 * @returns the value when it is a real instant, otherwise null
 */
export function answerInstant(value: string | null | undefined): string | null {
    if (!value) return null;
    const readable = !Number.isNaN(parseMysqlDateTime(value))
        && !Number.isNaN(new Date(value).getTime());
    return readable ? value : null;
}

/** A stable React key for one citation inside a block or row. */
export function citationKey(citation: AiChatCitation): string {
    return `${citation.handle}:${citation.kind}:${citation.id}`;
}

const CALENDAR_DATE = /^\d{4}-\d{2}-\d{2}$/;

/**
 * The absolute rendering of an answer timestamp.
 *
 * The step guard accepts an offset instant, an offset-less date-time, and a bare calendar date, and
 * every accepted shape has to read the same way in both halves of a freshness line. This shares
 * {@link parseMysqlDateTime} with `formatRelativeTime` — so an offset-less value is read as UTC and
 * a calendar date as that calendar day — rather than handing the raw string to `new Date`, which
 * reads a bare date as UTC midnight and lands on the previous day west of Greenwich.
 *
 * @param value an instant that already passed {@link answerInstant}
 * @param locale BCP-47 locale tag
 */
export function formatAnswerInstant(value: string, locale: string): string {
    const ms = parseMysqlDateTime(value);
    if (Number.isNaN(ms)) return ANSWER_ROW_PLACEHOLDER;
    const options: Intl.DateTimeFormatOptions = CALENDAR_DATE.test(value.trim())
        ? { dateStyle: 'medium' }
        : { dateStyle: 'medium', timeStyle: 'short' };
    return new Intl.DateTimeFormat(locale, options).format(new Date(ms));
}

/**
 * Collision-free React keys for one rendered answer list, derived from content alone.
 *
 * An answer's rows and blocks carry no server identity, so a key has to come from what the entry
 * says. Two entries can legitimately say the same thing, which would duplicate a key, so repeats
 * are disambiguated by how many identical entries preceded them — stable for every distinguishable
 * entry, and no worse than the index for the interchangeable ones.
 *
 * @param signatures one content signature per entry, in render order
 */
export function answerListKeys(signatures: readonly string[]): string[] {
    const seen = new Map<string, number>();
    return signatures.map((signature) => {
        const repeat = seen.get(signature) ?? 0;
        seen.set(signature, repeat + 1);
        return repeat === 0 ? signature : `${signature}#${repeat}`;
    });
}

/** The content signature of one structured row. */
export function answerRowSignature(row: AiChatAnswerRow): string {
    return [row.label, row.value ?? '', row.detail ?? '', row.at ?? ''].join(' ');
}

/** The content signature of one answer-document block. */
export function answerBlockSignature(block: AiChatAnswerBlock): string {
    return [block.kind, block.title ?? '', block.body ?? '', block.items.join('')]
        .join(' ');
}
