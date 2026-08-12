import { describe, expect, it } from 'vitest';

import {
    EMPTY_ASK_CONNEX_TURN,
    askConnexCitationHref,
    askConnexCitations,
    askConnexMessageContent,
    askConnexSessionStorageKey,
    askConnexTurnStorageKey,
    extractAskConnexAttachments,
    groupAskConnexMessages,
    mergeAskConnexContext,
    parseStoredAskConnexSession,
    parseStoredAskConnexTurn,
    reduceAskConnexTurn,
    removeAskConnexAttachment,
    serializeStoredAskConnexTurn,
} from '@/app/lib/askConnex';

describe('Ask Connex scoped continuity', () => {
    it('isolates the session and turn keys by user and workspace', () => {
        expect(askConnexSessionStorageKey(7, 11)).toBe('connex:view:7:11:ask-connex:session');
        expect(askConnexSessionStorageKey(7, 11)).not.toBe(askConnexSessionStorageKey(8, 11));
        expect(askConnexSessionStorageKey(7, 11)).not.toBe(askConnexSessionStorageKey(7, 12));
        expect(askConnexTurnStorageKey(7, 11)).toBe('connex:view:7:11:ask-connex:turn');
    });

    it('validates stored session and turn values at the storage boundary', () => {
        const storedTurn = { sessionId: 4, turnId: 9, generationHandle: 'opaque-handle' };

        expect(parseStoredAskConnexSession('14')).toBe(14);
        expect(parseStoredAskConnexSession('14x')).toBeNull();
        expect(parseStoredAskConnexTurn(serializeStoredAskConnexTurn(storedTurn))).toEqual(storedTurn);
        expect(parseStoredAskConnexTurn('{"sessionId":4,"turnId":9}')).toBeNull();
    });
});

describe('Ask Connex context merging', () => {
    it('extracts supported attachments, de-duplicates them, and keeps their labels', () => {
        expect(extractAskConnexAttachments(
            'Ask [Kenji](person:7) about [Acme](company:4) and [Kenji](person:7)',
        )).toEqual([
            { kind: 'person', id: 7, label: 'Kenji' },
            { kind: 'company', id: 4, label: 'Acme' },
        ]);
    });

    it('merges the implicit record first and does not double-count an attached copy', () => {
        const result = mergeAskConnexContext(
            { type: 'person', id: 7, label: 'Kenji' },
            'Summarize [Kenji](person:7) with [Renewal](deal:5)',
        );

        expect(result.pageContext).toEqual([
            { kind: 'person', id: 7 },
            { kind: 'deal', id: 5 },
        ]);
        expect(result.overflow).toBe(false);
    });

    it('reports the ten-record cap instead of silently truncating', () => {
        const content = Array.from(
            { length: 10 },
            (_, index) => `[Deal ${index + 1}](deal:${index + 1})`,
        ).join(' ');
        const result = mergeAskConnexContext(
            { type: 'company', id: 99, label: 'Acme' },
            content,
        );

        expect(result.pageContext).toHaveLength(11);
        expect(result.overflow).toBe(true);
    });

    it('removes only the selected attachment token', () => {
        expect(removeAskConnexAttachment(
            'Compare [Acme](company:4) and [Renewal](deal:5)',
            { kind: 'company', id: 4, label: 'Acme' },
        )).toBe('Compare and [Renewal](deal:5)');
    });

    it('submits readable prompt text while context travels separately', () => {
        expect(askConnexMessageContent('Summarize [Kenji](person:7) at [Acme](company:4)'))
            .toBe('Summarize Kenji at Acme');
    });
});

