import { describe, expect, it } from 'vitest';

import { missingTimelineDeepLink } from '@/app/components/me/timelineDeepLink';

describe('record timeline deep-link fallback', () => {
    const visible = {
        task: [2],
        activity: [3],
        note: [4],
    };
    const known = {
        task: [2, 99],
        activity: [3, 99],
        note: [4, 99],
    };

    it('does not offer a fallback when the addressed row is rendered', () => {
        expect(missingTimelineDeepLink(new URLSearchParams('task=2'), visible, known)).toBeNull();
    });

    it.each([
        ['task', '/activity/tasks?task=99'],
        ['activity', '/activity/all?activity=99'],
        ['note', '/activity/notes?note=99'],
    ] as const)(
        'links an out-of-window %s to the standalone consumer that fetches it by id',
        (kind, href) => {
            expect(missingTimelineDeepLink(new URLSearchParams(`${kind}=99`), visible, known)).toEqual({
                kind,
                id: 99,
                href,
                state: 'outside_window',
            });
        },
    );

    it.each([
        ['task', '/activity/tasks'],
        ['activity', '/activity/all'],
        ['note', '/activity/notes'],
    ] as const)(
        'does not assert that an unknown %s is merely outside the rendered window',
        (kind, href) => {
            expect(missingTimelineDeepLink(new URLSearchParams(`${kind}=88`), visible, known)).toEqual({
                kind,
                id: 88,
                href,
                state: 'unavailable',
            });
        },
    );

    it('ignores malformed identities instead of exposing them', () => {
        expect(missingTimelineDeepLink(new URLSearchParams('note=raw-id'), visible, known)).toBeNull();
    });
});
