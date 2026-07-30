'use client';

import { Deal, Task, UpdateTaskPayload } from "@/app/lib/types";
import { DropdownMenu, DropdownMenuItem, DropdownMenuContent, DropdownMenuTrigger, DropdownMenuSeparator } from "@/components/ui/dropdown-menu";
import { Button } from "@/components/ui/button";
import { EllipsisVerticalIcon, PencilIcon, CheckCircleIcon, TrashIcon, InformationCircleIcon } from "@heroicons/react/24/outline";
import { formatDate } from "@/app/lib/utils";
import { toastError, toastSuccess } from "@/app/lib/toast";
import { deleteTask, updateTask } from "@/app/lib/api";
import { useRouter } from "next/navigation";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { useState } from "react";
import EditTaskSheet from "@/app/components/activity/tasks/EditTaskSheet";
import NoteContent from "@/app/components/activity/notes/NoteContent";
import { useLocale, useTranslations } from "next-intl";

export default function DealTaskList({ dealId, companyId, tasks, deals }: { dealId: number, companyId?: number | null, tasks: Task[], deals: Deal[] }) {
    const t = useTranslations('DealsTaskList');
    const locale = useLocale();
    const openTasks = tasks.filter((task) => !task.completed);
    const [editTaskOpen, setEditTaskOpen] = useState(false);
    const [selectedTask, setSelectedTask] = useState<Task | null>(null);
    const router = useRouter();
    const deleteThisTask = async (taskId: number) => {
        try {
            await deleteTask(taskId);
            toastSuccess(t('taskDeleted'));
            router.refresh();
        } catch (err) {
            toastError(err instanceof Error ? err.message : t('failedToDeleteTask'));
        }
    };

    const markTaskAsComplete = async (taskId: number) => {

        const payload: UpdateTaskPayload = {
            // load the task details in, then overwrite the fields that are changed
            description: tasks.find((t) => t.id === taskId)?.description,
            dueDate: tasks.find((t) => t.id === taskId)?.dueDate,
            assignedToId: tasks.find((t) => t.id === taskId)?.assignedToId,
            personId: tasks.find((t) => t.id === taskId)?.personId ?? undefined,
            dealId: dealId,
            completed: true,
        };
        try {
            await updateTask(taskId, payload);
            toastSuccess(t('taskMarkedAsComplete'));
            router.refresh();
        } catch (err) {
            toastError(err instanceof Error ? err.message : t('failedToMarkTaskAsComplete'));
        }
    };

    const editTask = (taskId: number) => {
        const found = tasks.find((t) => t.id === taskId);
        if (!found) return;
        setSelectedTask(found);
        setEditTaskOpen(true);
    };

    return (
        <>
            {openTasks.length > 0 ? (
                <>
                    <div className="mb-3 mt-6 flex h-8 items-center">
                        <h2 className="px-6 text-xs font-medium uppercase tracking-[0.12em] text-muted-foreground">
                            {t('openTasksHeading', { count: openTasks.length })}
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
                            {openTasks.map((task) => (
                                <li key={task.id} className="px-6 py-3 flex items-center justify-between">
                                    <div className="">
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
                                                className="p-1 h-8 w-8 rounded-full shadow-none border-none bg-transparent hover:bg-muted focus:ring-0 focus:ring-offset-0"
                                            >
                                                <EllipsisVerticalIcon className="size-4" />
                                            </Button>
                                        </DropdownMenuTrigger>

                                        <DropdownMenuContent align="end">
                                            <DropdownMenuItem onClick={() => {
                                                editTask(task.id);
                                            }}>
                                                <PencilIcon className="size-4" />
                                                {t('edit')}
                                            </DropdownMenuItem>
                                            <DropdownMenuItem onClick={() => {
                                                markTaskAsComplete(task.id);
                                            }}>
                                                <CheckCircleIcon className="size-4" />
                                                {t('markAsComplete')}
                                            </DropdownMenuItem>
                                            <DropdownMenuSeparator />
                                            <DropdownMenuItem variant="destructive" onClick={() => {
                                                deleteThisTask(task.id);
                                            }}>
                                                <TrashIcon className="size-4" />
                                                {t('delete')}
                                            </DropdownMenuItem>
                                        </DropdownMenuContent>
                                    </DropdownMenu>
                                    {/* <Button variant="outline" size="sm">
                                        <EllipsisVerticalIcon className="size-4" />
                                    </Button> */}
                                </li>
                            ))}
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