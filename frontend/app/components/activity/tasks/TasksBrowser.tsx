'use client';

import { useEffect, useMemo, useState, type ComponentType } from 'react';
import { useRouter } from 'next/navigation';
import { useLocale, useTranslations } from 'next-intl';
import { toastError } from '@/app/lib/toast';
import Link from 'next/link';
import {
    MagnifyingGlassIcon,
    UserIcon,
    BriefcaseIcon,
    InboxIcon,
    CalendarDaysIcon,
    ExclamationCircleIcon,
    UserCircleIcon,
    QueueListIcon,
    CheckCircleIcon,
} from '@heroicons/react/24/outline';
import { PlusIcon } from '@heroicons/react/24/solid';

import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';
import { Checkbox } from '@/components/ui/checkbox';
import { Button } from '@/components/ui/button';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';

import EditTaskSheet from '@/app/components/activity/tasks/EditTaskSheet';
import TaskDialog from '@/app/components/activity/tasks/TaskDialog';
import { updateTask } from '@/app/lib/api';
import { parseMysqlDateTime } from '@/app/lib/utils';
import type { Contact, Deal, Task, User } from '@/app/lib/types';

type Props = {
    tasks: Task[];
    persons: Contact[];
    deals: Deal[];
    users: User[];
    currentUserId: number;
};

type Bucket = 'overdue' | 'today' | 'upcoming' | 'noDate' | 'completed';
type Queue = 'myOpen' | 'dueToday' | 'overdue' | 'unassigned' | 'allOpen' | 'completed';
type IconType = ComponentType<{ className?: string }>;

const ACTIVE_BUCKETS: Bucket[] = ['overdue', 'today', 'upcoming', 'noDate'];
const ACTIVE_QUEUES: Queue[] = ['myOpen', 'dueToday', 'overdue', 'unassigned', 'allOpen'];
const ALL_QUEUES: Queue[] = [...ACTIVE_QUEUES, 'completed'];
const QUEUE_STORAGE_KEY = 'tasks:queue';

const QUEUE_ICON: Record<Queue, IconType> = {
    myOpen: InboxIcon,
    dueToday: CalendarDaysIcon,
    overdue: ExclamationCircleIcon,
    unassigned: UserCircleIcon,
    allOpen: QueueListIcon,
    completed: CheckCircleIcon,
};

function isQueue(value: unknown): value is Queue {
    return typeof value === 'string' && (ALL_QUEUES as string[]).includes(value);
}

function startOfToday(): number {
    const d = new Date();
    d.setHours(0, 0, 0, 0);
    return d.getTime();
}

function endOfToday(): number {
    const d = new Date();
    d.setHours(23, 59, 59, 999);
    return d.getTime();
}

function dueTimestamp(task: Task): number {
    const ts = parseMysqlDateTime(task.dueDate);
    return Number.isNaN(ts) ? Number.POSITIVE_INFINITY : ts;
}

function bucketForTask(task: Task): Bucket {
    if (task.completed) return 'completed';
    const ts = parseMysqlDateTime(task.dueDate);
    if (Number.isNaN(ts)) return 'noDate';
    if (ts < startOfToday()) return 'overdue';
    if (ts <= endOfToday()) return 'today';
    return 'upcoming';
}

function isInQueue(queue: Queue, task: Task, currentUserId: number): boolean {
    const ts = parseMysqlDateTime(task.dueDate);
    const hasDate = !Number.isNaN(ts);
    switch (queue) {
        case 'myOpen':
            return !task.completed && task.assignedToId === currentUserId;
        case 'dueToday':
            return !task.completed && hasDate && ts >= startOfToday() && ts <= endOfToday();
        case 'overdue':
            return !task.completed && hasDate && ts < startOfToday();
        case 'unassigned':
            return !task.completed && (!task.assignedToId || task.assignedToId === 0);
        case 'allOpen':
            return !task.completed;
        case 'completed':
            return !!task.completed;
    }
}

function formatDueDate(dueDate: string | undefined, t: (key: string) => string, locale: string): string {
    if (!dueDate) return '';
    const ts = parseMysqlDateTime(dueDate);
    if (Number.isNaN(ts)) return '';
    const date = new Date(ts);
    const today = new Date();
    today.setHours(0, 0, 0, 0);
    const dayMs = 1000 * 60 * 60 * 24;
    const diffDays = Math.floor((date.getTime() - today.getTime()) / dayMs);
    if (diffDays === 0) return t('dueToday');
    if (diffDays === 1) return t('dueTomorrow');
    if (diffDays === -1) return t('dueYesterday');
    return new Intl.DateTimeFormat(locale, { month: 'short', day: 'numeric' }).format(date);
}

