'use client';

import Link from 'next/link';
import { useLocale, useTranslations } from 'next-intl';
import { ArrowLongRightIcon, CheckIcon } from '@heroicons/react/24/outline';

import { formatRelativeTime } from '@/app/lib/utils';
import type { IntroductionRecord } from '@/app/lib/types';
import NoteContent from '@/app/components/activity/notes/NoteContent';

import PartyAvatar from './PartyAvatar';

/**
 * The lineage timeline: introductions the team has made, newest first, rendered as a connected feed
 * so the compounding goodwill reads as history. Each node shows the two contacts that were connected,
 * when, by whom, and the note (issue #43).
 */
export default function IntroLineageList({ items }: { items: IntroductionRecord[] }) {
    const t = useTranslations('Introductions');
    const locale = useLocale();

    if (items.length === 0) {
        return (
            <div className="flex flex-col items-center justify-center gap-2 rounded-2xl border border-dashed border-border bg-card px-6 py-14 text-center">
                <span className="grid size-9 place-items-center rounded-full bg-muted text-muted-foreground">
                    <CheckIcon className="size-5" aria-hidden />
                </span>
                <p className="max-w-md text-sm text-muted-foreground">{t('lineageEmpty')}</p>
            </div>
        );
    }

    return (
        <ol className="overflow-hidden rounded-2xl border border-border bg-card">
            {items.map((item, index) => {
                const byline = item.introducerName ? t('byIntroducer', { name: item.introducerName }) : null;
                return (
                    <li
                        key={item.id}
                        className="flex gap-4 px-4 py-4 transition-colors hover:bg-muted/30 sm:px-6"
                    >
                        <div className="flex flex-col items-center" aria-hidden>
                            <span className="grid size-7 shrink-0 place-items-center rounded-full bg-brand/12 text-brand ring-1 ring-inset ring-brand/25">
                                <CheckIcon className="size-3.5" />
                            </span>
                            {index < items.length - 1 ? (
                                <span className="mt-1 w-px flex-1 bg-border" />
                            ) : null}
                        </div>

                        <div className="min-w-0 flex-1">
                            <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
                                <span className="flex min-w-0 items-center gap-2">
                                    <PartyAvatar imageUrl={item.personAImageUrl} />
                                    <Link
                                        href={`/records/contacts/${item.personAId}`}
                                        className="truncate text-sm font-medium text-foreground transition-colors hover:text-brand-hover"
                                    >
                                        {item.personAName}
                                    </Link>
                                </span>
                                <ArrowLongRightIcon
                                    className="size-4 shrink-0 text-muted-foreground"
                                    aria-hidden
                                />
                                <span className="flex min-w-0 items-center gap-2">
                                    <PartyAvatar imageUrl={item.personBImageUrl} />
                                    <Link
                                        href={`/records/contacts/${item.personBId}`}
                                        className="truncate text-sm font-medium text-foreground transition-colors hover:text-brand-hover"
                                    >
                                        {item.personBName}
                                    </Link>
                                </span>
                                <span className="ml-auto shrink-0 text-xs text-muted-foreground tabular-nums">
                                    {formatRelativeTime(item.introducedAt, locale)}
                                </span>
                            </div>
                            {byline ? (
                                <p className="mt-1 truncate text-xs text-muted-foreground">{byline}</p>
                            ) : null}
                            {item.note ? (
                                <p className="mt-1 line-clamp-2 text-xs text-muted-foreground">
                                    <NoteContent content={item.note} references={item.references} />
                                </p>
                            ) : null}
                        </div>
                    </li>
                );
            })}
        </ol>
    );
}