describe('Ask Connex turn state reduction', () => {
    it('keeps accepted, running, resolved, failed, and timed-out states distinct', () => {
        const accepted = reduceAskConnexTurn(EMPTY_ASK_CONNEX_TURN, {
            type: 'accepted',
            sessionId: 4,
            turnId: 9,
            generationHandle: 'opaque-handle',
            status: 'accepted',
        });
        const running = reduceAskConnexTurn(accepted, { type: 'status', status: 'running' });

        expect(accepted.phase).toBe('accepted');
        expect(running.phase).toBe('running');
        expect(reduceAskConnexTurn(running, { type: 'status', status: 'resolved' }).phase).toBe('resolved');
        expect(reduceAskConnexTurn(running, { type: 'status', status: 'failed', reason: 'provider_error' }))
            .toMatchObject({ phase: 'failed', reason: 'provider_error' });
        expect(reduceAskConnexTurn(running, { type: 'status', status: 'failed', reason: 'budget_exhausted' }))
            .toMatchObject({ phase: 'failed', reason: 'budget_exhausted' });
        expect(reduceAskConnexTurn(running, { type: 'status', status: 'timed_out', reason: 'generation_timeout' }))
            .toMatchObject({ phase: 'timed_out', reason: 'generation_timeout' });
    });
});

describe('Ask Connex citations', () => {
    it('routes each record kind to its records-browser detail path', () => {
        expect(askConnexCitationHref({ handle: 'r1', kind: 'person', id: 7 })).toBe('/records/contacts/7');
        expect(askConnexCitationHref({ handle: 'r2', kind: 'company', id: 4 })).toBe('/records/companies/4');
        expect(askConnexCitationHref({ handle: 'r3', kind: 'deal', id: 9 })).toBe('/records/deals/9');
    });

    it('de-duplicates by record identity rather than handle', () => {
        const result = askConnexCitations([
            { handle: 'r1', kind: 'person', id: 7 },
            { handle: 'r4', kind: 'person', id: 7 },
            { handle: 'r2', kind: 'company', id: 7 },
        ]);
        expect(result).toHaveLength(2);
        expect(result.map((c) => c.kind)).toEqual(['person', 'company']);
    });

    it('caps the rendered list and tolerates a missing projection', () => {
        const many = Array.from({ length: 20 }, (_, i) => ({ handle: `r${i + 1}`, kind: 'deal' as const, id: i + 1 }));
        expect(askConnexCitations(many)).toHaveLength(8);
        expect(askConnexCitations(null)).toEqual([]);
        expect(askConnexCitations(undefined)).toEqual([]);
    });
});

describe('Ask Connex transcript grouping', () => {
    it('groups only consecutive messages from the same sender', () => {
        const messages = [
            { id: 1, sessionId: 4, seq: 1, authorKind: 'user', authorUserId: 7, authorDisplayName: 'Mina', content: 'First', createdAt: '2026-08-11T10:00:00Z' },
            { id: 2, sessionId: 4, seq: 2, authorKind: 'user', authorUserId: 7, authorDisplayName: 'Mina', content: 'Second', createdAt: '2026-08-11T10:01:00Z' },
            { id: 3, sessionId: 4, seq: 3, authorKind: 'user', authorUserId: 8, authorDisplayName: 'Kenji', content: 'Another person', createdAt: '2026-08-11T10:02:00Z' },
            { id: 4, sessionId: 4, seq: 4, authorKind: 'assistant', authorUserId: null, authorDisplayName: null, content: 'Reply', createdAt: '2026-08-11T10:03:00Z' },
            { id: 5, sessionId: 4, seq: 5, authorKind: 'user', authorUserId: 7, authorDisplayName: 'Mina', content: 'Follow-up', createdAt: '2026-08-11T10:04:00Z' },
        ];

        expect(groupAskConnexMessages(messages).map((group) => ({
            authorKind: group.authorKind,
            ids: group.messages.map((message) => message.id),
        }))).toEqual([
            { authorKind: 'user', ids: [1, 2] },
            { authorKind: 'user', ids: [3] },
            { authorKind: 'assistant', ids: [4] },
            { authorKind: 'user', ids: [5] },
        ]);
    });
});