export default function TasksBrowser({ tasks, persons, deals, users, currentUserId }: Props) {
    const router = useRouter();
    const t = useTranslations('ActivityTasks');
    const locale = useLocale();

    const personById = useMemo(() => {
        const map = new Map<number, Contact>();
        for (const p of persons) map.set(p.id, p);
        return map;
    }, [persons]);

    const dealById = useMemo(() => {
        const map = new Map<number, Deal>();
        for (const d of deals) map.set(d.id, d);
        return map;
    }, [deals]);

    const userById = useMemo(() => {
        const map = new Map<number, User>();
        for (const u of users) map.set(u.id, u);
        return map;
    }, [users]);

    const [query, setQuery] = useState('');
    const [queue, setQueue] = useState<Queue>('myOpen');
    const [queueInitialized, setQueueInitialized] = useState(false);
    const [editingTask, setEditingTask] = useState<Task | null>(null);
    const [creating, setCreating] = useState(false);
    const [pendingToggle, setPendingToggle] = useState<Set<number>>(new Set());

    useEffect(() => {
        const stored = window.localStorage.getItem(QUEUE_STORAGE_KEY);
        // eslint-disable-next-line react-hooks/set-state-in-effect
        if (isQueue(stored)) setQueue(stored);
        setQueueInitialized(true);
    }, []);

    useEffect(() => {
        if (!queueInitialized) return;
        window.localStorage.setItem(QUEUE_STORAGE_KEY, queue);
    }, [queue, queueInitialized]);

    const queueCounts = useMemo(() => {
        const counts: Record<Queue, number> = {
            myOpen: 0,
            dueToday: 0,
            overdue: 0,
            unassigned: 0,
            allOpen: 0,
            completed: 0,
        };
        for (const task of tasks) {
            for (const q of ALL_QUEUES) {
                if (isInQueue(q, task, currentUserId)) counts[q]++;
            }
        }
        return counts;
    }, [tasks, currentUserId]);

    const filtered = useMemo(() => {
        const q = query.trim().toLowerCase();
        return tasks.filter((task) => {
            if (!isInQueue(queue, task, currentUserId)) return false;
            if (!q) return true;
            const haystacks = [
                task.description,
                task.personId ? personById.get(task.personId)?.name : null,
                task.dealId ? dealById.get(task.dealId)?.name : null,
                userById.get(task.assignedToId)?.displayName,
            ];
            return haystacks.some((s) => s?.toLowerCase().includes(q));
        });
    }, [tasks, query, queue, currentUserId, personById, dealById, userById]);

    const grouped = useMemo(() => {
        const buckets: Record<Bucket, Task[]> = {
            overdue: [],
            today: [],
            upcoming: [],
            noDate: [],
            completed: [],
        };
        for (const task of filtered) {
            buckets[bucketForTask(task)].push(task);
        }
        for (const key of [...ACTIVE_BUCKETS, 'completed' as const]) {
            buckets[key].sort((a, c) => dueTimestamp(a) - dueTimestamp(c));
        }
        return buckets;
    }, [filtered]);

    const visibleBuckets = useMemo(
        () => ACTIVE_BUCKETS.filter((b) => grouped[b].length > 0),
        [grouped],
    );

    const handleToggleComplete = async (task: Task, next: boolean) => {
        if (pendingToggle.has(task.id)) return;
        setPendingToggle((prev) => new Set(prev).add(task.id));
        try {
            await updateTask(task.id, { completed: next });
            router.refresh();
        } catch (err) {
            toastError(err instanceof Error ? err.message : t('toastFailedUpdate'));
        } finally {
            setPendingToggle((prev) => {
                const n = new Set(prev);
                n.delete(task.id);
                return n;
            });
        }
    };

    const drawerCompanyId = editingTask?.personId
        ? personById.get(editingTask.personId)?.companyId ?? null
        : null;

    const isEmpty = filtered.length === 0;
    const isCompletedQueue = queue === 'completed';
    const emptyMessage = query.trim()
        ? t('emptyFiltered')
        : t(`emptyQueue_${queue}` as 'emptyQueue_myOpen');

    return (
        <div className="space-y-8">
            <div className="flex items-center justify-between">
                <h1 className="text-4xl font-extrabold">{t('title')}</h1>
                <Button
                    className="bg-brand text-white"
                    aria-label={t('newAria')}
                    onClick={() => setCreating(true)}
                >
                    <PlusIcon strokeWidth={2.5} />
                    {t('new')}
                </Button>
            </div>

            <div className="grid grid-cols-1 gap-6 md:grid-cols-[200px_minmax(0,1fr)] md:gap-10">
                <aside className="md:sticky md:top-6 md:self-start">
                    <h2 className="mb-2 px-3 text-[11px] font-semibold uppercase tracking-[0.14em] text-neutral-400">
                        {t('queuesHeader')}
                    </h2>
                    <nav className="space-y-0.5">
                        {ACTIVE_QUEUES.map((q) => (
                            <QueueButton
                                key={q}
                                Icon={QUEUE_ICON[q]}
                                label={t(`queue_${q}` as 'queue_myOpen')}
                                count={queueCounts[q]}
                                active={queue === q}
                                onClick={() => setQueue(q)}
                            />
                        ))}
                        <div className="mx-3 my-3 h-px bg-neutral-200" />
                        <QueueButton
                            Icon={QUEUE_ICON.completed}
                            label={t('queue_completed')}
                            count={queueCounts.completed}
                            active={queue === 'completed'}
                            onClick={() => setQueue('completed')}
                        />
                    </nav>
                </aside>

                <div className="min-w-0 space-y-4">
                    <div className="flex items-center">
                        <div className="relative ml-auto w-full max-w-xs">
                            <input
                                type="text"
                                placeholder={t('searchPlaceholder')}
                                value={query}
                                onChange={(e) => setQuery(e.target.value)}
                                className="w-full rounded-full bg-neutral-100 px-4 py-2 pr-10 text-sm text-black placeholder-neutral-500 outline-none ring-1 ring-black/5 transition focus:ring-2 focus:ring-brand"
                            />
                            <MagnifyingGlassIcon className="pointer-events-none absolute right-3 top-1/2 size-4 -translate-y-1/2 text-neutral-500" />
                        </div>
                    </div>

                    {isEmpty ? (
                        <div className="rounded-2xl bg-white px-6 py-20 text-center ring-1 ring-black/5">
                            <p className="text-sm text-neutral-500">{emptyMessage}</p>
                        </div>
                    ) : (
                        <div className="overflow-hidden rounded-2xl bg-white ring-1 ring-black/5">
                            {isCompletedQueue ? (
                                <ul className="divide-y divide-neutral-100">
                                    {filtered.map((task) => (
                                        <TaskRow
                                            key={task.id}
                                            task={task}
                                            person={task.personId ? personById.get(task.personId) : undefined}
                                            deal={task.dealId ? dealById.get(task.dealId) : undefined}
                                            assignee={userById.get(task.assignedToId)}
                                            bucket="completed"
                                            onToggle={(next) => handleToggleComplete(task, next)}
                                            onOpen={() => setEditingTask(task)}
                                            pending={pendingToggle.has(task.id)}
                                            ariaCompleteLabel={t('ariaCompleteTask')}
                                            formatDue={(d) => formatDueDate(d, t, locale)}
                                        />
                                    ))}
                                </ul>
                            ) : (
                                visibleBuckets.map((bucket) => {
                                    const items = grouped[bucket];
                                    const isOverdueBucket = bucket === 'overdue';
                                    return (
                                        <section
                                            key={bucket}
                                            className="border-t border-neutral-200 first:border-t-0"
                                        >
                                            <div className="flex items-baseline justify-between px-5 pt-4 pb-2">
                                                <h3
                                                    className={`text-sm font-semibold ${isOverdueBucket ? 'text-destructive' : 'text-neutral-900'}`}
                                                >
                                                    {t(`bucket_${bucket}` as 'bucket_overdue')}
                                                </h3>
                                                <span className="text-xs tabular-nums text-neutral-400">
                                                    {items.length}
                                                </span>
                                            </div>
                                            <ul className="divide-y divide-neutral-100">
                                                {items.map((task) => (
                                                    <TaskRow
                                                        key={task.id}
                                                        task={task}
                                                        person={task.personId ? personById.get(task.personId) : undefined}
                                                        deal={task.dealId ? dealById.get(task.dealId) : undefined}
                                                        assignee={userById.get(task.assignedToId)}
                                                        bucket={bucket}
                                                        onToggle={(next) => handleToggleComplete(task, next)}
                                                        onOpen={() => setEditingTask(task)}
                                                        pending={pendingToggle.has(task.id)}
                                                        ariaCompleteLabel={t('ariaCompleteTask')}
                                                        formatDue={(d) => formatDueDate(d, t, locale)}
                                                    />
                                                ))}
                                            </ul>
                                        </section>
                                    );
                                })
                            )}
                        </div>
                    )}
                </div>
            </div>

            {editingTask && (
                <EditTaskSheet
                    task={editingTask}
                    open={!!editingTask}
                    onOpenChange={(open) => {
                        if (!open) setEditingTask(null);
                    }}
                    companyId={drawerCompanyId}
                    deals={deals}
                />
            )}

            <TaskDialog
                open={creating}
                onOpenChange={setCreating}
                persons={persons}
                deals={deals}
                users={users}
                currentUserId={currentUserId}
            />
        </div>
    );
}

