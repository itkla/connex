'use client';

import Link from 'next/link';
import { ArrowRightIcon, ClockIcon } from '@heroicons/react/24/outline';
import { useSearchParams } from 'next/navigation';
import { useTranslations } from 'next-intl';

import {
    ACTIVITY_URL_KEY,
    NOTE_URL_KEY,
    parseDeepLinkId,
    TASK_URL_KEY,
} from '@/app/hooks/listStateUrl';

type TimelineDeepLinkKind = 'task' | 'activity' | 'note';

type TimelineDeepLinkAvailability = Readonly<Record<TimelineDeepLinkKind, readonly number[]>>;

type TimelineDeepLinkTarget = {
    kind: TimelineDeepLinkKind;
    id: number;
    href: string;
};

const DEEP_LINK_CONSUMERS = [
    { kind: 'task', key: TASK_URL_KEY, path: '/activity/tasks' },
    { kind: 'activity', key: ACTIVITY_URL_KEY, path: '/activity/all' },
    { kind: 'note', key: NOTE_URL_KEY, path: '/activity/notes' },
] as const;

/** Resolves an addressed timeline item only when it is absent from the rendered history window. */
export function missingTimelineDeepLink(
    searchParams: Pick<URLSearchParams, 'get'>,
    available: TimelineDeepLinkAvailability,
): TimelineDeepLinkTarget | null {
    for (const consumer of DEEP_LINK_CONSUMERS) {
        const id = parseDeepLinkId(searchParams.get(consumer.key));
        if (id !== null && !available[consumer.kind].includes(id)) {
            return {
                kind: consumer.kind,
                id,
                href: `${consumer.path}?${consumer.key}=${id}`,
            };
        }
    }
    return null;
}

export default function TimelineDeepLinkFallback({
    available,
}: {
    available: TimelineDeepLinkAvailability;
}) {
    const t = useTranslations('MeTimeline');
    const searchParams = useSearchParams();
    const target = missingTimelineDeepLink(searchParams, available);
    if (target === null) return null;

    return (
        <div className="flex items-start gap-3 border-b border-border bg-muted/40 px-4 py-3 text-sm">
            <ClockIcon className="mt-0.5 size-4 shrink-0 text-muted-foreground" />
            <div className="min-w-0 flex-1">
                <p className="text-foreground">{t('linkedItemOutsideWindow')}</p>
                <Link
                    href={target.href}
                    className="mt-1 inline-flex items-center gap-1 font-medium text-brand underline-offset-4 hover:underline"
                >
                    {t(`openLinked.${target.kind}`)}
                    <ArrowRightIcon className="size-3.5" />
                </Link>
            </div>
        </div>
    );
}
