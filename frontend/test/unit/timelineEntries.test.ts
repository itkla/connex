import { readFileSync } from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';

import {
    buildTimeline,
    commentsFromThreads,
    entryAuthorId,
    entryId,
    type TimelineEntry,
} from '@/app/components/me/timelineEntries';
import type {
    Activity,
    ContactLifecycleHistoryEntry,
    Note,
    PersonCampaignTouch,
    RecordComment,
    RecordCommentThread,
    Task,
} from '@/app/lib/types';

function comment(overrides: Partial<RecordComment> & Pick<RecordComment, 'id' | 'createdAt'>): RecordComment {
    return {
        threadId: 1,
        author: { id: 7, displayName: 'Mei Tanaka' },
        content: 'Looks good to me',
        deletedAt: null,
        ...overrides,
    };
}

function thread(comments: RecordComment[]): RecordCommentThread {
    return {
        id: 1,
        targetType: 'person',
        targetId: 5,
        createdByUserId: 7,
        state: 'open',
        version: 1,
        createdAt: '2026-01-01T00:00:00Z',
        updatedAt: '2026-01-01T00:00:00Z',
        comments,
    };
}

const EMPTY = {
    tasks: [] as Task[],
    activities: [] as Activity[],
    notes: [] as Note[],
    lifecycleHistory: [] as ContactLifecycleHistoryEntry[],
    comments: [] as RecordComment[],
    campaignTouches: [] as PersonCampaignTouch[],
};

describe('the record timeline carries comments', () => {
    it('files a comment among the other histories, newest first', () => {
        const entries = buildTimeline({
            ...EMPTY,
            activities: [
                { id: 11, subject: 'Kickoff call', timestamp: '2026-03-01T09:00:00Z' } as Activity,
                { id: 12, subject: 'Follow-up call', timestamp: '2026-03-05T09:00:00Z' } as Activity,
            ],
            comments: [comment({ id: 21, createdAt: '2026-03-03T09:00:00Z' })],
        });

        expect(entries.map((entry) => [entry.kind, entryId(entry)])).toEqual([
            ['activity', 12],
            ['comment', 21],
            ['activity', 11],
        ]);
    });

    it('dates an edited comment by its edit, the same way a note is dated by its update', () => {
        const [entry] = buildTimeline({
            ...EMPTY,
            comments: [
                comment({ id: 30, createdAt: '2026-03-01T09:00:00Z', editedAt: '2026-03-04T09:00:00Z' }),
            ],
        });

        expect(entry.sortAt).toBe(Date.parse('2026-03-04T09:00:00Z'));
    });

    it('attributes a comment to its own author, and tolerates an erased one', () => {
        const attributed: TimelineEntry = {
            kind: 'comment',
            sortAt: 0,
            comment: comment({ id: 40, createdAt: '2026-03-01T09:00:00Z' }),
        };
        const orphaned: TimelineEntry = {
            kind: 'comment',
            sortAt: 0,
            comment: comment({ id: 41, createdAt: '2026-03-01T09:00:00Z', author: null }),
        };

        expect(entryAuthorId(attributed)).toBe(7);
        expect(entryAuthorId(orphaned)).toBeUndefined();
    });

    it('flattens threads and drops the tombstones a redacted comment leaves behind', () => {
        const comments = commentsFromThreads([
            thread([
                comment({ id: 50, createdAt: '2026-03-01T09:00:00Z' }),
                comment({ id: 51, createdAt: '2026-03-02T09:00:00Z', content: null, deletedAt: '2026-03-03T09:00:00Z' }),
            ]),
            thread([comment({ id: 52, createdAt: '2026-03-04T09:00:00Z' })]),
        ]);

        expect(comments.map((entry) => entry.id)).toEqual([50, 52]);
    });

    it('leaves the thread view as the comment composer, and the timeline as the chronology', () => {
        const row = readFileSync(
            path.resolve(process.cwd(), 'app/components/me/TimelineRow.tsx'),
            'utf8',
        );

        expect(row).toContain("comment: 'chipComment'");
        expect(row).toContain('commentThreadHref(pathname, searchParams, comment.id)');
        expect(row).toContain(
            "entry.kind === 'lifecycle' || entry.kind === 'comment' || entry.kind === 'campaign';",
        );
    });

    it('never nests an anchor inside an anchor, whatever the comment mentions', () => {
        const row = readFileSync(
            path.resolve(process.cwd(), 'app/components/me/TimelineRow.tsx'),
            'utf8',
        );

        for (const match of row.matchAll(/<NoteContent/g)) {
            const before = row.slice(0, match.index);
            const opened = (before.match(/<Link[\s>]/g) ?? []).length;
            const closed = (before.match(/<\/Link>/g) ?? []).length;

            expect(
                opened - closed,
                'NoteContent renders mention and record chips as anchors, so it may never sit inside a Link',
            ).toBe(0);
        }
        expect(row).toContain("{t('commentViewInThread')}");
    });

    it('re-runs the thread scroll when the linked comment changes, not once per mount', () => {
        const section = readFileSync(
            path.resolve(process.cwd(), 'app/components/records/comments/CommentsSection.tsx'),
            'utf8',
        );
        const reset = section.slice(
            section.indexOf('highlightScrolled.current = false'),
            section.indexOf('highlightScrolled.current = false') + 90,
        );

        expect(reset).toContain('[highlightedCommentId]');
    });
});
