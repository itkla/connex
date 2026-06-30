'use client';

import { useCallback, useMemo } from 'react';
import { useLocale, useTranslations } from 'next-intl';
import type { Announcements, ScreenReaderInstructions, UniqueIdentifier } from '@dnd-kit/core';
import { UserIcon, BriefcaseIcon } from '@heroicons/react/24/outline';
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';
import { cn } from '@/lib/utils';
import KanbanBoard, { type KanbanColumnDef } from '@/app/components/kanban/KanbanBoard';
import { moveTask } from '@/app/lib/api';
import { toastError } from '@/app/lib/toast';
import { parseMysqlDateTime } from '@/app/lib/utils';
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

type DueTone = 'overdue' | 'today' | 'soon' | 'later';

const DUE_CHIP: Record<DueTone, string> = {
    overdue: 'bg-red-50 text-red-600 ring-red-600/10 dark:bg-red-950/40 dark:text-red-400 dark:ring-red-400/20',
    today: 'bg-brand-light/70 text-brand-dark ring-brand-dark/15',
    soon: 'bg-muted text-muted-foreground ring-border',
    later: 'bg-muted text-muted-foreground ring-border',
};

const STATUS_ACCENT: Record<TaskStatus, string> = {
    todo: 'var(--chart-open)',
    in_progress: 'var(--color-brand)',
    done: 'var(--chart-won)',
};

function formatDue(dueDate: string | undefined, locale: string): { label: string; tone: DueTone } | null {
    if (!dueDate) return null;
    const ts = parseMysqlDateTime(dueDate);
    if (Number.isNaN(ts)) return null;
    const date = new Date(ts);
    const today = new Date();
    today.setHours(0, 0, 0, 0);
    const diffDays = Math.floor((date.getTime() - today.getTime()) / (24 * 60 * 60 * 1000));
    const short = new Intl.DateTimeFormat(locale, { month: 'short', day: 'numeric' }).format(date);
    if (diffDays < 0) return { label: short, tone: 'overdue' };
    if (diffDays === 0) return { label: short, tone: 'today' };
    return { label: short, tone: diffDays <= 6 ? 'soon' : 'later' };
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
            const due = task.status === 'done' ? null : formatDue(task.dueDate, locale);
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
        [personById, dealById, userById, locale, onOpen],
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

    const announcements: Announcements = useMemo(
        () => ({
            onDragStart: ({ active }) => t('a11yLifted', { name: taskName(active.id) }),
            onDragOver: ({ active, over }) =>
                over ? t('a11yOver', { name: taskName(active.id), column: columnName(over.id) }) : undefined,
            onDragEnd: ({ active, over }) =>
                over
                    ? t('a11yDropped', { name: taskName(active.id), column: columnName(over.id) })
                    : t('a11yCancelled', { name: taskName(active.id) }),
            onDragCancel: ({ active }) => t('a11yCancelled', { name: taskName(active.id) }),
        }),
        [t, taskName, columnName],
    );
    const screenReaderInstructions: ScreenReaderInstructions = useMemo(
        () => ({ draggable: t('a11yInstructions') }),
        [t],
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
