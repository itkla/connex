'use client';

import { useCallback, useMemo } from 'react';
import { useLocale, useTranslations } from 'next-intl';
import type { UniqueIdentifier } from '@dnd-kit/core';
import { UserIcon, BriefcaseIcon } from '@heroicons/react/24/outline';
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';
import { cn } from '@/lib/utils';
import KanbanBoard, { type KanbanColumnDef } from '@/app/components/kanban/KanbanBoard';
import { kanbanAccessibility } from '@/app/components/kanban/kanbanAccessibility';
import NoteContent from '@/app/components/activity/notes/NoteContent';
import { DUE_CHIP, formatDue } from '@/app/components/activity/tasks/taskDue';
import { moveTask } from '@/app/lib/api';
import { noteContentToPlainText } from '@/app/lib/references';
import { useApiErrorToast } from '@/app/hooks/useApiErrorToast';
import type { Contact, Deal, Task, TaskStatus, User } from '@/app/lib/types';

interface TasksKanbanProps {
    tasks: Task[];
    personById: Map<number, Contact>;
    dealById: Map<number, Deal>;
    userById: Map<number, User>;
    onMoved: () => void;
    onOpen: (task: Task) => void;
    reduce: boolean;
}

const STATUS_ACCENT: Record<TaskStatus, string> = {
    todo: 'var(--chart-open)',
    in_progress: 'var(--color-brand)',
    done: 'var(--chart-won)',
};

function taskId(task: Task): number {
    return task.id;
}

function taskColumnId(task: Task): string {
    return task.status;
}

function taskPosition(task: Task): number {
    return task.position;
}

export default function TasksKanban({
    tasks,
    personById,
    dealById,
    userById,
    onMoved,
    onOpen,
    reduce,
}: TasksKanbanProps) {
    const t = useTranslations('ActivityTasks');
    const showApiError = useApiErrorToast('ActivityTasks');
    const locale = useLocale();

    const columns: KanbanColumnDef[] = useMemo(
        () => [
            { id: 'todo', label: t('statusTodo'), accent: STATUS_ACCENT.todo },
            { id: 'in_progress', label: t('statusInProgress'), accent: STATUS_ACCENT.in_progress },
            { id: 'done', label: t('statusDone'), accent: STATUS_ACCENT.done },
        ],
        [t],
    );

    const tasksById = useMemo(() => new Map(tasks.map((task) => [task.id, task])), [tasks]);

    const renderCard = useCallback(
        (task: Task) => {
            const person = task.personId != null ? personById.get(task.personId) : undefined;
            const deal = task.dealId != null ? dealById.get(task.dealId) : undefined;
            const assignee = userById.get(task.assignedToId);
            const due = task.status === 'done' ? null : formatDue(task.dueDate, t, locale);
            return (
                <div
                    onClick={() => onOpen(task)}
                    className={cn(
                        'group flex cursor-pointer flex-col gap-2.5 rounded-xl bg-card p-3.5 ring-1 ring-border transition duration-200 hover:-translate-y-0.5 hover:shadow-md hover:ring-border active:scale-[0.98] active:shadow-sm',
                        task.status === 'done' && 'opacity-70',
                    )}
                >
                    <p className={cn('text-sm font-medium leading-snug text-foreground line-clamp-3', task.status === 'done' && 'text-muted-foreground line-through')}>
                        <NoteContent content={task.description} references={task.references} />
                    </p>
                    {(person || deal) && (
                        <div className="flex flex-wrap items-center gap-1.5">
                            {deal && (
                                <span className="inline-flex max-w-full items-center gap-1 rounded-full bg-muted px-2 py-0.5 text-xs font-medium text-muted-foreground ring-1 ring-inset ring-border">
                                    <BriefcaseIcon className="size-3 shrink-0" />
                                    <span className="truncate">{deal.name}</span>
                                </span>
                            )}
                            {person && (
                                <span className="inline-flex max-w-full items-center gap-1 rounded-full bg-brand-light/50 px-2 py-0.5 text-xs font-medium text-brand-dark ring-1 ring-inset ring-brand-dark/10">
                                    <UserIcon className="size-3 shrink-0" />
                                    <span className="truncate">{person.name}</span>
                                </span>
                            )}
                        </div>
                    )}
                    {(due || assignee) && (
                        <div className={cn('flex items-center gap-2', due ? 'justify-between' : 'justify-end')}>
                            {due && (
                                <span className={cn('inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium tabular-nums ring-1 ring-inset', DUE_CHIP[due.tone])}>
                                    {due.label}
                                </span>
                            )}
                            {assignee && (
                                <Avatar size="sm" className="shrink-0 ring-1 ring-border">
                                    {assignee.profilePictureUrl ? (
                                        <AvatarImage src={assignee.profilePictureUrl} alt={assignee.displayName} />
                                    ) : (
                                        <AvatarFallback>
                                            <UserIcon className="size-3 text-muted-foreground" />
                                        </AvatarFallback>
                                    )}
                                </Avatar>
                            )}
                        </div>
                    )}
                </div>
            );
        },
        [personById, dealById, userById, locale, onOpen, t],
    );

    const onMove = useCallback(
        async (taskId: number, columnId: string, index: number) => {
            try {
                await moveTask(taskId, columnId as TaskStatus, index);
                onMoved();
            } catch (err) {
                showApiError(err, 'moveFailed');
                throw err;
            }
        },
        [onMoved, showApiError],
    );

    const taskName = useCallback((id: UniqueIdentifier) => noteContentToPlainText(tasksById.get(Number(id))?.description ?? ''), [tasksById]);
    const columnName = useCallback(
        (id: UniqueIdentifier) => {
            const s = String(id);
            const status = s.startsWith('col:') ? s.slice(4) : tasksById.get(Number(id))?.status;
            return columns.find((c) => c.id === status)?.label ?? '';
        },
        [tasksById, columns],
    );

    const { announcements, screenReaderInstructions } = useMemo(
        () => kanbanAccessibility(t, taskName, columnName),
        [t, taskName, columnName],
    );

    return (
        <KanbanBoard<Task>
            columns={columns}
            items={tasks}
            getId={taskId}
            getColumnId={taskColumnId}
            getPosition={taskPosition}
            renderCard={renderCard}
            onMove={onMove}
            reduce={reduce}
            emptyHint={t('emptyColumn')}
            countLabel={(count) => t('kanbanCount', { count })}
            accessibility={{ announcements, screenReaderInstructions }}
        />
    );
}
