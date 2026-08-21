import { describe, expect, it } from 'vitest';

import { missingTimelineDeepLink } from '@/app/components/me/TimelineDeepLinkFallback';

describe('record timeline deep-link fallback', () => {
    const available = {
        task: [2],
        activity: [3],
        note: [4],
    };

    it('does not offer a fallback when the addressed row is rendered', () => {
        expect(missingTimelineDeepLink(new URLSearchParams('task=2'), available)).toBeNull();
    });

    it.each([
        ['task', '/activity/tasks?task=99'],
        ['activity', '/activity/all?activity=99'],
        ['note', '/activity/notes?note=99'],
    ] as const)(
        'links an out-of-window %s to the standalone consumer that fetches it by id',
        (kind, href) => {
            expect(missingTimelineDeepLink(new URLSearchParams(`${kind}=99`), available)).toEqual({
                kind,
                id: 99,
                href,
            });
        },
    );

    it('ignores malformed identities instead of exposing them', () => {
        expect(missingTimelineDeepLink(new URLSearchParams('note=raw-id'), available)).toBeNull();
    });
});