function QueueButton({
    Icon,
    label,
    count,
    active,
    onClick,
}: {
    Icon: IconType;
    label: string;
    count: number;
    active: boolean;
    onClick: () => void;
}) {
    return (
        <button
            type="button"
            onClick={onClick}
            aria-current={active ? 'page' : undefined}
            className={`flex w-full items-center justify-between rounded-lg px-3 py-2 text-sm transition ${active ? 'bg-brand-light/60 font-medium text-brand-dark' : 'text-neutral-700 hover:bg-neutral-100'}`}
        >
            <span className="flex min-w-0 items-center gap-2.5">
                <Icon
                    className={`size-4 shrink-0 ${active ? 'text-brand-dark' : 'text-neutral-400'}`}
                />
                <span className="truncate">{label}</span>
            </span>
            <span
                className={`shrink-0 text-xs tabular-nums ${active ? 'text-brand-dark/70' : 'text-neutral-400'}`}
            >
                {count}
            </span>
        </button>
    );
}

type TaskRowProps = {
    task: Task;
    person?: Contact;
    deal?: Deal;
    assignee?: User;
    bucket: Bucket;
    onToggle: (next: boolean) => void;
    onOpen: () => void;
    pending: boolean;
    ariaCompleteLabel: string;
    formatDue: (dueDate: string | undefined) => string;
};

