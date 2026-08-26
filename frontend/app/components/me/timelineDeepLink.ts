import {
    ACTIVITY_URL_KEY,
    NOTE_URL_KEY,
    parseDeepLinkId,
    TASK_URL_KEY,
} from '@/app/hooks/listStateUrl';

export type TimelineDeepLinkKind = 'task' | 'activity' | 'note';

export type TimelineDeepLinkIds = Readonly<Record<TimelineDeepLinkKind, readonly number[]>>;

export type TimelineDeepLinkTarget = {
    kind: TimelineDeepLinkKind;
    id: number;
    href: string;
    state: 'outside_window' | 'unavailable';
};

const DEEP_LINK_CONSUMERS = [
    { kind: 'task', key: TASK_URL_KEY, path: '/activity/tasks' },
    { kind: 'activity', key: ACTIVITY_URL_KEY, path: '/activity/all' },
    { kind: 'note', key: NOTE_URL_KEY, path: '/activity/notes' },
] as const;

/** Resolves an addressed timeline item without claiming an unfetched or inaccessible target is old. */
export function missingTimelineDeepLink(
    searchParams: Pick<URLSearchParams, 'get'>,
    visible: TimelineDeepLinkIds,
    known: TimelineDeepLinkIds,
): TimelineDeepLinkTarget | null {
    for (const consumer of DEEP_LINK_CONSUMERS) {
        const id = parseDeepLinkId(searchParams.get(consumer.key));
        if (id === null || visible[consumer.kind].includes(id)) continue;
        const outsideWindow = known[consumer.kind].includes(id);
        return {
            kind: consumer.kind,
            id,
            href: outsideWindow
                ? `${consumer.path}?${consumer.key}=${id}`
                : consumer.path,
            state: outsideWindow ? 'outside_window' : 'unavailable',
        };
    }
    return null;
}
