'use client';

import Link from 'next/link';
import { useLocale, useTranslations } from 'next-intl';
import { ArrowLongRightIcon } from '@heroicons/react/24/outline';

import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';
import type { JobMove } from '@/app/lib/types';
import { formatShortDate, initials } from '@/app/lib/utils';

/**
 * Dashboard widget: contacts who recently changed companies. A champion who moves is the warmest
 * possible lead at their new account, so each row deep-links to the contact record.
 */
export default function RecentMoves({ moves }: { moves: JobMove[] }) {
    const t = useTranslations('RecentMoves');
    const locale = useLocale();

    return (
        <div className="flex h-full flex-col overflow-hidden rounded-2xl border border-border bg-card">
            {moves.length === 0 ? (
                <p className="flex-1 px-4 py-10 text-center text-sm text-muted-foreground">{t('empty')}</p>
            ) : (
                <ul className="flex-1 divide-y divide-border">
                    {moves.map((move) => (
                        <li key={move.personId}>
                            <Link
                                href={`/records/contacts/${move.personId}`}
                                className="flex items-center gap-3 px-4 py-2.5 transition-colors hover:bg-muted/50"
                            >
                                <Avatar className="size-8">
                                    <AvatarImage src={move.personImageUrl ?? undefined} />
                                    <AvatarFallback className="text-xs">{initials(move.personName)}</AvatarFallback>
                                </Avatar>
                                <div className="min-w-0 flex-1">
                                    <p className="truncate text-sm font-medium text-foreground">{move.personName}</p>
                                    <p className="flex items-center gap-1 text-xs text-muted-foreground">
                                        <span className="truncate">{move.fromCompanyName ?? t('unknown')}</span>
                                        <ArrowLongRightIcon className="size-3.5 shrink-0" />
                                        <span className="truncate text-foreground">{move.toCompanyName ?? t('unknown')}</span>
                                    </p>
                                </div>
                                <span className="shrink-0 text-xs tabular-nums text-muted-foreground">
                                    {formatShortDate(move.movedAt ?? undefined, locale)}
                                </span>
                            </Link>
                        </li>
                    ))}
                </ul>
            )}
        </div>
    );
}
