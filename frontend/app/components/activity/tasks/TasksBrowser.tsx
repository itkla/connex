'use client';

import {
    useCallback,
    useEffect,
    useLayoutEffect,
    useMemo,
    useRef,
    useState,
    type ComponentType,
    type KeyboardEvent,
} from 'react';
import { useLocale, useTranslations } from 'next-intl';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';
import Link from 'next/link';
import { AnimatePresence, motion, useReducedMotion } from 'motion/react';
import {
    MagnifyingGlassIcon,
    UserIcon,
    BriefcaseIcon,
    InboxIcon,
    CalendarDaysIcon,
    ExclamationCircleIcon,
    UserCircleIcon,
    QueueListIcon,
    ViewColumnsIcon,
    CheckCircleIcon,
    CheckIcon,
} from '@heroicons/react/24/outline';
import { PlusIcon } from '@heroicons/react/24/solid';

import { SearchField, FilterBar, MultiSelectFilter, type FilterChipData } from '@/app/components/filters';
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';
import { Checkbox } from '@/components/ui/checkbox';
import { Button } from '@/components/ui/button';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';

import DeleteRecordDialog from '@/app/components/records/DeleteRecordDialog';
import {
    RecordActionMenuTrigger,
    RecordContextMenu,
    type RecordMenuModel,
} from '@/app/components/records/RecordActionMenu';
import EditTaskSheet from '@/app/components/activity/tasks/EditTaskSheet';
import TaskFilterSheet, {
    type TaskFilterSheetSection,
} from '@/app/components/activity/tasks/TaskFilterSheet';
import TaskDialog from '@/app/components/activity/tasks/TaskDialog';
import TasksKanban from '@/app/components/activity/tasks/TasksKanban';
import NoteContent from '@/app/components/activity/notes/NoteContent';
import { type DueTone, DUE_CHIP, formatDue } from '@/app/components/activity/tasks/taskDue';
import Rise from '@/app/components/motion/Rise';
import { deleteTask, getTaskById, updateTask } from '@/app/lib/api';
import { useUrlSync } from '@/app/hooks/useUrlSync';
import { useIsMobile } from '@/app/hooks/useIsMobile';
import { useScopedViewPreference } from '@/app/hooks/useScopedViewPreference';
import { effectiveListView } from '@/app/hooks/viewPreference';
import { useWorkspace } from '@/app/hooks/useWorkspace';
import { toastError, toastSuccess } from '@/app/lib/toast';
import { noteSnippet } from '@/app/lib/noteText';
import { parseMysqlDateTime } from '@/app/lib/utils';
import { cn } from '@/lib/utils';
import type { Contact, Deal, Task, User } from '@/app/lib/types';

type Props = {
    tasks: Task[];
    persons: Contact[];
    deals: Deal[];
    users: User[];
    currentUserId: number;
    canDeleteTasks: boolean;
    originWorkspaceId: number | null;
};

type Bucket = 'overdue' | 'today' | 'upcoming' | 'noDate' | 'completed';
type Queue = 'myOpen' | 'dueToday' | 'overdue' | 'unassigned' | 'allOpen' | 'completed';
type TaskView = 'list' | 'board';
type IconType = ComponentType<{ className?: string }>;

const ACTIVE_BUCKETS: Bucket[] = ['overdue', 'today', 'upcoming', 'noDate'];
const ACTIVE_QUEUES: Queue[] = ['myOpen', 'dueToday', 'overdue', 'unassigned', 'allOpen'];
const ALL_QUEUES: Queue[] = [...ACTIVE_QUEUES, 'completed'];
const QUEUE_STORAGE_KEY = 'tasks:queue';
const VIEW_STORAGE_KEY = 'tasks:view';
const EASE_OUT: [number, number, number, number] = [0.23, 1, 0.32, 1];
const COMPLETE_LINGER_MS = 230;
const WEEK_MS = 7 * 24 * 60 * 60 * 1000;

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

