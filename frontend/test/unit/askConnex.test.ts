import { describe, expect, it } from 'vitest';

import {
    EMPTY_ASK_CONNEX_TURN,
    askConnexMessageContent,
    askConnexSessionStorageKey,
    askConnexTurnStorageKey,
    extractAskConnexAttachments,
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
        expect(reduceAskConnexTurn(running, { type: 'status', status: 'timed_out', reason: 'generation_timeout' }))
            .toMatchObject({ phase: 'timed_out', reason: 'generation_timeout' });
    });
});
