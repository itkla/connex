'use client';

import { startTransition, useOptimistic, useState } from "react";
import { useRouter } from "next/navigation";
import { useLocale, useTranslations } from "next-intl";
import { AnimatePresence, motion, useReducedMotion } from "motion/react";
import { EllipsisVerticalIcon, PencilIcon, TrashIcon, InformationCircleIcon } from "@heroicons/react/24/outline";

import { Deal, Task, UpdateTaskPayload } from "@/app/lib/types";
import { DropdownMenu, DropdownMenuItem, DropdownMenuContent, DropdownMenuTrigger, DropdownMenuSeparator } from "@/components/ui/dropdown-menu";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { formatDate } from "@/app/lib/utils";
import { easeOut } from "@/app/lib/motion";
import { toastError, toastSuccess } from "@/app/lib/toast";
import { deleteTask, updateTask } from "@/app/lib/api";
import EditTaskSheet from "@/app/components/activity/tasks/EditTaskSheet";
import NoteContent from "@/app/components/activity/notes/NoteContent";

/**
 * The open-task list on the deal detail page. Completing a task is a single click on its checkbox:
 * the row leaves the list immediately and the optimistic state holds until the refreshed server data
 * lands, so the round trip never blocks the feedback. Editing and deleting stay in the row menu.
 */
export default function DealTaskList({ dealId, companyId, tasks, deals }: { dealId: number, companyId?: number | null, tasks: Task[], deals: Deal[] }) {
    const t = useTranslations('DealsTaskList');
    const locale = useLocale();
    const router = useRouter();
    const reduce = useReducedMotion() ?? false;
    const [editTaskOpen, setEditTaskOpen] = useState(false);
    const [selectedTask, setSelectedTask] = useState<Task | null>(null);

    const openTasks = tasks.filter((task) => !task.completed);
    const [visibleTasks, dismissTask] = useOptimistic<Task[], number>(
        openTasks,
        (state, taskId) => state.filter((task) => task.id !== taskId),
    );

    const deleteThisTask = (task: Task) => {
        startTransition(async () => {
            dismissTask(task.id);
            try {
                await deleteTask(task.id);
                toastSuccess(t('taskDeleted'));
                router.refresh();
            } catch (err) {
                toastError(err instanceof Error ? err.message : t('failedToDeleteTask'));
            }
        });
    };

    const markTaskAsComplete = (task: Task) => {
        const payload: UpdateTaskPayload = {
            description: task.description,
            dueDate: task.dueDate,
            assignedToId: task.assignedToId,
            personId: task.personId ?? undefined,
            dealId,
            completed: true,
        };
        startTransition(async () => {
            dismissTask(task.id);
            try {
                await updateTask(task.id, payload);
                toastSuccess(t('taskMarkedAsComplete'));
                router.refresh();
            } catch (err) {
                toastError(err instanceof Error ? err.message : t('failedToMarkTaskAsComplete'));
            }
        });
    };

    const editTask = (task: Task) => {
        setSelectedTask(task);
        setEditTaskOpen(true);
    };

    return (
        <>
            {visibleTasks.length > 0 ? (
                <>
                    <div className="mb-3 mt-6 flex h-8 items-center">
                        <h2 className="px-6 text-xs font-medium uppercase tracking-[0.12em] text-muted-foreground">
                            {t('openTasksHeading', { count: visibleTasks.length })}
                        </h2>
                        <Tooltip>
                            <TooltipTrigger asChild>
                                <InformationCircleIcon className="size-3 text-muted-foreground" />
                            </TooltipTrigger>
                            <TooltipContent>
                                <div className="flex flex-col gap-2">
                                    <h2 className="text-sm font-medium">{t('openTasks')}</h2>
                                    <p className="text-xs text-muted-foreground">
                                        {t('openTasksTooltip')}
                                    </p>
                                </div>
                            </TooltipContent>
                        </Tooltip>
                    </div>
                    <div className="overflow-hidden rounded-2xl bg-muted ring-1 ring-border">
                        <ul className="divide-y divide-border">
                            <AnimatePresence initial={false}>
                                {visibleTasks.map((task) => (
                                    <motion.li
                                        key={task.id}
                                        layout={!reduce}
                                        initial={false}
                                        exit={reduce ? { opacity: 0 } : { opacity: 0, x: 8 }}
                                        transition={{ duration: 0.2, ease: easeOut }}
                                        className="flex items-center gap-3 px-6 py-3"
                                    >
                                        <Checkbox
                                            checked={false}
                                            onCheckedChange={(value) => {
                                                if (value === true) markTaskAsComplete(task);
                                            }}
                                            aria-label={t('ariaCompleteTask')}
                                            className="size-[18px] shrink-0 rounded-full border-border transition data-[state=checked]:border-brand data-[state=checked]:bg-brand data-[state=checked]:text-brand-foreground"
                                        />
                                        <div className="min-w-0 flex-1">
                                            <p className="text-sm text-foreground"><NoteContent content={task.description} references={task.references} /></p>
                                            {task.dueDate ? (
                                                <p className="mt-0.5 text-xs text-muted-foreground">
                                                    {t('due', { date: formatDate(task.dueDate, locale) })}
                                                </p>
                                            ) : null}
                                        </div>
                                        <DropdownMenu>
                                            <DropdownMenuTrigger asChild>
                                                <Button
                                                    variant="ghost"
                                                    size="icon"
                                                    className="p-1 h-8 w-8 shrink-0 rounded-full shadow-none border-none bg-transparent hover:bg-muted focus:ring-0 focus:ring-offset-0"
                                                >
                                                    <EllipsisVerticalIcon className="size-4" />
                                                </Button>
                                            </DropdownMenuTrigger>

                                            <DropdownMenuContent align="end">
                                                <DropdownMenuItem onClick={() => editTask(task)}>
                                                    <PencilIcon className="size-4" />
                                                    {t('edit')}
                                                </DropdownMenuItem>
                                                <DropdownMenuSeparator />
                                                <DropdownMenuItem variant="destructive" onClick={() => deleteThisTask(task)}>
                                                    <TrashIcon className="size-4" />
                                                    {t('delete')}
                                                </DropdownMenuItem>
                                            </DropdownMenuContent>
                                        </DropdownMenu>
                                    </motion.li>
                                ))}
                            </AnimatePresence>
                        </ul>
                    </div>
                </>
            ) : null}
            {selectedTask && (
                <EditTaskSheet
                    key={selectedTask.id}
                    open={editTaskOpen}
                    onOpenChange={(next) => {
                        setEditTaskOpen(next);
                        if (!next) setSelectedTask(null);
                    }}
                    task={selectedTask}
                    companyId={companyId}
                    deals={deals}
                />
            )}

        </>
    );
}
