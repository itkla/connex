'use client';

import { useCallback, useMemo } from 'react';
import { useLocale, useTranslations } from 'next-intl';
import type { UniqueIdentifier } from '@dnd-kit/core';
import { UserIcon, BriefcaseIcon } from '@heroicons/react/24/outline';
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';
import { cn } from '@/lib/utils';
import KanbanBoard, { type KanbanColumnDef } from '@/app/components/kanban/KanbanBoard';
import { kanbanAccessibility } from '@/app/components/kanban/kanbanAccessibility';
import { DUE_CHIP, formatDue } from '@/app/components/activity/tasks/taskDue';
import { moveTask } from '@/app/lib/api';
import { toastError } from '@/app/lib/toast';
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
                    className="group cursor-pointer rounded-xl bg-card p-3 ring-1 ring-border transition duration-200 hover:bg-muted hover:shadow-md"
                >
                    <p className={cn('text-sm font-medium text-foreground', task.status === 'done' && 'text-muted-foreground line-through')}>
                        {task.description}
                    </p>
                    {(person || deal) && (
                        <div className="mt-2 flex flex-wrap items-center gap-1.5">
                            {deal && (
                                <span className="inline-flex max-w-full items-center gap-1 rounded-full bg-card px-2 py-0.5 text-xs font-medium text-foreground ring-1 ring-inset ring-border">
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
                    <div className="mt-2 flex items-center justify-between">
                        {due ? (
                            <span className={cn('inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium tabular-nums ring-1 ring-inset', DUE_CHIP[due.tone])}>
                                {due.label}
                            </span>
                        ) : (
                            <span />
                        )}
                        {assignee && (
                            <Avatar size="sm" className="ring-1 ring-border">
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
                toastError(err instanceof Error ? err.message : t('moveFailed'));
                throw err;
            }
        },
        [onMoved, t],
    );

    const taskName = useCallback((id: UniqueIdentifier) => tasksById.get(Number(id))?.description ?? '', [tasksById]);
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
            getId={(task) => task.id}
            getColumnId={(task) => task.status}
            getPosition={(task) => task.position}
            renderCard={renderCard}
            onMove={onMove}
            reduce={reduce}
            emptyHint={t('emptyColumn')}
            countLabel={(count) => t('kanbanCount', { count })}
            accessibility={{ announcements, screenReaderInstructions }}
        />
    );
}