function isTaskView(value: unknown): value is TaskView {
    return value === 'list' || value === 'board';
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

function bumpOption(map: Map<string, { label: string; count: number }>, key: string, label: string) {
    const e = map.get(key);
    if (e) e.count++;
    else map.set(key, { label, count: 1 });
}

function toOptions(map: Map<string, { label: string; count: number }>) {
    return [...map.entries()]
        .sort((a, b) => b[1].count - a[1].count || a[1].label.localeCompare(b[1].label))
        .map(([value, { label, count }]) => ({ value, label, total: count }));
}

function toggleInSet(setter: React.Dispatch<React.SetStateAction<Set<string>>>, value: string) {
    setter((prev) => {
        const next = new Set(prev);
        if (next.has(value)) next.delete(value);
        else next.add(value);
        return next;
    });
}

export default function TasksBrowser({
    tasks: initialTasks,
    persons,
    deals,
    users,
    currentUserId,
    canDeleteTasks,
    originWorkspaceId,
}: Props) {
    const t = useTranslations('ActivityTasks');
    const tf = useTranslations('Filters');
    const locale = useLocale();
    const router = useRouter();
    const pathname = usePathname() ?? '';
    const reduce = useReducedMotion() ?? false;
    const { activeWorkspaceId, switching } = useWorkspace();
    const isMobile = useIsMobile();
    const [originPathname] = useState(pathname);

    const [now] = useState(() => Date.now());
    const [tasks, setTasks] = useState<Task[]>(initialTasks);
    useEffect(() => {
        // eslint-disable-next-line react-hooks/set-state-in-effect
        setTasks(initialTasks);
    }, [initialTasks]);

    const personById = useMemo(() => new Map(persons.map((p) => [p.id, p])), [persons]);
    const dealById = useMemo(() => new Map(deals.map((d) => [d.id, d])), [deals]);
    const userById = useMemo(() => new Map(users.map((u) => [u.id, u])), [users]);

    const [query, setQuery] = useState('');
    const [assigneeFilter, setAssigneeFilter] = useState<Set<string>>(new Set());
    const [personFilter, setPersonFilter] = useState<Set<string>>(new Set());
    const [dealFilter, setDealFilter] = useState<Set<string>>(new Set());
    const [companyFilter, setCompanyFilter] = useState<Set<string>>(new Set());
    const [queue, setQueue] = useState<Queue>('myOpen');
    const [queueInitialized, setQueueInitialized] = useState(false);
    const [view, setView] = useScopedViewPreference<TaskView>({
        storageKey: VIEW_STORAGE_KEY,
        userId: currentUserId,
        workspaceId: activeWorkspaceId,
        initialValue: null,
        fallback: 'list',
        isValue: isTaskView,
    });
    const effectiveView: TaskView = effectiveListView(view, isMobile);
    const [editingTask, setEditingTask] = useState<Task | null>(null);
    const [creating, setCreating] = useState(false);
    const [deletingTask, setDeletingTask] = useState<Task | null>(null);
    const [deleting, setDeleting] = useState(false);
    const [rovingTaskId, setRovingTaskId] = useState<number | null>(null);
    const [pendingToggle, setPendingToggle] = useState<Set<number>>(new Set());
    const [completing, setCompleting] = useState<Set<number>>(new Set());
    const searchInputRef = useRef<HTMLInputElement>(null);
    const rowRefs = useRef(new Map<number, HTMLButtonElement>());
    const pendingFocusRef = useRef<number | 'search' | null>(null);
    const timers = useRef<number[]>([]);
    const deleteControllerRef = useRef<AbortController | null>(null);
    const toggleControllersRef = useRef(new Map<number, AbortController>());
    const liveScopeRef = useRef({
        active: true,
        activeWorkspaceId,
        originWorkspaceId,
        pathname,
        switching,
    });
    const pageCurrent = !switching
        && originWorkspaceId !== null
        && activeWorkspaceId === originWorkspaceId
        && pathname === originPathname;

    useLayoutEffect(() => {
        const toggleControllers = toggleControllersRef.current;
        liveScopeRef.current = {
            active: true,
            activeWorkspaceId,
            originWorkspaceId,
            pathname,
            switching,
        };
        return () => {
            liveScopeRef.current = {
                active: false,
                activeWorkspaceId,
                originWorkspaceId,
                pathname,
                switching,
            };
            deleteControllerRef.current?.abort();
            deleteControllerRef.current = null;
            toggleControllers.forEach((controller) => controller.abort());
            toggleControllers.clear();
        };
    }, [activeWorkspaceId, originWorkspaceId, pathname, switching]);

    useEffect(() => {
        if (pageCurrent) return;
        const timer = window.setTimeout(() => {
            timers.current.forEach((id) => window.clearTimeout(id));
            timers.current = [];
            setDeletingTask(null);
            setDeleting(false);
            setPendingToggle(new Set());
            setCompleting(new Set());
        }, 0);
        return () => window.clearTimeout(timer);
    }, [pageCurrent]);

    const searchParams = useSearchParams();
    useEffect(() => {
        const taskId = searchParams.get('task');
        if (taskId && /^\d+$/.test(taskId)) {
            getTaskById(Number(taskId)).then(setEditingTask).catch(() => {});
        }
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);
    useUrlSync({ task: editingTask ? String(editingTask.id) : undefined });

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

    useEffect(() => () => timers.current.forEach((id) => window.clearTimeout(id)), []);

    const queueCounts = useMemo(() => {
        const counts: Record<Queue, number> = {
            myOpen: 0, dueToday: 0, overdue: 0, unassigned: 0, allOpen: 0, completed: 0,
        };
        for (const task of tasks) {
            for (const q of ALL_QUEUES) {
                if (isInQueue(q, task, currentUserId)) counts[q]++;
            }
        }
        return counts;
    }, [tasks, currentUserId]);

    const todayFocus = useMemo(() => {
        const start = startOfToday();
        const end = endOfToday();
        let total = 0;
        let done = 0;
        let doneThisWeek = 0;
        const weekAgo = now - WEEK_MS;
        for (const task of tasks) {
            const ts = parseMysqlDateTime(task.dueDate);
            if (!Number.isNaN(ts) && ts >= start && ts <= end) {
                total++;
                if (task.completed) done++;
            }
            if (task.completed) {
                const updated = parseMysqlDateTime(task.updatedAt);
                if (!Number.isNaN(updated) && updated >= weekAgo) doneThisWeek++;
            }
        }
        return { total, done, left: total - done, overdue: queueCounts.overdue, doneThisWeek };
    }, [tasks, queueCounts.overdue, now]);

    const companyIdForTask = useCallback(
        (task: Task) => {
            if (task.personId == null) return null;
            const person = personById.get(task.personId);
            return person?.company?.id ?? person?.companyId ?? null;
        },
        [personById],
    );

    const queueTasks = useMemo(
        () => tasks.filter((task) => isInQueue(queue, task, currentUserId) || completing.has(task.id)),
        [tasks, queue, currentUserId, completing],
    );

    const dimensionOptions = useMemo(() => {
        const assignees = new Map<string, { label: string; count: number }>();
        const persons_ = new Map<string, { label: string; count: number }>();
        const deals_ = new Map<string, { label: string; count: number }>();
        const companies = new Map<string, { label: string; count: number }>();
        for (const task of queueTasks) {
            const assignee = userById.get(task.assignedToId);
            if (assignee) bumpOption(assignees, String(task.assignedToId), assignee.displayName || assignee.username);
            if (task.personId != null) {
                const person = personById.get(task.personId);
                if (person) bumpOption(persons_, String(person.id), person.name);
            }
            if (task.dealId != null) {
                const deal = dealById.get(task.dealId);
                if (deal) bumpOption(deals_, String(deal.id), deal.name);
            }
            const companyId = companyIdForTask(task);
            if (companyId != null) {
                const company = task.personId != null ? personById.get(task.personId)?.company : undefined;
                if (company) bumpOption(companies, String(companyId), company.name);
            }
        }
        return {
            assignees: toOptions(assignees),
            persons: toOptions(persons_),
            deals: toOptions(deals_),
            companies: toOptions(companies),
        };
    }, [queueTasks, userById, personById, dealById, companyIdForTask]);

    const filtered = useMemo(() => {
        const q = query.trim().toLowerCase();
        return queueTasks.filter((task) => {
            if (assigneeFilter.size && !assigneeFilter.has(String(task.assignedToId))) return false;
            if (personFilter.size && !(task.personId != null && personFilter.has(String(task.personId)))) return false;
            if (dealFilter.size && !(task.dealId != null && dealFilter.has(String(task.dealId)))) return false;
            if (companyFilter.size) {
                const companyId = companyIdForTask(task);
                if (!(companyId != null && companyFilter.has(String(companyId)))) return false;
            }
            if (!q) return true;
            const haystacks = [
                task.description,
                task.personId ? personById.get(task.personId)?.name : null,
                task.dealId ? dealById.get(task.dealId)?.name : null,
                userById.get(task.assignedToId)?.displayName,
            ];
            return haystacks.some((s) => s?.toLowerCase().includes(q));
        });
    }, [queueTasks, query, assigneeFilter, personFilter, dealFilter, companyFilter, companyIdForTask, personById, dealById, userById]);

    const boardTasks = useMemo(() => {
        const q = query.trim().toLowerCase();
        if (!q) return tasks;
        return tasks.filter((task) => {
            const haystacks = [
                task.description,
                task.personId ? personById.get(task.personId)?.name : null,
                task.dealId ? dealById.get(task.dealId)?.name : null,
                userById.get(task.assignedToId)?.displayName,
            ];
            return haystacks.some((s) => s?.toLowerCase().includes(q));
        });
    }, [tasks, query, personById, dealById, userById]);

    const grouped = useMemo(() => {
        const buckets: Record<Bucket, Task[]> = { overdue: [], today: [], upcoming: [], noDate: [], completed: [] };
        for (const task of filtered) {
            const bucket = completing.has(task.id) ? bucketForTask({ ...task, completed: false }) : bucketForTask(task);
            buckets[bucket].push(task);
        }
        for (const key of [...ACTIVE_BUCKETS, 'completed' as const]) {
            buckets[key].sort((a, c) => dueTimestamp(a) - dueTimestamp(c));
        }
        return buckets;
    }, [filtered, completing]);

    const visibleBuckets = useMemo(() => ACTIVE_BUCKETS.filter((b) => grouped[b].length > 0), [grouped]);
    const visibleTasks = useMemo(
        () => queue === 'completed' ? filtered : visibleBuckets.flatMap((bucket) => grouped[bucket]),
        [queue, filtered, visibleBuckets, grouped],
    );
    const effectiveRovingTaskId =
        rovingTaskId != null && visibleTasks.some((task) => task.id === rovingTaskId)
            ? rovingTaskId
            : visibleTasks[0]?.id ?? null;

    useEffect(() => {
        const pendingFocus = pendingFocusRef.current;
        if (pendingFocus == null) return;
        if (pendingFocus === 'search') {
            pendingFocusRef.current = null;
            searchInputRef.current?.focus();
            return;
        }
        const row = rowRefs.current.get(pendingFocus);
        if (!row) return;
        pendingFocusRef.current = null;
        row.focus();
    }, [visibleTasks]);

    const focusTaskRow = useCallback((taskId: number) => {
        setRovingTaskId(taskId);
        rowRefs.current.get(taskId)?.focus();
    }, []);

    const handleTaskRowKeyDown = useCallback(
        (event: KeyboardEvent<HTMLButtonElement>, task: Task) => {
            const rowIndex = visibleTasks.findIndex((visibleTask) => visibleTask.id === task.id);
            if (rowIndex < 0) return;

            if (event.key === 'ArrowDown') {
                const next = visibleTasks[rowIndex + 1];
                if (next) {
                    event.preventDefault();
                    focusTaskRow(next.id);
                }
            } else if (event.key === 'ArrowUp') {
                const previous = visibleTasks[rowIndex - 1];
                if (previous) {
                    event.preventDefault();
                    focusTaskRow(previous.id);
                }
            } else if (event.key === 'Home') {
                const first = visibleTasks[0];
                if (first) {
                    event.preventDefault();
                    focusTaskRow(first.id);
                }
            } else if (event.key === 'End') {
                const last = visibleTasks[visibleTasks.length - 1];
                if (last) {
                    event.preventDefault();
                    focusTaskRow(last.id);
                }
            } else if (event.key === 'ContextMenu' || (event.key === 'F10' && event.shiftKey)) {
                const row = rowRefs.current.get(task.id);
                if (row) {
                    event.preventDefault();
                    const rect = row.getBoundingClientRect();
                    row.dispatchEvent(
                        new MouseEvent('contextmenu', {
                            bubbles: true,
                            cancelable: true,
                            clientX: rect.right - 40,
                            clientY: rect.top + rect.height / 2,
                        }),
                    );
                }
            }
        },
        [visibleTasks, focusTaskRow],
    );

    const setTaskCompleted = (id: number, value: boolean) =>
        setTasks((prev) => prev.map((task) => (task.id === id ? { ...task, completed: value } : task)));

    const handleToggleComplete = async (task: Task, next: boolean) => {
        const operationWorkspaceId = originWorkspaceId;
        if (
            !pageCurrent
            || operationWorkspaceId === null
            || task.workspaceId !== operationWorkspaceId
            || pendingToggle.has(task.id)
        ) return;
        const controller = new AbortController();
        toggleControllersRef.current.set(task.id, controller);
        const scopeCurrent = () => {
            const scope = liveScopeRef.current;
            return scope.active
                && !scope.switching
                && scope.activeWorkspaceId === operationWorkspaceId
                && scope.originWorkspaceId === operationWorkspaceId
                && scope.pathname === originPathname;
        };
        setPendingToggle((prev) => new Set(prev).add(task.id));
        let optimisticTimerId: number | null = null;
        let requestSettled = false;
        let timerSettled = !(next && !reduce);

        const finishOperation = () => {
            if (!requestSettled || !timerSettled) return;
            if (toggleControllersRef.current.get(task.id) !== controller) return;
            toggleControllersRef.current.delete(task.id);
            if (scopeCurrent()) {
                setPendingToggle((prev) => {
                    const n = new Set(prev);
                    n.delete(task.id);
                    return n;
                });
            }
        };

        const commitOptimistic = () => {
            if (optimisticTimerId !== null) {
                timers.current = timers.current.filter((id) => id !== optimisticTimerId);
                optimisticTimerId = null;
            }
            timerSettled = true;
            if (
                controller.signal.aborted
                || !scopeCurrent()
                || toggleControllersRef.current.get(task.id) !== controller
            ) {
                finishOperation();
                return;
            }
            setTaskCompleted(task.id, next);
            setCompleting((prev) => {
                const n = new Set(prev);
                n.delete(task.id);
                return n;
            });
            finishOperation();
        };

        if (next && !reduce) {
            setCompleting((prev) => new Set(prev).add(task.id));
            const id = window.setTimeout(commitOptimistic, COMPLETE_LINGER_MS);
            optimisticTimerId = id;
            timers.current.push(id);
        } else {
            commitOptimistic();
        }

        try {
            await updateTask(
                task.id,
                {
                    description: task.description,
                    dueDate: task.dueDate,
                    assignedToId: task.assignedToId,
                    personId: task.personId ?? undefined,
                    dealId: task.dealId ?? undefined,
                    completed: next,
                },
                {
                    headers: { 'X-Workspace-Id': String(operationWorkspaceId) },
                    signal: controller.signal,
                },
            );
        } catch (err) {
            if (controller.signal.aborted || !scopeCurrent()) return;
            if (optimisticTimerId !== null) {
                window.clearTimeout(optimisticTimerId);
                timers.current = timers.current.filter((id) => id !== optimisticTimerId);
                optimisticTimerId = null;
            }
            timerSettled = true;
            toastError(err instanceof Error ? err.message : t('toastFailedUpdate'));
            setTaskCompleted(task.id, !next);
            setCompleting((prev) => {
                const n = new Set(prev);
                n.delete(task.id);
                return n;
            });
        } finally {
            requestSettled = true;
            finishOperation();
        }
    };

    const handleDeleteTask = async () => {
        const operationWorkspaceId = originWorkspaceId;
        if (
            !deletingTask
            || deleting
            || !pageCurrent
            || operationWorkspaceId === null
            || deletingTask.workspaceId !== operationWorkspaceId
            || pendingToggle.has(deletingTask.id)
            || completing.has(deletingTask.id)
        ) return;
        const deletingIndex = visibleTasks.findIndex((task) => task.id === deletingTask.id);
        const focusTarget =
            deletingIndex >= 0
                ? visibleTasks[deletingIndex + 1] ?? visibleTasks[deletingIndex - 1] ?? null
                : null;

        setDeleting(true);
        const controller = new AbortController();
        deleteControllerRef.current = controller;
        const scopeCurrent = () => {
            const scope = liveScopeRef.current;
            return scope.active
                && !scope.switching
                && scope.activeWorkspaceId === operationWorkspaceId
                && scope.originWorkspaceId === operationWorkspaceId
                && scope.pathname === originPathname;
        };
        try {
            await deleteTask(deletingTask.id, {
                headers: { 'X-Workspace-Id': String(operationWorkspaceId) },
                signal: controller.signal,
            });
            if (controller.signal.aborted || !scopeCurrent()) return;
            pendingFocusRef.current = focusTarget?.id ?? 'search';
            setRovingTaskId(focusTarget?.id ?? null);
            setTasks((previous) => previous.filter((task) => task.id !== deletingTask.id));
            setDeletingTask(null);
            toastSuccess(t('toastDeleted'));
            router.refresh();
        } catch (error) {
            if (controller.signal.aborted || !scopeCurrent()) return;
            toastError(error instanceof Error ? error.message : t('toastFailedDelete'));
        } finally {
            if (deleteControllerRef.current === controller) {
                deleteControllerRef.current = null;
                if (scopeCurrent()) setDeleting(false);
            }
        }
    };

    const drawerCompanyId = editingTask?.personId
        ? personById.get(editingTask.personId)?.companyId ?? null
        : null;
    const visibleDeletingTask = pageCurrent ? deletingTask : null;

    const hasAnyTasks = tasks.length > 0;
    const isEmpty = filtered.length === 0;
    const isCompletedQueue = queue === 'completed';
    const dimensionsActive =
        assigneeFilter.size > 0 || personFilter.size > 0 || dealFilter.size > 0 || companyFilter.size > 0;
    const emptyMessage = query.trim() || dimensionsActive ? t('emptyFiltered') : t(`emptyQueue_${queue}` as 'emptyQueue_myOpen');

    const labelFor = (options: { value: string; label: string }[], value: string) =>
        options.find((o) => o.value === value)?.label ?? value;
    const chips: FilterChipData[] = [
        ...(query.trim() ? [{ id: 'q', label: tf('chipSearch', { query: query.trim() }), onRemove: () => setQuery('') }] : []),
        ...[...assigneeFilter].map((v) => ({ id: `assignee-${v}`, label: labelFor(dimensionOptions.assignees, v), onRemove: () => toggleInSet(setAssigneeFilter, v) })),
        ...[...personFilter].map((v) => ({ id: `person-${v}`, label: labelFor(dimensionOptions.persons, v), onRemove: () => toggleInSet(setPersonFilter, v) })),
        ...[...dealFilter].map((v) => ({ id: `deal-${v}`, label: labelFor(dimensionOptions.deals, v), onRemove: () => toggleInSet(setDealFilter, v) })),
        ...[...companyFilter].map((v) => ({ id: `company-${v}`, label: labelFor(dimensionOptions.companies, v), onRemove: () => toggleInSet(setCompanyFilter, v) })),
    ];
    const clearAllFilters = () => {
        setQuery('');
        setAssigneeFilter(new Set());
        setPersonFilter(new Set());
        setDealFilter(new Set());
        setCompanyFilter(new Set());
    };
    const activeDimensionCount =
        assigneeFilter.size + personFilter.size + dealFilter.size + companyFilter.size;
    const taskFilterSections: TaskFilterSheetSection[] = [
        {
            label: tf('assignee'),
            options: dimensionOptions.assignees,
            selected: assigneeFilter,
            onToggle: (value) => toggleInSet(setAssigneeFilter, value),
        },
        {
            label: tf('contact'),
            options: dimensionOptions.persons,
            selected: personFilter,
            onToggle: (value) => toggleInSet(setPersonFilter, value),
        },
        {
            label: tf('deal'),
            options: dimensionOptions.deals,
            selected: dealFilter,
            onToggle: (value) => toggleInSet(setDealFilter, value),
        },
        {
            label: tf('company'),
            options: dimensionOptions.companies,
            selected: companyFilter,
            onToggle: (value) => toggleInSet(setCompanyFilter, value),
        },
    ];

    const renderRow = (task: Task, bucket: Bucket) => {
        const label = noteSnippet(task.description, 60) || t('entityLabel');
        const taskMutationPending = pendingToggle.has(task.id) || completing.has(task.id);
        const taskInOriginWorkspace = task.workspaceId === originWorkspaceId;
        const menuModel: RecordMenuModel = {
            record: { type: 'task', id: task.id, label },
            includeCreateActions: false,
            onRemove: canDeleteTasks && pageCurrent && taskInOriginWorkspace && !taskMutationPending
                ? () => setDeletingTask(task)
                : undefined,
        };

        return (
            <TaskRow
                key={task.id}
                task={task}
                reduce={reduce}
                checked={completing.has(task.id) || task.completed}
                person={task.personId ? personById.get(task.personId) : undefined}
                deal={task.dealId ? dealById.get(task.dealId) : undefined}
                assignee={userById.get(task.assignedToId)}
                bucket={bucket}
                onToggle={(nextChecked) => handleToggleComplete(task, nextChecked)}
                onOpen={() => {
                    if (pageCurrent && taskInOriginWorkspace) setEditingTask(task);
                }}
                pending={pendingToggle.has(task.id)}
                ariaCompleteLabel={t('ariaCompleteTask')}
                ariaOpenLabel={t('ariaOpenTask', { name: label })}
                due={formatDue(task.dueDate, t, locale)}
                menuModel={menuModel}
                tabIndex={task.id === effectiveRovingTaskId ? 0 : -1}
                rowRef={(row) => {
                    if (row) rowRefs.current.set(task.id, row);
                    else rowRefs.current.delete(task.id);
                }}
                onFocus={() => setRovingTaskId(task.id)}
                onKeyDown={(event) => handleTaskRowKeyDown(event, task)}
            />
        );
    };

    return (
        <div className="min-h-full bg-background px-2 pt-8 pb-12">
            <div className="mx-auto flex w-full max-w-[100rem] flex-col gap-10">
                <Rise>
                    <header className="flex flex-wrap items-start justify-between gap-4">
                        <div>
                            <h1 className="text-4xl font-extrabold tracking-tight">{t('title')}</h1>
                            <p className="mt-1 text-sm text-muted-foreground">{t('subtitle')}</p>
                        </div>
                        <div className="flex items-center gap-2">
                            <div
                                role="group"
                                aria-label={t('displayMode')}
                                className="hidden rounded-full bg-muted p-0.5 ring-1 ring-border md:inline-flex"
                            >
                                <button
                                    type="button"
                                    onClick={() => setView('list')}
                                    aria-label={t('viewList')}
                                    aria-pressed={view === 'list'}
                                    className={cn(
                                        'flex h-8 w-8 items-center justify-center rounded-full transition active:scale-[0.97]',
                                        view === 'list' ? 'bg-background text-foreground shadow' : 'text-muted-foreground hover:text-foreground',
                                    )}
                                >
                                    <QueueListIcon className="size-4" />
                                </button>
                                <button
                                    type="button"
                                    onClick={() => setView('board')}
                                    aria-label={t('viewBoard')}
                                    aria-pressed={view === 'board'}
                                    className={cn(
                                        'flex h-8 w-8 items-center justify-center rounded-full transition active:scale-[0.97]',
                                        view === 'board' ? 'bg-background text-foreground shadow' : 'text-muted-foreground hover:text-foreground',
                                    )}
                                >
                                    <ViewColumnsIcon className="size-4" />
                                </button>
                            </div>
                            <Button
                                variant="brand"
                                className="shadow-sm transition-transform active:scale-[0.98]"
                                aria-label={t('newAria')}
                                onClick={() => setCreating(true)}
                            >
                                <PlusIcon strokeWidth={2.5} />
                                {t('new')}
                            </Button>
                        </div>
                    </header>
                </Rise>

                {hasAnyTasks && (
                    <Rise delay={0.06}>
                        <FocusStrip
                            focus={todayFocus}
                            reduce={reduce}
                            t={t}
                            onSelectToday={() => setQueue('dueToday')}
                            onSelectOverdue={() => setQueue('overdue')}
                            onSelectWeek={() => setQueue('completed')}
                        />
                    </Rise>
                )}

                <Rise delay={0.12}>
                    {effectiveView === 'board' ? (
                        <div className="min-w-0 space-y-4">
                            <FilterBar
                                reduce={reduce}
                                chips={query.trim() ? [{ id: 'q', label: tf('chipSearch', { query: query.trim() }), onRemove: () => setQuery('') }] : []}
                                hasActiveFilters={query.trim() !== ''}
                                onClearAll={() => setQuery('')}
                                clearAllLabel={tf('clearAll')}
                                className="py-0"
                                search={
                                    <SearchField
                                        inputRef={searchInputRef}
                                        value={query}
                                        onChange={setQuery}
                                        onClear={() => setQuery('')}
                                        placeholder={t('searchPlaceholder')}
                                        searchAria={tf('searchAria')}
                                        clearAria={tf('clearSearchAria')}
                                    />
                                }
                            />
                            <TasksKanban
                                tasks={boardTasks}
                                personById={personById}
                                dealById={dealById}
                                userById={userById}
                                onMoved={() => router.refresh()}
                                onOpen={setEditingTask}
                                reduce={reduce}
                            />
                        </div>
                    ) : (
                        <div className="grid grid-cols-1 gap-6 md:grid-cols-[200px_minmax(0,1fr)] md:gap-10">
                            <aside className="md:sticky md:top-6 md:self-start">
                                <h2 className="mb-2 px-3 text-xs font-medium uppercase tracking-[0.12em] text-muted-foreground">
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
                                            reduce={reduce}
                                            onClick={() => setQueue(q)}
                                        />
                                    ))}
                                    <div className="mx-3 my-3 h-px bg-border" />
                                    <QueueButton
                                        Icon={QUEUE_ICON.completed}
                                        label={t('queue_completed')}
                                        count={queueCounts.completed}
                                        active={queue === 'completed'}
                                        reduce={reduce}
                                        onClick={() => setQueue('completed')}
                                    />
                                </nav>
                            </aside>

                            <div className="min-w-0 space-y-4">
                                <FilterBar
                                    reduce={reduce}
                                    chips={chips}
                                    hasActiveFilters={query.trim() !== '' || dimensionsActive}
                                    onClearAll={clearAllFilters}
                                    clearAllLabel={tf('clearAll')}
                                    className="py-0"
                                    search={
                                        <SearchField
                                            inputRef={searchInputRef}
                                            value={query}
                                            onChange={setQuery}
                                            onClear={() => setQuery('')}
                                            placeholder={t('searchPlaceholder')}
                                            searchAria={tf('searchAria')}
                                            clearAria={tf('clearSearchAria')}
                                            className="min-w-0 flex-1 md:flex-initial"
                                        />
                                    }
                                    collapsed={
                                        <TaskFilterSheet
                                            sections={taskFilterSections}
                                            activeCount={activeDimensionCount}
                                            hasActiveFilters={query.trim() !== '' || dimensionsActive}
                                            onClearAll={clearAllFilters}
                                        />
                                    }
                                >
                                    {dimensionOptions.assignees.length > 0 && (
                                        <MultiSelectFilter
                                            label={tf('assignee')}
                                            ariaLabel={tf('assignee')}
                                            options={dimensionOptions.assignees}
                                            selected={assigneeFilter}
                                            onToggle={(v) => toggleInSet(setAssigneeFilter, v)}
                                            onClear={() => setAssigneeFilter(new Set())}
                                            clearLabel={tf('clear')}
                                            scroll
                                        />
                                    )}
                                    {dimensionOptions.persons.length > 0 && (
                                        <MultiSelectFilter
                                            label={tf('contact')}
                                            ariaLabel={tf('contact')}
                                            options={dimensionOptions.persons}
                                            selected={personFilter}
                                            onToggle={(v) => toggleInSet(setPersonFilter, v)}
                                            onClear={() => setPersonFilter(new Set())}
                                            clearLabel={tf('clear')}
                                            scroll
                                        />
                                    )}
                                    {dimensionOptions.deals.length > 0 && (
                                        <MultiSelectFilter
                                            label={tf('deal')}
                                            ariaLabel={tf('deal')}
                                            options={dimensionOptions.deals}
                                            selected={dealFilter}
                                            onToggle={(v) => toggleInSet(setDealFilter, v)}
                                            onClear={() => setDealFilter(new Set())}
                                            clearLabel={tf('clear')}
                                            scroll
                                        />
                                    )}
                                    {dimensionOptions.companies.length > 0 && (
                                        <MultiSelectFilter
                                            label={tf('company')}
                                            ariaLabel={tf('company')}
                                            options={dimensionOptions.companies}
                                            selected={companyFilter}
                                            onToggle={(v) => toggleInSet(setCompanyFilter, v)}
                                            onClear={() => setCompanyFilter(new Set())}
                                            clearLabel={tf('clear')}
                                            scroll
                                        />
                                    )}
                                </FilterBar>

                                {isEmpty ? (
                                    <TaskEmptyState
                                        filtered={!!query.trim()}
                                        completed={isCompletedQueue}
                                        message={emptyMessage}
                                    />
                                ) : isCompletedQueue ? (
                                    <div className="overflow-hidden rounded-2xl border border-border bg-card">
                                        <ul className="divide-y divide-border">
                                            <AnimatePresence initial={false} mode="popLayout">
                                                {filtered.map((task) => renderRow(task, 'completed'))}
                                            </AnimatePresence>
                                        </ul>
                                    </div>
                                ) : (
                                    <div className="overflow-hidden rounded-2xl border border-border bg-card">
                                        {visibleBuckets.map((bucket, i) => (
                                            <section key={bucket} className={cn(i > 0 && 'border-t border-border')}>
                                                <div className="flex items-baseline justify-between px-5 pt-4 pb-2">
                                                    <h3
                                                        className={cn(
                                                            'text-sm font-semibold',
                                                            bucket === 'overdue' ? 'text-destructive' : 'text-foreground',
                                                        )}
                                                    >
                                                        {t(`bucket_${bucket}` as 'bucket_overdue')}
                                                    </h3>
                                                    <span className="text-xs tabular-nums text-muted-foreground">
                                                        {grouped[bucket].length}
                                                    </span>
                                                </div>
                                                <ul className="divide-y divide-border">
                                                    <AnimatePresence initial={false} mode="popLayout">
                                                        {grouped[bucket].map((task) => renderRow(task, bucket))}
                                                    </AnimatePresence>
                                                </ul>
                                            </section>
                                        ))}
                                    </div>
                                )}
                            </div>
                        </div>
                    )}
                </Rise>
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

            <DeleteRecordDialog<Task>
                open={visibleDeletingTask !== null}
                onOpenChange={(open) => {
                    if (!open && !deleting) setDeletingTask(null);
                }}
                selectedIds={new Set(visibleDeletingTask ? [visibleDeletingTask.id] : [])}
                selectedItems={visibleDeletingTask ? [visibleDeletingTask] : []}
                entityLabel={t('entityLabel')}
                getDisplayName={(task) => noteSnippet(task.description, 60) || t('entityLabel')}
                isDeleting={deleting}
                confirmDelete={() => void handleDeleteTask()}
            />

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

function ProgressRing({ value, reduce, label }: { value: number; reduce: boolean; label: string }) {
    const size = 48;
    const stroke = 5;
    const r = (size - stroke) / 2;
    const c = 2 * Math.PI * r;
    const pct = Math.max(0, Math.min(1, value));
    const offset = c * (1 - pct);
    return (
        <div className="relative shrink-0" style={{ width: size, height: size }}>
            <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`} className="-rotate-90">
                <circle cx={size / 2} cy={size / 2} r={r} fill="none" stroke="var(--chart-grid)" strokeWidth={stroke} />
                <motion.circle
                    cx={size / 2}
                    cy={size / 2}
                    r={r}
                    fill="none"
                    stroke="var(--color-brand)"
                    strokeWidth={stroke}
                    strokeLinecap="round"
                    strokeDasharray={c}
                    initial={{ strokeDashoffset: reduce ? offset : c }}
                    animate={{ strokeDashoffset: offset }}
                    transition={{ duration: reduce ? 0 : 0.9, ease: EASE_OUT }}
                />
            </svg>
            <span className="absolute inset-0 flex items-center justify-center font-mono text-[11px] font-medium tabular-nums text-foreground">
                {label}
            </span>
        </div>
    );
}

function FocusStrip({
    focus,
    reduce,
    t,
    onSelectToday,
    onSelectOverdue,
    onSelectWeek,
}: {
    focus: { total: number; done: number; left: number; overdue: number; doneThisWeek: number };
    reduce: boolean;
    t: ReturnType<typeof useTranslations>;
    onSelectToday: () => void;
    onSelectOverdue: () => void;
    onSelectWeek: () => void;
}) {
    const { total, done, left, overdue, doneThisWeek } = focus;
    const todaySub =
        total === 0 ? t('nothingDueToday') : left === 0 ? t('allDoneToday') : t('tasksLeft', { count: left });

    return (
        <div className="grid grid-cols-3 gap-px overflow-hidden rounded-2xl border border-border bg-border">
            <button
                type="button"
                onClick={onSelectToday}
                className="flex min-h-20 items-center gap-3.5 bg-card p-4 text-left outline-none transition hover:bg-muted focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-brand"
            >
                <ProgressRing
                    value={total === 0 ? 0 : done / total}
                    reduce={reduce}
                    label={total === 0 ? '0' : `${done}/${total}`}
                />
                <span className="min-w-0">
                    <span className="block text-sm font-semibold text-foreground">{t('today')}</span>
                    <span className="mt-0.5 block truncate text-xs text-muted-foreground">{todaySub}</span>
                </span>
            </button>

            <button
                type="button"
                onClick={onSelectOverdue}
                className="flex min-h-20 flex-col justify-center bg-card p-4 text-left outline-none transition hover:bg-muted focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-brand"
            >
                <span className={cn('text-2xl font-semibold leading-none tabular-nums', overdue > 0 ? 'text-destructive' : 'text-muted-foreground')}>
                    {overdue}
                </span>
                <span className="mt-1.5 text-xs font-medium text-muted-foreground">{t('queue_overdue')}</span>
            </button>

            <button
                type="button"
                onClick={onSelectWeek}
                className="flex min-h-20 flex-col justify-center bg-card p-4 text-left outline-none transition hover:bg-muted focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-brand"
            >
                <span className="text-2xl font-semibold leading-none tabular-nums text-foreground">{doneThisWeek}</span>
                <span className="mt-1.5 text-xs font-medium text-muted-foreground">{t('doneThisWeek')}</span>
            </button>
        </div>
    );
}

function QueueButton({
    Icon,
    label,
    count,
    active,
    reduce,
    onClick,
}: {
    Icon: IconType;
    label: string;
    count: number;
    active: boolean;
    reduce: boolean;
    onClick: () => void;
}) {
    return (
        <button
            type="button"
            onClick={onClick}
            aria-current={active ? 'page' : undefined}
            className={cn(
                'relative flex w-full items-center justify-between rounded-lg px-3 py-2 text-sm transition-colors',
                active ? 'text-brand-dark' : 'text-foreground hover:bg-muted',
            )}
        >
            {active && (
                <motion.span
                    layoutId="task-queue-pill"
                    className="absolute inset-0 z-0 rounded-lg bg-brand-light/60"
                    transition={reduce ? { duration: 0 } : { type: 'spring', stiffness: 520, damping: 42 }}
                />
            )}
            <span className="relative z-10 flex min-w-0 items-center gap-2.5">
                <Icon className={cn('size-4 shrink-0', active ? 'text-brand-dark' : 'text-muted-foreground')} />
                <span className={cn('truncate', active && 'font-medium')}>{label}</span>
            </span>
            <span className={cn('relative z-10 shrink-0 text-xs tabular-nums', active ? 'text-brand-dark/70' : 'text-muted-foreground')}>
                {count}
            </span>
        </button>
    );
}

type TaskRowProps = {
    task: Task;
    reduce: boolean;
    checked: boolean;
    person?: Contact;
    deal?: Deal;
    assignee?: User;
    bucket: Bucket;
    onToggle: (next: boolean) => void;
    onOpen: () => void;
    pending: boolean;
    ariaCompleteLabel: string;
    ariaOpenLabel: string;
    due: { label: string; tone: DueTone } | null;
    menuModel: RecordMenuModel;
    tabIndex: number;
    rowRef: (row: HTMLButtonElement | null) => void;
    onFocus: () => void;
    onKeyDown: (event: KeyboardEvent<HTMLButtonElement>) => void;
};

function TaskRow({
    task,
    reduce,
    checked,
    person,
    deal,
    assignee,
    bucket,
    onToggle,
    onOpen,
    pending,
    ariaCompleteLabel,
    ariaOpenLabel,
    due,
    menuModel,
    tabIndex,
    rowRef,
    onFocus,
    onKeyDown,
}: TaskRowProps) {
    const isCompletedRow = bucket === 'completed';
    const touchContextRef = useRef(false);
    const suppressNextOpenRef = useRef(false);
    const handleRowPointerDown = (pointerType: string) => {
        suppressNextOpenRef.current = false;
        touchContextRef.current = pointerType !== 'mouse';
    };
    const handleRowOpen = () => {
        touchContextRef.current = false;
        if (suppressNextOpenRef.current) {
            suppressNextOpenRef.current = false;
            return;
        }
        onOpen();
    };

    return (
        <RecordContextMenu
            model={menuModel}
            onOpenChange={(open) => {
                if (open && touchContextRef.current) {
                    suppressNextOpenRef.current = true;
                } else if (!open) {
                    touchContextRef.current = false;
                    suppressNextOpenRef.current = false;
                }
            }}
        >
            <motion.li
                layout={!reduce}
                initial={false}
                exit={reduce ? { opacity: 0 } : { opacity: 0, x: 8, transition: { duration: 0.2, ease: EASE_OUT } }}
                transition={{ duration: 0.22, ease: EASE_OUT }}
                className={cn(
                    'group relative flex cursor-pointer items-center gap-3 px-5 py-3 transition-colors hover:bg-muted/80',
                    (checked || isCompletedRow) && 'opacity-55',
                )}
            >
                <button
                    ref={rowRef}
                    type="button"
                    tabIndex={tabIndex}
                    aria-label={ariaOpenLabel}
                    aria-keyshortcuts="Shift+F10"
                    className="absolute inset-0 z-0 outline-hidden focus-visible:outline-2 focus-visible:outline-solid focus-visible:-outline-offset-2 focus-visible:outline-brand"
                    onFocus={onFocus}
                    onKeyDown={onKeyDown}
                    onPointerDown={(event) => handleRowPointerDown(event.pointerType)}
                    onPointerCancel={() => {
                        touchContextRef.current = false;
                    }}
                    onClick={handleRowOpen}
                />

                <div
                    onClick={(event) => event.stopPropagation()}
                    onPointerDown={(event) => event.stopPropagation()}
                    className="relative z-10 shrink-0"
                >
                    <Checkbox
                        checked={checked}
                        onCheckedChange={(value) => onToggle(value === true)}
                        disabled={pending}
                        aria-label={ariaCompleteLabel}
                        className="size-[18px] rounded-full border-border transition data-[state=checked]:border-brand data-[state=checked]:bg-brand data-[state=checked]:text-brand-foreground"
                    />
                </div>

                <span
                    onPointerDown={(event) => {
                        if (event.target instanceof Element && event.target.closest('a')) {
                            event.stopPropagation();
                        }
                    }}
                    className={cn(
                        'pointer-events-none relative z-10 min-w-0 flex-1 truncate text-sm [&_a]:pointer-events-auto',
                        checked || isCompletedRow ? 'text-muted-foreground line-through' : 'text-foreground',
                    )}
                >
                    <NoteContent content={task.description} references={task.references} />
                </span>

                <div className="relative z-10 hidden shrink-0 items-center gap-1.5 sm:flex">
                    {person && (
                        <Link
                            href={`/records/contacts/${person.id}`}
                            onClick={(event) => event.stopPropagation()}
                            onPointerDown={(event) => event.stopPropagation()}
                            className="inline-flex max-w-40 items-center gap-1 rounded-full bg-brand-light/50 px-2 py-0.5 text-xs font-medium text-brand-dark ring-1 ring-inset ring-brand-dark/10 transition hover:bg-brand-light"
                            title={person.name}
                        >
                            <UserIcon className="size-3 shrink-0" />
                            <span className="truncate">{person.name}</span>
                        </Link>
                    )}
                    {deal && (
                        <Link
                            href={`/records/deals/${deal.id}`}
                            onClick={(event) => event.stopPropagation()}
                            onPointerDown={(event) => event.stopPropagation()}
                            className="inline-flex max-w-40 items-center gap-1 rounded-full bg-card px-2 py-0.5 text-xs font-medium text-foreground ring-1 ring-inset ring-border transition hover:bg-muted"
                            title={deal.name}
                        >
                            <BriefcaseIcon className="size-3 shrink-0" />
                            <span className="truncate">{deal.name}</span>
                        </Link>
                    )}
                </div>

                <div className="pointer-events-none relative z-10 w-18 shrink-0 text-right">
                    {due && !isCompletedRow ? (
                        <span
                            className={cn(
                                'inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium tabular-nums ring-1 ring-inset',
                                DUE_CHIP[due.tone],
                            )}
                        >
                            {due.label}
                        </span>
                    ) : null}
                </div>

                <button
                    type="button"
                    tabIndex={-1}
                    aria-label={ariaOpenLabel}
                    className="relative z-10 shrink-0"
                    onPointerDown={(event) => handleRowPointerDown(event.pointerType)}
                    onPointerCancel={() => {
                        touchContextRef.current = false;
                    }}
                    onClick={(event) => {
                        event.stopPropagation();
                        handleRowOpen();
                    }}
                >
                    {assignee ? (
                        <Tooltip>
                            <TooltipTrigger asChild>
                                <Avatar size="sm" className="ring-1 ring-border">
                                    {assignee.profilePictureUrl ? (
                                        <AvatarImage src={assignee.profilePictureUrl} alt={assignee.displayName} />
                                    ) : (
                                        <AvatarFallback>
                                            <UserIcon className="size-3 text-muted-foreground" />
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
                </button>

                <div
                    className="relative z-10 shrink-0"
                    onClick={(event) => event.stopPropagation()}
                    onPointerDown={(event) => event.stopPropagation()}
                >
                    <RecordActionMenuTrigger model={menuModel} triggerClassName="opacity-100" />
                </div>
            </motion.li>
        </RecordContextMenu>
    );
}

function TaskEmptyState({ filtered, completed, message }: { filtered: boolean; completed: boolean; message: string }) {
    const Icon = completed ? CheckCircleIcon : filtered ? MagnifyingGlassIcon : CheckIcon;
    return (
        <div className="rounded-2xl border border-border bg-card px-6 py-20 text-center">
            <div className="mx-auto flex size-14 items-center justify-center rounded-2xl bg-brand-light text-brand-dark">
                <Icon className="size-7" strokeWidth={1.75} />
            </div>
            <p className="mx-auto mt-5 max-w-sm text-sm font-medium text-foreground">{message}</p>
        </div>
    );
}
