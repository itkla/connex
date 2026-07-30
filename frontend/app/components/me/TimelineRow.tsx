'use client';

import { useEffect, useRef, useState } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import { useReducedMotion } from 'motion/react';
import { useLocale, useTranslations } from 'next-intl';
import { toastError, toastSuccess } from '@/app/lib/toast';
import { CheckIcon, EllipsisVerticalIcon, PencilIcon, TrashIcon, UserIcon } from '@heroicons/react/24/outline';

import { type Activity, type Contact, type Deal, type Note, type Task, type User } from '@/app/lib/types';
import { formatShortDate } from '@/app/lib/utils';
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';
import { Button } from '@/components/ui/button';
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuSeparator, DropdownMenuTrigger } from '@/components/ui/dropdown-menu';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import { deleteActivity, deleteNote, deleteTask } from '@/app/lib/api';
import EditTaskSheet from '@/app/components/activity/tasks/EditTaskSheet';
import EditActivitySheet from '@/app/components/activity/activities/EditActivitySheet';
import NoteDialog from '@/app/components/activity/notes/NoteDialog';
import NoteContent from '@/app/components/activity/notes/NoteContent';

export type TimelineEntry =
    | { kind: 'task'; sortAt: number; task: Task }
    | { kind: 'activity'; sortAt: number; activity: Activity }
    | { kind: 'note'; sortAt: number; note: Note };

const CHIP_CLASS: Record<TimelineEntry['kind'], string> = {
    task: 'bg-brand-light text-brand-dark',
    activity: 'bg-neutral-900 text-white dark:bg-neutral-100 dark:text-neutral-900',
    note: 'bg-neutral-200 text-neutral-700 dark:bg-neutral-800 dark:text-neutral-200',
};

const CHIP_LABEL_KEY: Record<TimelineEntry['kind'], 'chipTask' | 'chipActivity' | 'chipNote'> = {
    task: 'chipTask',
    activity: 'chipActivity',
    note: 'chipNote',
};

function entryDate(entry: TimelineEntry, locale: string): string {
    return entry.sortAt
        ? formatShortDate(new Date(entry.sortAt).toISOString(), locale)
        : '';
}

