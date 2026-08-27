import type { AiChatCitation } from '@/app/lib/types';
import { parseMysqlDateTime } from '@/app/lib/utils';

/**
 * Localized vocabulary for the assistant's evidence surfaces.
 *
 * Every string the evidence escalation renders arrives through this object so the presentational
 * components stay free of `next-intl` and can be rendered directly in tests. The two time functions
 * are supplied by the caller, which owns the shared render clock.
 */
export type AskConnexAnswerDocumentLabels = {
    absoluteTime: (instant: string) => string;
    citationKind: (kind: AiChatCitation['kind']) => string;
    dismiss: string;
    evidence: string;
    evidenceDetail: string;
    freshness: string;
    freshnessCurrent: string;
    moreDetail: string;
    openRecord: string;
    relativeTime: (instant: string) => string;
    sourceLimits: string;
    unsupported: string;
};

/** The placeholder shown where the server established no value for a structured field. */
export const ANSWER_ROW_PLACEHOLDER = '—';

/** A stable React key for one citation inside an evidence row. */
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
 * @param value a model-declared timestamp
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
