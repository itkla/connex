import { describe, expect, it } from 'vitest';

import {
    citationHref,
    citationTitle,
    citationTitleKey,
    indexCitations,
    rewriteBriefBody,
} from '@/app/lib/dealBriefCitations';
import type { DealBriefCitation } from '@/app/lib/types';

const citations: DealBriefCitation[] = [
    { sourceId: 'note.0', kind: 'note', id: 182 },
    { sourceId: 'act.1', kind: 'act', id: 44 },
    { sourceId: 'person.0', kind: 'person', id: 73 },
];

describe('dealBriefCitations', () => {
    it('maps citation kinds to record detail hrefs', () => {
        expect(citationHref('deal', 9)).toBe('/records/deals/9');
        expect(citationHref('person', 73)).toBe('/records/contacts/73');
        expect(citationHref('act', 44)).toBe('/activity/activities/44');
        expect(citationHref('note', 182)).toBe('/activity/notes/182');
        expect(citationHref('task', 12)).toBe('/activity/tasks/12');
    });

    it('indexes citations by sourceId with 1-based display order', () => {
        const indexed = indexCitations(citations);
        expect(indexed.get('note.0')?.index).toBe(1);
        expect(indexed.get('act.1')?.href).toBe('/activity/activities/44');
        expect(indexed.get('person.0')?.citation.id).toBe(73);
    });

    it('rewrites mapped tokens into numbered citation segments', () => {
        const segments = rewriteBriefBody(
            'Champion quiet since note.0; last meeting was act.1 with person.0.',
            citations,
        );
        expect(segments).toEqual([
            { type: 'text', value: 'Champion quiet since ' },
            { type: 'citation', sourceId: 'note.0', index: 1, href: '/activity/notes/182' },
            { type: 'text', value: '; last meeting was ' },
            { type: 'citation', sourceId: 'act.1', index: 2, href: '/activity/activities/44' },
            { type: 'text', value: ' with ' },
            { type: 'citation', sourceId: 'person.0', index: 3, href: '/records/contacts/73' },
            { type: 'text', value: '.' },
        ]);
    });

    it('strips unmapped positional tokens from prose', () => {
        const segments = rewriteBriefBody(
            'Keep this note.0 but drop task.9 entirely.',
            [{ sourceId: 'note.0', kind: 'note', id: 182 }],
        );
        const text = segments
            .map((segment) => (segment.type === 'text' ? segment.value : `[${segment.index}]`))
            .join('');
        expect(text).toBe('Keep this [1] but drop entirely.');
        expect(text).not.toContain('task.9');
        expect(text).not.toContain('note.0');
    });

    it('tidies punctuation left after stripping unmapped tokens', () => {
        const segments = rewriteBriefBody(
            'Quiet since act.1. See (task.9) today. Risk from note.0, then act.9.',
            [{ sourceId: 'note.0', kind: 'note', id: 182 }],
        );
        const text = segments
            .map((segment) => (segment.type === 'text' ? segment.value : `[${segment.index}]`))
            .join('');
        expect(text).toBe('Quiet since. See today. Risk from [1], then.');
        expect(text).not.toMatch(/\(\)/);
        expect(text).not.toContain('act.1');
        expect(text).not.toContain('task.9');
    });

    it('prefers page-known titles over generic labels', () => {
        const titles = new Map([[citationTitleKey('note', 182), 'Q3 renewal notes']]);
        expect(citationTitle(citations[0], titles)).toBe('Q3 renewal notes');
        expect(citationTitle(citations[1], titles)).toBeNull();
    });
});