export default function TimelineRow({
    entry,
    author,
    persons,
    deals,
    currentUserId,
    companyId,
    originWorkspaceId,
}: {
    entry: TimelineEntry;
    author?: User;
    persons: Contact[];
    deals: Deal[];
    currentUserId?: number;
    companyId: number | null;
    originWorkspaceId: number | null;
}) {
    const t = useTranslations('MeTimeline');
    const locale = useLocale();
    const router = useRouter();
    const [editOpen, setEditOpen] = useState(false);
    const rowRef = useRef<HTMLLIElement>(null);
    const searchParams = useSearchParams();
    const reduceMotion = useReducedMotion();
    const isHighlighted = entry.kind === 'note' && searchParams.get('note') === String(entry.note.id);

    useEffect(() => {
        if (isHighlighted) {
            rowRef.current?.scrollIntoView({ behavior: reduceMotion ? 'auto' : 'smooth', block: 'center' });
        }
    }, [isHighlighted, reduceMotion]);

    const handleDelete = async () => {
        try {
            if (entry.kind === 'task') {
                await deleteTask(entry.task.id);
                toastSuccess(t('taskDeleted'));
            } else if (entry.kind === 'activity') {
                await deleteActivity(entry.activity.id);
                toastSuccess(t('activityDeleted'));
            } else {
                await deleteNote(entry.note.id);
                toastSuccess(t('noteDeleted'));
            }
            router.refresh();
        } catch (err) {
            toastError(err instanceof Error ? err.message : t('deleteFailed'));
        }
    };

    const date = entryDate(entry, locale);
    const chipLabel = t(CHIP_LABEL_KEY[entry.kind]);

    let title: React.ReactNode;
    let subtitle: React.ReactNode = null;

    if (entry.kind === 'task') {
        const { task } = entry;
        title = (
            <p className={`text-sm ${task.completed ? 'text-muted-foreground line-through' : 'text-foreground'}`}>
                <NoteContent content={task.description} references={task.references} />
            </p>
        );
        if (task.completed) {
            subtitle = (
                <span className="flex items-center gap-2">
                    <CheckIcon className="h-4 w-4 text-muted-foreground" />
                    <p className="text-xs text-muted-foreground">{t('completed')}</p>
                </span>
            );
        } else if (task.dueDate) {
            subtitle = (
                <p className="mt-0.5 text-xs text-muted-foreground">
                    {t('due', { date: formatShortDate(task.dueDate, locale) })}
                </p>
            );
        }
    } else if (entry.kind === 'activity') {
        const { activity } = entry;
        title = <p className="text-sm text-foreground">{activity.subject}</p>;
        subtitle = (
            <div className="mt-0.5 flex min-w-0 items-center gap-2">
                <span className="text-xs font-medium tracking-wide text-muted-foreground uppercase">
                    {activity.type}
                </span>
                {activity.notes ? (
                    <span className="truncate text-xs text-muted-foreground">
                        · <NoteContent content={activity.notes} references={activity.references} />
                    </span>
                ) : null}
            </div>
        );
    } else {
        title = (
            <p className="line-clamp-2 text-sm text-foreground">
                <NoteContent content={entry.note.content} references={entry.note.references} />
            </p>
        );
    }

    return (
        <li
            ref={rowRef}
            className={`flex scroll-mt-24 items-center gap-4 rounded-lg px-6 py-4 transition-colors duration-700 ${isHighlighted ? 'bg-brand-light/40' : ''}`}
        >
            <Tooltip>
                <TooltipTrigger asChild>
                    <Avatar size="default">
                        <AvatarImage src={author?.profilePictureUrl} />
                        <AvatarFallback>
                            <UserIcon className="size-3 text-muted-foreground" />
                        </AvatarFallback>
                    </Avatar>
                </TooltipTrigger>
                <TooltipContent>
                    {author?.displayName || author?.username || ''}
                </TooltipContent>
            </Tooltip>
            <span className={`inline-flex items-center rounded-md px-2 py-1 text-xs font-medium inset-ring ${CHIP_CLASS[entry.kind]}`}>
                <span className="mr-1">●</span>{chipLabel}
            </span>
            <div className="min-w-0 flex-1">
                <div className="flex items-start justify-between gap-3">
                    {title}
                    {date ? (
                        <time className="shrink-0 text-xs text-muted-foreground">
                            {date}
                        </time>
                    ) : null}
                </div>
                {subtitle}
            </div>
            <DropdownMenu>
                <DropdownMenuTrigger asChild>
                    <Button variant="ghost" size="icon" className="rounded-full" aria-label={t('actionsAria')}>
                        <EllipsisVerticalIcon className="size-3 text-muted-foreground" />
                    </Button>
                </DropdownMenuTrigger>
                <DropdownMenuContent align="end">
                    <DropdownMenuItem onClick={() => setEditOpen(true)}>
                        <PencilIcon className="size-4 text-muted-foreground" />
                        {t('edit')}
                    </DropdownMenuItem>
                    <DropdownMenuSeparator />
                    <DropdownMenuItem variant="destructive" onClick={handleDelete}>
                        <TrashIcon className="size-4" />
                        {t('delete')}
                    </DropdownMenuItem>
                </DropdownMenuContent>
            </DropdownMenu>

            {entry.kind === 'task' && (
                <EditTaskSheet
                    key={entry.task.id}
                    task={entry.task}
                    open={editOpen}
                    onOpenChange={setEditOpen}
                    companyId={companyId}
                    deals={deals}
                />
            )}
            {entry.kind === 'activity' && (
                <EditActivitySheet
                    key={entry.activity.id}
                    activity={entry.activity}
                    open={editOpen}
                    onOpenChange={setEditOpen}
                    persons={persons}
                    deals={deals}
                    originWorkspaceId={originWorkspaceId}
                />
            )}
            {entry.kind === 'note' && currentUserId != null && (
                <NoteDialog
                    key={entry.note.id}
                    note={entry.note}
                    open={editOpen}
                    onOpenChange={setEditOpen}
                    persons={persons}
                    deals={deals}
                    currentUserId={currentUserId}
                />
            )}
        </li>
    );
}
