import { describe, expect, it } from 'vitest';
import { auditListParams, readAuditListState } from '@/app/lib/auditListState';

describe('audit URL state', () => {
    it('round-trips every filter, Unicode actor punctuation and sort', () => {
        const params = new URLSearchParams({ q: ' consent ', verbs: 'create,delete', entities: 'person,company',
            outcome: 'failed', actors: JSON.stringify(['山田, 太郎', 'Operations & support']), range: '7d', sort: 'oldest' });
        const { filters, sort } = readAuditListState(params);
        expect(filters).toEqual({ query: 'consent', verbs: new Set(['create', 'delete']), entities: new Set(['person', 'company']),
            outcome: 'failed', actors: new Set(['山田, 太郎', 'Operations & support']), range: '7d' });
        expect(sort).toBe('oldest');
        const encoded = new URLSearchParams(Object.entries(auditListParams(filters, sort)).flatMap(
            ([key, value]) => value === undefined ? [] : [[key, value]],
        ));
        expect(readAuditListState(encoded)).toEqual({ filters, sort });
    });

    it('omits defaults so clearing filters removes only audit-owned keys', () => {
        const defaults = readAuditListState(null);
        expect(Object.values(auditListParams(defaults.filters, defaults.sort))).toEqual(Array(7).fill(undefined));
    });

    it.each(['not-json', '{}', '[1]', '[null]'])('ignores malformed actor selections: %s', (actors) => {
        const state = readAuditListState(new URLSearchParams({ actors, outcome: 'invalid', range: '-1', sort: 'random' }));
        expect(state).toEqual(readAuditListState(null));
    });

    it.each(['today', '7d', '30d'])('restores the supported %s date range and success outcome', (range) => {
        expect(readAuditListState(new URLSearchParams({ range, outcome: 'success' })).filters).toMatchObject({ range, outcome: 'success' });
    });
});