function TaskRow({
    task,
    person,
    deal,
    assignee,
    bucket,
    onToggle,
    onOpen,
    pending,
    ariaCompleteLabel,
    formatDue,
}: TaskRowProps) {
    const isOverdue = bucket === 'overdue';
    const isCompleted = bucket === 'completed';
    const dueLabel = formatDue(task.dueDate);

    return (
        <li
            className={`group flex cursor-pointer items-center gap-3 px-5 py-3 transition-colors hover:bg-neutral-50/70 ${isCompleted ? 'opacity-60' : ''}`}
            onClick={onOpen}
        >
            <div onClick={(e) => e.stopPropagation()} className="shrink-0">
                <Checkbox
                    checked={isCompleted}
                    onCheckedChange={(checked) => onToggle(checked === true)}
                    disabled={pending}
                    aria-label={ariaCompleteLabel}
                    className="size-4 rounded-full data-[state=checked]:bg-brand data-[state=checked]:border-brand"
                />
            </div>

            <span
                className={`min-w-0 flex-1 truncate text-sm ${isCompleted ? 'text-neutral-400 line-through' : 'text-neutral-900'}`}
            >
                {task.description}
            </span>

            <div className="hidden shrink-0 items-center gap-1.5 sm:flex">
                {person && (
                    <Link
                        href={`/records/contacts/${person.id}`}
                        onClick={(e) => e.stopPropagation()}
                        className="inline-flex max-w-[10rem] items-center gap-1 rounded-full bg-brand-light/50 px-2 py-0.5 text-xs font-medium text-brand-dark ring-1 ring-inset ring-brand-dark/10 transition hover:bg-brand-light"
                        title={person.name}
                    >
                        <UserIcon className="size-3 shrink-0" />
                        <span className="truncate">{person.name}</span>
                    </Link>
                )}
                {deal && (
                    <Link
                        href={`/records/deals/${deal.id}`}
                        onClick={(e) => e.stopPropagation()}
                        className="inline-flex max-w-[10rem] items-center gap-1 rounded-full bg-white px-2 py-0.5 text-xs font-medium text-neutral-700 ring-1 ring-inset ring-neutral-200 transition hover:bg-neutral-50"
                        title={deal.name}
                    >
                        <BriefcaseIcon className="size-3 shrink-0" />
                        <span className="truncate">{deal.name}</span>
                    </Link>
                )}
            </div>

            <span
                className={`w-16 shrink-0 text-right text-xs tabular-nums ${isOverdue ? 'font-medium text-destructive' : 'text-neutral-500'}`}
            >
                {dueLabel || <span className="text-neutral-300">—</span>}
            </span>

            <div className="shrink-0">
                {assignee ? (
                    <Tooltip>
                        <TooltipTrigger asChild>
                            <Avatar size="sm" className="ring-1 ring-black/5">
                                {assignee.profilePictureUrl ? (
                                    <AvatarImage
                                        src={assignee.profilePictureUrl}
                                        alt={assignee.displayName}
                                    />
                                ) : (
                                    <AvatarFallback>
                                        <UserIcon className="size-3 text-neutral-500" />
                                    </AvatarFallback>
                                )}
                            </Avatar>
                        </TooltipTrigger>
                        <TooltipContent side="bottom" align="end">
                            {assignee.displayName || assignee.username}
                        </TooltipContent>
                    </Tooltip>
                ) : (
                    <div className="size-6" />
                )}
            </div>
        </li>
    );
}