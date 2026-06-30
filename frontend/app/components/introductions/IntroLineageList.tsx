'use client';

import Link from 'next/link';
import { useLocale, useTranslations } from 'next-intl';
import { ArrowLongRightIcon } from '@heroicons/react/24/outline';

import { formatRelativeTime } from '@/app/lib/utils';
import type { IntroductionRecord } from '@/app/lib/types';

import PartyAvatar from './PartyAvatar';

/**
 * The lineage feed: introductions the team has made, newest first. Each row shows the two contacts
 * that were connected, when, and by whom — the goodwill that compounds over time (issue #43).
 */
export default function IntroLineageList({ items }: { items: IntroductionRecord[] }) {
    const t = useTranslations('Introductions');
    const locale = useLocale();

    if (items.length === 0) {
        return (
            <div className="rounded-2xl border border-border bg-card">
                <p className="px-6 py-10 text-center text-sm text-muted-foreground">{t('lineageEmpty')}</p>
            </div>
        );
    }

    return (
        <div className="overflow-hidden rounded-2xl border border-border bg-card">
            <ul className="divide-y divide-border">
                {items.map((item) => (
                    <li key={item.id} className="px-4 py-3 sm:px-6">
                        <div className="flex items-center gap-3">
                            <div className="flex min-w-0 flex-1 flex-wrap items-center gap-x-2 gap-y-1">
                                <span className="flex min-w-0 items-center gap-2">
                                    <PartyAvatar imageUrl={item.personAImageUrl} />
                                    <Link
                                        href={`/records/contacts/${item.personAId}`}
                                        className="truncate text-sm font-medium text-foreground transition-colors hover:text-brand-hover"
                                    >
                                        {item.personAName}
                                    </Link>
                                </span>
                                <ArrowLongRightIcon className="size-4 shrink-0 text-muted-foreground" aria-hidden />
                                <span className="flex min-w-0 items-center gap-2">
                                    <PartyAvatar imageUrl={item.personBImageUrl} />
                                    <Link
                                        href={`/records/contacts/${item.personBId}`}
                                        className="truncate text-sm font-medium text-foreground transition-colors hover:text-brand-hover"
                                    >
                                        {item.personBName}
                                    </Link>
                                </span>
                            </div>
                            <div className="shrink-0 text-right">
                                <p className="text-xs text-muted-foreground">
                                    {formatRelativeTime(item.introducedAt, locale)}
                                </p>
                                {item.introducerName ? (
                                    <p className="text-xs text-muted-foreground/70">
                                        {t('byIntroducer', { name: item.introducerName })}
                                    </p>
                                ) : null}
                            </div>
                        </div>
                        {item.note ? (
                            <p className="mt-1.5 truncate text-xs text-muted-foreground">{item.note}</p>
                        ) : null}
                    </li>
                ))}
            </ul>
        </div>
    );
}
