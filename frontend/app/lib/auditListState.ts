import { parseListQuery } from '@/app/hooks/listStateUrl';

export type AuditOutcomeFilter = 'all' | 'success' | 'failed';
export type AuditRangeFilter = 'all' | 'today' | '7d' | '30d';
export type AuditSort = 'newest' | 'oldest';

export type AuditFilters = {
    query: string;
    verbs: Set<string>;
    entities: Set<string>;
    outcome: AuditOutcomeFilter;
    actors: Set<string>;
    range: AuditRangeFilter;
};

function readActors(value: string | null): Set<string> {
    if (!value) return new Set();
    try {
        const parsed: unknown = JSON.parse(value);
        if (Array.isArray(parsed) && parsed.every((actor: unknown) => typeof actor === 'string')) {
            return new Set(parsed.filter((actor: string) => actor.length > 0));
        }
    } catch {
        return new Set();
    }
    return new Set();
}

/** Restores the audit browser's owned URL state, defaulting malformed single-choice filters. */
export function readAuditListState(params: Pick<URLSearchParams, 'get'> | null): { filters: AuditFilters; sort: AuditSort } {
    const values = (key: string) => new Set((params?.get(key) ?? '').split(',').filter(Boolean));
    const outcome = params?.get('outcome');
    const range = params?.get('range');
    return {
        filters: {
            query: parseListQuery(params?.get('q') ?? null),
            verbs: values('verbs'),
            entities: values('entities'),
            outcome: outcome === 'success' || outcome === 'failed' ? outcome : 'all',
            actors: readActors(params?.get('actors') ?? null),
            range: range === 'today' || range === '7d' || range === '30d' ? range : 'all',
        },
        sort: params?.get('sort') === 'oldest' ? 'oldest' : 'newest',
    };
}

/** Serializes only audit-owned keys; JSON preserves punctuation in actor labels. */
export function auditListParams(filters: AuditFilters, sort: AuditSort): Record<string, string | undefined> {
    return {
        q: filters.query.trim() || undefined,
        verbs: [...filters.verbs].join(',') || undefined,
        entities: [...filters.entities].join(',') || undefined,
        outcome: filters.outcome === 'all' ? undefined : filters.outcome,
        actors: filters.actors.size ? JSON.stringify([...filters.actors]) : undefined,
        range: filters.range === 'all' ? undefined : filters.range,
        sort: sort === 'newest' ? undefined : sort,
    };
}
