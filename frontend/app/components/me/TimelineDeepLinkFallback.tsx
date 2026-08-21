'use client';

import Link from 'next/link';
import { ArrowRightIcon, ClockIcon, LinkSlashIcon } from '@heroicons/react/24/outline';
import { useSearchParams } from 'next/navigation';
import { useTranslations } from 'next-intl';

import { missingTimelineDeepLink, type TimelineDeepLinkIds } from './timelineDeepLink';

export default function TimelineDeepLinkFallback({
    visible,
    known,
}: {
    visible: TimelineDeepLinkIds;
    known: TimelineDeepLinkIds;
}) {
    const t = useTranslations('MeTimeline');
    const searchParams = useSearchParams();
    const target = missingTimelineDeepLink(searchParams, visible, known);
    if (target === null) return null;
    const TargetIcon = target.state === 'outside_window' ? ClockIcon : LinkSlashIcon;

    return (
        <div className="flex items-start gap-3 border-b border-border bg-muted/40 px-4 py-3 text-sm">
            <TargetIcon className="mt-0.5 size-4 shrink-0 text-muted-foreground" />
            <div className="min-w-0 flex-1">
                <p className="text-foreground">
                    {t(target.state === 'outside_window' ? 'linkedItemOutsideWindow' : 'linkedItemUnavailable')}
                </p>
                <Link
                    href={target.href}
                    className="mt-1 inline-flex items-center gap-1 font-medium text-brand underline-offset-4 hover:underline"
                >
                    {t(`${target.state === 'outside_window' ? 'openLinked' : 'viewLinked'}.${target.kind}`)}
                    <ArrowRightIcon className="size-3.5" />
                </Link>
            </div>
        </div>
    );
}
