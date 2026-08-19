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
import { Button } from '@/components/ui/button';
import { getUsers } from '@/app/lib/api';
import type { Contact, Deal, User } from '@/app/lib/types';
import { dayKeyOf } from '@/app/lib/calendar';
import TaskDialog from '@/app/components/activity/tasks/TaskDialog';
import ActivityDialog from '@/app/components/activity/activities/ActivityDialog';
import CalendarNewDealContainer from './CalendarNewDealContainer';

type CreateKind = 'task' | 'activity' | 'deal' | null;

/**
 * Header create action for the calendar. Its menu opens the existing task, activity, and
 * deal dialogs prefilled with the selected day. Task users load on first task creation.
 */
export default function QuickCreateHost({
    selectedDay,
    persons,
    deals,
    currentUserId,
    menuOpen,
    onMenuOpenChange,
}: {
    selectedDay: Date;
    persons: Contact[];
    deals: Deal[];
    currentUserId: number;
    menuOpen?: boolean;
    onMenuOpenChange?: (open: boolean) => void;
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
            <DropdownMenu open={menuOpen} onOpenChange={onMenuOpenChange}>
                <DropdownMenuTrigger asChild>
                    <Button type="button" variant="brand" size="toolbar" menu className="h-11 sm:h-8">
                        <PlusIcon className="size-4" />
                        {t('quickCreate')}
                    </Button>
                </DropdownMenuTrigger>
                <DropdownMenuContent align="end" sideOffset={8}>
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
