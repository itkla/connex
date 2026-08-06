import { recordDetailPath } from '@/app/lib/recordReturnPath';
import type { DealBriefCitation, DealBriefCitationKind } from '@/app/lib/types';

/** Positional grounding token as emitted into brief prose (`note.0`, `act.1`, …). */
const SOURCE_TOKEN_RE = /\b(deal|person|act|note|task)\.(\d+)\b/g;

/**
 * Detail href for a brief citation kind + real record id.
 * @param kind wire citation kind
 * @param id positive workspace record id
 */
export function citationHref(kind: DealBriefCitationKind, id: number): string {
    switch (kind) {
        case 'deal':
            return recordDetailPath('deals', id);
        case 'person':
            return recordDetailPath('contacts', id);
        case 'act':
            return recordDetailPath('activities', id);
        case 'note':
            return recordDetailPath('notes', id);
        case 'task':
            return recordDetailPath('tasks', id);
    }
}

/** Indexed citation entry used when rewriting prose tokens. */
export type IndexedCitation = {
    citation: DealBriefCitation;
    index: number;
    href: string;
};

/**
 * Maps positional {@code sourceId} values to 1-based display indices and hrefs.
 * Citations without a usable {@code sourceId} are omitted from the map.
 */
export function indexCitations(citations: readonly DealBriefCitation[]): Map<string, IndexedCitation> {
    const map = new Map<string, IndexedCitation>();
    let displayIndex = 0;
    for (const citation of citations) {
        const sourceId = citation.sourceId?.trim();
        if (!sourceId || map.has(sourceId)) continue;
        displayIndex += 1;
        map.set(sourceId, {
            citation,
            index: displayIndex,
            href: citationHref(citation.kind, citation.id),
        });
    }
    return map;
}

/** One segment of rewritten brief prose — plain text or a numbered citation link. */
export type BriefBodySegment =
    | { type: 'text'; value: string }
    | { type: 'citation'; sourceId: string; index: number; href: string };

/**
 * Rewrites positional tokens in brief prose into numbered citation markers.
 * Tokens that resolve against {@code citations} become citation segments; unmapped tokens are
 * stripped so machine identifiers never reach the reader.
 */
export function rewriteBriefBody(
    body: string,
    citations: readonly DealBriefCitation[],
): BriefBodySegment[] {
    const indexed = indexCitations(citations);
    const segments: BriefBodySegment[] = [];
    let cursor = 0;
    for (const match of body.matchAll(SOURCE_TOKEN_RE)) {
        const start = match.index ?? 0;
        if (start > cursor) {
            segments.push({ type: 'text', value: body.slice(cursor, start) });
        }
        const sourceId = match[0];
        const entry = indexed.get(sourceId);
        if (entry) {
            segments.push({
                type: 'citation',
                sourceId,
                index: entry.index,
                href: entry.href,
            });
        }
        cursor = start + sourceId.length;
    }
    if (cursor < body.length) {
        segments.push({ type: 'text', value: body.slice(cursor) });
    }
    return collapseAdjacentText(segments);
}

/**
 * Prefer a page-known title for a citation chip; otherwise {@code null} so the caller can fall
 * back to the generic {@code source_*} i18n label.
 */
export function citationTitle(
    citation: DealBriefCitation,
    titles: ReadonlyMap<string, string>,
): string | null {
    const key = `${citation.kind}:${citation.id}`;
    const trimmed = titles.get(key)?.trim();
    return trimmed ? trimmed : null;
}

/** Stable map key for a citation title lookup. */
export function citationTitleKey(kind: DealBriefCitationKind, id: number): string {
    return `${kind}:${id}`;
}

function collapseAdjacentText(segments: BriefBodySegment[]): BriefBodySegment[] {
    const collapsed: BriefBodySegment[] = [];
    for (const segment of segments) {
        if (segment.type === 'text') {
            const prev = collapsed.at(-1);
            if (prev?.type === 'text') {
                prev.value = tidyProse(prev.value + segment.value);
                continue;
            }
            const value = tidyProse(segment.value);
            if (!value) continue;
            collapsed.push({ type: 'text', value });
            continue;
        }
        collapsed.push(segment);
    }
    return collapsed;
}

function tidyProse(value: string): string {
    return value
        .replace(/\(\s*\)/g, '')
        .replace(/\[\s*\]/g, '')
        .replace(/[ \t]+([.,;:!?])/g, '$1')
        .replace(/([(\[])[ \t]+/g, '$1')
        .replace(/[ \t]+([)\]])/g, '$1')
        .replace(/[ \t]{2,}/g, ' ');
}
