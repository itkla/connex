'use client';

import { useState } from 'react';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';

import { Checkbox } from '@/components/ui/checkbox';
import { updateTask } from '@/app/lib/api';
import { toastError, toastSuccess } from '@/app/lib/toast';
import { formatShortDate, timeOf } from '@/app/lib/utils';
import { type Task } from '@/app/lib/types';
import { cn } from '@/lib/utils';

export default function UpcomingTasks({ tasks, locale }: { tasks: Task[]; locale: string }) {
    const router = useRouter();
    const t = useTranslations('DashboardTaskSummary');
    const [now] = useState(() => new Date().getTime());
    const [doneIds, setDoneIds] = useState<Set<number>>(new Set());

    async function complete(task: Task) {
        setDoneIds((prev) => new Set(prev).add(task.id));
        try {
            await updateTask(task.id, { completed: true });
            toastSuccess(t('taskCompleted'), {
                action: { label: t('undo'), onClick: () => undo(task) },
            });
            router.refresh();
        } catch {
            setDoneIds((prev) => {
                const next = new Set(prev);
                next.delete(task.id);
                return next;
            });
            toastError(t('taskCompleteFailed'));
        }
    }

    async function undo(task: Task) {
        setDoneIds((prev) => {
            const next = new Set(prev);
            next.delete(task.id);
            return next;
        });
        try {
            await updateTask(task.id, { completed: false });
            router.refresh();
        } catch {
            toastError(t('taskCompleteFailed'));
        }
    }

    const visible = tasks.filter((task) => !doneIds.has(task.id));

    if (visible.length === 0) {
        return (
            <p className="mt-6 border-t border-neutral-200 pt-6 text-sm text-neutral-500">
                {t('allCaughtUp')}
            </p>
        );
    }

    return (
        <ul className="mt-6 divide-y divide-neutral-200 border-t border-neutral-200">
            {visible.map((task) => {
                const due = timeOf(task.dueDate);
                const isOverdue = due > 0 && due < now;
                return (
                    <li key={task.id} className="flex items-center gap-3 py-3">
                        <Checkbox
                            checked={false}
                            onCheckedChange={() => complete(task)}
                            aria-label={t('completeAria', { description: task.description })}
                            className="size-4 shrink-0 cursor-pointer"
                        />
                        <span className="line-clamp-1 flex-1 text-sm text-neutral-800">
                            {task.description}
                        </span>
                        {task.dueDate ? (
                            <span
                                className={cn(
                                    'shrink-0 text-xs tabular-nums',
                                    isOverdue ? 'font-medium text-red-600' : 'text-neutral-500',
                                )}
                            >
                                {formatShortDate(task.dueDate, locale)}
                            </span>
                        ) : (
                            <span className="shrink-0 text-xs text-neutral-400">{t('noDate')}</span>
                        )}
                    </li>
                );
            })}
        </ul>
    );
}