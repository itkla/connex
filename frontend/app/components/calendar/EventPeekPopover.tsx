'use client';

import { useMemo } from 'react';
import Link from 'next/link';
import { useTranslations } from 'next-intl';
import { ArrowUpRightIcon, BriefcaseIcon, CheckCircleIcon, UserIcon } from '@heroicons/react/24/outline';

import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import {
    Popover,
    PopoverContent,
    PopoverDescription,
    PopoverTitle,
} from '@/components/ui/popover';
import type { Contact, Deal } from '@/app/lib/types';
import type { CalendarEvent } from '@/app/lib/calendar';
import { KIND_ICON, KIND_LABEL_KEY } from './constants';
import { linkedIds } from './eventLinks';

/**
 * The D5 inspect archetype for a calendar event: an anchored popover on the clicked entry showing
 * when it is, who and what it touches, and one action — with "Details" expanding the same event into
 * the right drawer. On a coarse pointer the shell skips this surface and opens the drawer directly,
 * per the mobile bottom-sheet rule.
 */
export default function EventPeekPopover({
    event,
    anchor,
    open,
    onOpenChange,
    onExpand,
    onComplete,
    locale,
    personById,
    dealById,
    currentUserId,
}: {
    event: CalendarEvent | null;
    anchor: HTMLElement | null;
    open: boolean;
    onOpenChange: (open: boolean) => void;
    onExpand: () => void;
    onComplete?: (event: CalendarEvent) => void;
    locale: string;
    personById: Map<number, Contact>;
    dealById: Map<number, Deal>;
    currentUserId: number;
}) {
    const t = useTranslations('Calendar');

    const whenLabel = useMemo(() => {
        if (!event) return '';
        return new Intl.DateTimeFormat(
            locale,
            event.allDay ? { dateStyle: 'full' } : { dateStyle: 'full', timeStyle: 'short' },
        ).format(event.startMs);
    }, [event, locale]);

    if (!event) return null;

    const Icon = KIND_ICON[event.kind];
    const { personId, dealId } = linkedIds(event);
    const person = personId != null ? personById.get(personId) : undefined;
    const deal = dealId != null ? dealById.get(dealId) : undefined;
    const canComplete = event.kind === 'task'
        && onComplete !== undefined
        && event.raw.assignedToId === currentUserId;

    return (
        <Popover open={open} onOpenChange={onOpenChange}>
            <PopoverContent anchor={anchor} align="start" className="flex flex-col gap-3 p-3">
                <div className="flex flex-col gap-1.5">
                    <Badge variant="secondary" className="w-fit gap-1.5">
                        <Icon className="size-3.5" aria-hidden />
                        {t(KIND_LABEL_KEY[event.kind])}
                    </Badge>
                    <PopoverTitle className="text-sm leading-snug font-medium text-foreground">
                        {event.title}
                    </PopoverTitle>
                    <PopoverDescription className="text-xs tabular-nums text-muted-foreground">
                        {whenLabel}
                    </PopoverDescription>
                </div>

                {person || deal ? (
                    <div className="flex flex-col gap-1.5">
                        {person ? (
                            <div className="flex items-center gap-2 text-sm text-foreground">
                                <UserIcon className="size-4 shrink-0 text-muted-foreground" />
                                <span className="truncate">{person.name}</span>
                            </div>
                        ) : null}
                        {deal ? (
                            <div className="flex items-center gap-2 text-sm text-foreground">
                                <BriefcaseIcon className="size-4 shrink-0 text-muted-foreground" />
                                <span className="truncate">{deal.name}</span>
                            </div>
                        ) : null}
                    </div>
                ) : null}

                <div className="flex items-center gap-2">
                    {canComplete ? (
                        <Button
                            variant="brand"
                            size="toolbar"
                            className="flex-1"
                            onClick={() => onComplete?.(event)}
                        >
                            <CheckCircleIcon className="size-4" />
                            {t('markDone')}
                        </Button>
                    ) : (
                        <Button asChild variant="brand" size="toolbar" className="flex-1">
                            <Link href={event.href} onClick={() => onOpenChange(false)}>
                                {t('openRecord')}
                                <ArrowUpRightIcon className="size-4" />
                            </Link>
                        </Button>
                    )}
                    <Button variant="outline" size="toolbar" onClick={onExpand}>
                        {t('peekDetails')}
                    </Button>
                </div>
            </PopoverContent>
        </Popover>
    );
}
