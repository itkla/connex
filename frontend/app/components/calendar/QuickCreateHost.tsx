'use client';

import { useEffect, useState } from 'react';
import { useTranslations } from 'next-intl';
import {
    PlusIcon,
    ClipboardDocumentCheckIcon,
    ChatBubbleLeftRightIcon,
    BriefcaseIcon,
} from '@heroicons/react/24/outline';

import {
    DropdownMenu,
    DropdownMenuTrigger,
    DropdownMenuContent,
    DropdownMenuItem,
} from '@/components/ui/dropdown-menu';
import { getUsers } from '@/app/lib/api';
import type { Contact, Deal, User } from '@/app/lib/types';
import { dayKeyOf } from '@/app/lib/calendar';
import TaskDialog from '@/app/components/activity/tasks/TaskDialog';
import ActivityDialog from '@/app/components/activity/activities/ActivityDialog';
import CalendarNewDealContainer from './CalendarNewDealContainer';

type CreateKind = 'task' | 'activity' | 'deal' | null;

/**
 * Floating create action for the calendar. A single FAB opens a menu to create a task,
 * activity or deal, each launching the existing create dialog prefilled with the selected
 * day. Users (needed by the task dialog) are fetched lazily on first task-create.
 */
export default function QuickCreateHost({
    selectedDay,
    persons,
    deals,
    currentUserId,
}: {
    selectedDay: Date;
    persons: Contact[];
    deals: Deal[];
    currentUserId: number;
}) {
    const t = useTranslations('Calendar');
    const [openKind, setOpenKind] = useState<CreateKind>(null);
    const [users, setUsers] = useState<User[]>([]);

    useEffect(() => {
        if (openKind !== 'task' || users.length > 0) return;
        let cancelled = false;
        getUsers()
            .then((next) => {
                if (!cancelled) setUsers(next);
            })
            .catch(() => {
                if (!cancelled) setUsers([]);
            });
        return () => {
            cancelled = true;
        };
    }, [openKind, users.length]);

    const dueDate = dayKeyOf(selectedDay);
    const timestamp = `${dueDate}T09:00`;

    return (
        <>
            <DropdownMenu>
                <DropdownMenuTrigger asChild>
                    <button
                        type="button"
                        aria-label={t('quickCreate')}
                        className="fixed right-6 bottom-[calc(env(safe-area-inset-bottom)+1.5rem)] z-30 grid size-14 place-items-center rounded-full bg-brand text-brand-foreground shadow-lg outline-none transition active:scale-95 hover:bg-brand-hover focus-visible:ring-2 focus-visible:ring-brand/50 focus-visible:ring-offset-2 focus-visible:ring-offset-background motion-reduce:transition-none"
                    >
                        <PlusIcon className="size-6" />
                    </button>
                </DropdownMenuTrigger>
                <DropdownMenuContent align="end" side="top" sideOffset={12}>
                    <DropdownMenuItem onSelect={() => setOpenKind('task')}>
                        <ClipboardDocumentCheckIcon className="size-4" />
                        {t('newTask')}
                    </DropdownMenuItem>
                    <DropdownMenuItem onSelect={() => setOpenKind('activity')}>
                        <ChatBubbleLeftRightIcon className="size-4" />
                        {t('newActivity')}
                    </DropdownMenuItem>
                    <DropdownMenuItem onSelect={() => setOpenKind('deal')}>
                        <BriefcaseIcon className="size-4" />
                        {t('newDeal')}
                    </DropdownMenuItem>
                </DropdownMenuContent>
            </DropdownMenu>

            <TaskDialog
                open={openKind === 'task'}
                onOpenChange={(open) => setOpenKind(open ? 'task' : null)}
                persons={persons}
                deals={deals}
                users={users}
                currentUserId={currentUserId}
                defaultDueDate={dueDate}
            />
            <ActivityDialog
                open={openKind === 'activity'}
                onOpenChange={(open) => setOpenKind(open ? 'activity' : null)}
                persons={persons}
                deals={deals}
                currentUserId={currentUserId}
                defaultTimestamp={timestamp}
            />
            <CalendarNewDealContainer
                open={openKind === 'deal'}
                onOpenChange={(open) => setOpenKind(open ? 'deal' : null)}
                defaultExpectedCloseDate={dueDate}
            />
        </>
    );
}
