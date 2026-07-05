'use client';

import Link from 'next/link';
import { useLocale, useTranslations } from 'next-intl';
import { ArrowLongRightIcon } from '@heroicons/react/24/outline';

import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';
import { type JobMove } from '@/app/lib/types';
import { formatShortDate, initials } from '@/app/lib/utils';

const MAX_ROWS = 6;

/**
 * Analytics panel body: contacts who recently changed companies, newest first. A champion who moves
 * is the warmest possible lead at their new account. Renders bare (no card shell) for placement
 * inside an analytics {@link Panel}.
 */
export default function RecentMovesList({ moves }: { moves: JobMove[] }) {
    const t = useTranslations('RecentMoves');
    const locale = useLocale();

    if (moves.length === 0) {
        return <div className="flex h-56 items-center justify-center text-sm text-muted-foreground">{t('empty')}</div>;
    }

    return (
        <ul className="flex flex-col gap-1">
            {moves.slice(0, MAX_ROWS).map((move) => (
                <li key={move.personId}>
                    <Link
                        href={`/records/contacts/${move.personId}`}
                        className="group -mx-2 flex items-center gap-3 rounded-lg px-2 py-1.5 transition hover:bg-muted"
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
    );
}
