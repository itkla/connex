'use client';

import { subscribeToRecordMutations } from '@/app/lib/record-mutation-events';

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
import { useTimeZone, useLocale, useTranslations } from 'next-intl';
import { usePathname, useSearchParams } from 'next/navigation';
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

import { useServerRecords } from '@/app/hooks/useServerRecords';
import { useActivityListFilters } from '@/app/hooks/useActivityListFilters';
import { useContactTargetSearch, useDealTargetSearch, useMemberTargetSearch } from '@/app/hooks/useRecordTargetSearch';
import { useCompanySearch } from '@/app/hooks/useCompanySearch';
import { RecordListState } from '@/app/components/records/RecordListState';
import ActivityListPagination from '@/app/components/activity/ActivityListPagination';
import { SearchField, FilterBar, MultiSelectFilter, type FilterChipData } from '@/app/components/filters';
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';
import { Checkbox } from '@/components/ui/checkbox';
import { Button } from '@/components/ui/button';
import { SegmentedControl } from '@/components/ui/segmented-control';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';

import DeleteRecordDialog from '@/app/components/records/DeleteRecordDialog';
import { EmptyState } from '@/app/components/EmptyState';
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
import { useLiveNow } from '@/app/hooks/useNow';
import { calendarDayInTimeZone } from '@/app/lib/timeZone';
import { parseCalendarDateUtc } from '@/app/lib/utils';
import { type DueTone, DUE_CHIP, formatDue } from '@/app/components/activity/tasks/taskDue';
import Rise from '@/app/components/motion/Rise';
import { PageHeader } from '@/app/components/PageHeader';
import { PageShell } from '@/app/components/PageShell';
import { deleteTask, getTaskById, updateTask, getTasksPage } from '@/app/lib/api';
import { parseDeepLinkId, TASK_URL_KEY } from '@/app/hooks/listStateUrl';
import { useOwnedUrlParams } from '@/app/hooks/useOwnedUrlParams';
import { useIsMobile } from '@/app/hooks/useIsMobile';
import { useScopedViewPreference } from '@/app/hooks/useScopedViewPreference';
import { effectiveListView } from '@/app/hooks/viewPreference';
import { useWorkspace } from '@/app/hooks/useWorkspace';
import { useApiErrorToast } from '@/app/hooks/useApiErrorToast';
import { toastSuccess } from '@/app/lib/toast';
import { noteSnippet } from '@/app/lib/noteText';
import { cn } from '@/lib/utils';
import type { ContactPickerOption, Deal, Task, User } from '@/app/lib/types';

type Props = {
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
const DAY_MS = 24 * 60 * 60 * 1000;

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

function dueTimestamp(task: Task): number {
    const ts = parseCalendarDateUtc(task.dueDate ?? '');
    return Number.isNaN(ts) ? Number.POSITIVE_INFINITY : ts;
}

function bucketForTask(task: Task, today: number): Bucket {
    if (task.completed) return 'completed';
    const ts = parseCalendarDateUtc(task.dueDate ?? '');
    if (Number.isNaN(ts)) return 'noDate';
    if (ts < today) return 'overdue';
    if (ts <= today + DAY_MS - 1) return 'today';
    return 'upcoming';
}

function isInQueue(queue: Queue, task: Task, currentUserId: number, today: number): boolean {
    const ts = parseCalendarDateUtc(task.dueDate ?? '');
    const hasDate = !Number.isNaN(ts);
    switch (queue) {
        case 'myOpen':
            return !task.completed && task.assignedToId === currentUserId;
        case 'dueToday':
            return !task.completed && hasDate && ts >= today && ts <= today + DAY_MS - 1;
        case 'overdue':
            return !task.completed && hasDate && ts < today;
        case 'unassigned':
            return !task.completed && (!task.assignedToId || task.assignedToId === 0);
        case 'allOpen':
            return !task.completed;
        case 'completed':
            return !!task.completed;
    }
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
    currentUserId,
    canDeleteTasks,
    originWorkspaceId,
}: Props) {
    const t = useTranslations('ActivityTasks');
    const tf = useTranslations('Filters');
    const showApiError = useApiErrorToast('ActivityTasks');
    const showPickerError = useApiErrorToast('ActivityList');
    const locale = useLocale();
    const timeZone = useTimeZone() ?? 'UTC';
    const pathname = usePathname() ?? '';
    const reduce = useReducedMotion() ?? false;
    const { activeWorkspaceId, switching } = useWorkspace();
    const isMobile = useIsMobile();
    const [originPathname] = useState(pathname);

    const now = useLiveNow();
    const today = parseCalendarDateUtc(calendarDayInTimeZone(now, timeZone));
    const searchParams = useSearchParams();
    const urlPreference = searchParams.get('queue');
    const listFilters = useActivityListFilters();
    const { personFilter, setPersonFilter, dealFilter, setDealFilter, companyFilter, setCompanyFilter } = listFilters;
    const [assigneeFilter, setAssigneeFilter] = [listFilters.memberFilter, listFilters.setMemberFilter];
    const [queue, setQueue] = useScopedViewPreference<Queue>({
        storageKey: QUEUE_STORAGE_KEY,
        userId: currentUserId,
        workspaceId: activeWorkspaceId,
        initialValue: isQueue(urlPreference) ? urlPreference : listFilters.hasRecordContext ? 'allOpen' : null,
        fallback: isQueue(urlPreference) ? urlPreference : listFilters.hasRecordContext ? 'allOpen' : 'myOpen',
        isValue: isQueue,
    });
    const [view, setView] = useScopedViewPreference<TaskView>({
        storageKey: VIEW_STORAGE_KEY,
        userId: currentUserId,
        workspaceId: activeWorkspaceId,
        initialValue: null,
        fallback: 'list',
        isValue: isTaskView,
    });
    const effectiveView: TaskView = effectiveListView(view, isMobile);
    const records = useServerRecords(getTasksPage, { ...listFilters.params, queue: effectiveView === 'board' ? 'all' : queue }, { urlSync: true, retainPreviousData: false, requestContext: String(today) });
    const tasks = records.items;
    const { query, setQuery, reload } = records;
    useEffect(() => subscribeToRecordMutations((entity) => {
        if (entity === 'task') reload();
    }), [reload]);
    const [editingTask, setEditingTask] = useState<Task | null>(null);
    const personSearch = useContactTargetSearch(true, [editingTask?.personId, ...records.items.map((item) => item.personId), ...personFilter].map((id) => id == null ? null : Number(id)));
    const dealSearch = useDealTargetSearch(true, [...records.items.map((item) => item.dealId), ...dealFilter].map((id) => id == null ? null : Number(id)));
    const memberSearch = useMemberTargetSearch(true, [currentUserId, ...records.items.map((item) => item.assignedToId), ...listFilters.memberFilter].map(Number));
    const companySearch = useCompanySearch(true, [...companyFilter].map(Number));
    const pickerError = personSearch.error ?? dealSearch.error ?? memberSearch.error ?? companySearch.error;
    useEffect(() => {
        if (pickerError != null) showPickerError(pickerError, 'pickerLoadFailed', 'pickerLoadFailedBody');
    }, [pickerError, showPickerError]);
    const persons = personSearch.contacts;
    const deals = dealSearch.deals;
    const users = memberSearch.users;
    const personById = useMemo(() => new Map(persons.map((p) => [p.id, p])), [persons]);
    const dealById = useMemo(() => new Map(deals.map((d) => [d.id, d])), [deals]);
    const userById = useMemo(() => new Map(users.map((u) => [u.id, u])), [users]);

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

    useOwnedUrlParams({ queue });
    const [deepLinkSettled, setDeepLinkSettled] = useState(
        () => parseDeepLinkId(searchParams.get(TASK_URL_KEY)) === null,
    );
    useEffect(() => {
        const taskId = parseDeepLinkId(searchParams.get(TASK_URL_KEY));
        if (taskId === null) return;
        getTaskById(taskId)
            .then(setEditingTask)
            .catch(() => {})
            .finally(() => setDeepLinkSettled(true));
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);
    useOwnedUrlParams(
        { [TASK_URL_KEY]: editingTask ? String(editingTask.id) : undefined },
        deepLinkSettled,
    );

    useEffect(() => () => timers.current.forEach((id) => window.clearTimeout(id)), []);

    const queueTasks = useMemo(
        () => tasks.filter((task) => isInQueue(queue, task, currentUserId, today) || completing.has(task.id)),
        [tasks, queue, currentUserId, today, completing],
    );

    const dimensionOptions = {
        assignees: users.map((u) => ({ value: String(u.id), label: u.displayName || u.username })),
        persons: persons.map((p) => ({ value: String(p.id), label: p.name })),
        deals: deals.map((d) => ({ value: String(d.id), label: d.name })),
        companies: companySearch.companies.map((c) => ({ value: String(c.id), label: c.name })),
    };

    const filtered = queueTasks;
    const boardTasks = tasks;

    const grouped = useMemo(() => {
        const buckets: Record<Bucket, Task[]> = { overdue: [], today: [], upcoming: [], noDate: [], completed: [] };
        for (const task of filtered) {
            const bucket = completing.has(task.id) ? bucketForTask({ ...task, completed: false }, today) : bucketForTask(task, today);
            buckets[bucket].push(task);
        }
        for (const key of [...ACTIVE_BUCKETS, 'completed' as const]) {
            buckets[key].sort((a, c) => dueTimestamp(a) - dueTimestamp(c));
        }
        return buckets;
    }, [filtered, completing, today]);

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
        records.patchItem(id, { completed: value });

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
        let requestSucceeded = false;
        let timerSettled = !(next && !reduce);

        const finishOperation = () => {
            if (!requestSettled || !timerSettled) return;
            if (toggleControllersRef.current.get(task.id) !== controller) return;
            toggleControllersRef.current.delete(task.id);
            if (scopeCurrent()) {
                if (requestSucceeded) records.reload();
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
            requestSucceeded = true;
        } catch (err) {
            if (controller.signal.aborted || !scopeCurrent()) return;
            if (optimisticTimerId !== null) {
                window.clearTimeout(optimisticTimerId);
                timers.current = timers.current.filter((id) => id !== optimisticTimerId);
                optimisticTimerId = null;
            }
            timerSettled = true;
            showApiError(err, 'toastFailedUpdate');
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
            setDeletingTask(null);
            toastSuccess(t('toastDeleted'));
            records.reload();
        } catch (error) {
            if (controller.signal.aborted || !scopeCurrent()) return;
            showApiError(error, 'toastFailedDelete');
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

    const isEmpty = !records.loading && filtered.length === 0;
    const isCompletedQueue = queue === 'completed';
    const dimensionsActive =
        assigneeFilter.size > 0 || personFilter.size > 0 || dealFilter.size > 0 || companyFilter.size > 0;
    const filteredEmpty = query.trim() !== '' || dimensionsActive;
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
    const taskEmptyState = filteredEmpty ? (
        <EmptyState
            tone="muted"
            icon={MagnifyingGlassIcon}
            title={t('emptyFilteredTitle')}
            body={t('emptyFiltered')}
            action={
                <Button variant="outline" onClick={clearAllFilters}>
                    {tf('clearAll')}
                </Button>
            }
        />
    ) : (
        <EmptyState
            tone="muted"
            icon={isCompletedQueue ? CheckCircleIcon : CheckIcon}
            title={t(`emptyQueue_${queue}` as 'emptyQueue_myOpen')}
            body={t('emptyQueueBody')}
            action={
                <Button variant="outline" onClick={() => setQueue(queue === 'allOpen' ? 'completed' : 'allOpen')}>
                    {queue === 'allOpen' ? t('emptyQueueShowCompleted') : t('emptyQueueShowAllOpen')}
                </Button>
            }
        />
    );
    const activeDimensionCount =
        assigneeFilter.size + personFilter.size + dealFilter.size + companyFilter.size;
    const taskFilterSections: TaskFilterSheetSection[] = [
        {
            label: tf('assignee'),
            options: dimensionOptions.assignees,
            search: { value: memberSearch.query, onChange: memberSearch.onInputValueChange, state: memberSearch.serverSearch },
            selected: assigneeFilter,
            onToggle: (value) => toggleInSet(setAssigneeFilter, value),
        },
        {
            label: tf('contact'),
            options: dimensionOptions.persons,
            search: { value: personSearch.query, onChange: personSearch.onInputValueChange, state: personSearch.serverSearch },
            selected: personFilter,
            onToggle: (value) => toggleInSet(setPersonFilter, value),
        },
        {
            label: tf('deal'),
            options: dimensionOptions.deals,
            search: { value: dealSearch.query, onChange: dealSearch.onInputValueChange, state: dealSearch.serverSearch },
            selected: dealFilter,
            onToggle: (value) => toggleInSet(setDealFilter, value),
        },
        {
            label: tf('company'),
            options: dimensionOptions.companies,
            search: { value: companySearch.query, onChange: companySearch.onInputValueChange, state: companySearch.serverSearch },
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
                due={formatDue(task.dueDate, t, locale, now, timeZone)}
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
        <>
            <PageShell>
                <Rise>
                    <PageHeader
                        title={t('title')}
                        description={t('subtitle')}
                        actions={
                            <>
                                <SegmentedControl
                                    ariaLabel={t('displayMode')}
                                    className="hidden md:inline-flex"
                                    value={view}
                                    onChange={setView}
                                    options={[
                                        { value: 'list', icon: <QueueListIcon className="size-4" />, ariaLabel: t('viewList') },
                                        { value: 'board', icon: <ViewColumnsIcon className="size-4" />, ariaLabel: t('viewBoard') },
                                    ]}
                                />
                                <Button
                                    variant="brand"
                                    size="page"
                                    aria-label={t('newAria')}
                                    onClick={() => setCreating(true)}
                                >
                                    <PlusIcon strokeWidth={2.5} />
                                    {t('new')}
                                </Button>
                            </>
                        }
                    />
                </Rise>

                <Rise delay={0.12}>
                    <ActivityListPagination page={records.page} size={records.size} total={records.total} loading={records.loading} onPageChange={records.setPage} />
                    {effectiveView === 'board' ? (
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
                                    />
                                }
                            />
                            <RecordListState error={records.error} loading={records.loading} hasRecords={tasks.length > 0} onRetry={records.reload}>
                            <TasksKanban
                                tasks={boardTasks}
                                personById={personById}
                                dealById={dealById}
                                userById={userById}
                                onMoved={records.reload}
                                onOpen={setEditingTask}
                                reduce={reduce}
                            />
                            </RecordListState>
                        </div>
                    ) : (
                        <div className="grid grid-cols-1 gap-6 xl:grid-cols-[200px_minmax(0,1fr)] xl:gap-10">
                            <aside className="xl:sticky xl:top-6 xl:self-start">
                                <h2 className="mb-2 px-3 text-xs font-medium uppercase tracking-[0.12em] text-muted-foreground">
                                    {t('queuesHeader')}
                                </h2>
                                <nav className="space-y-0.5">
                                    {ACTIVE_QUEUES.map((q) => (
                                        <QueueButton
                                            key={q}
                                            Icon={QUEUE_ICON[q]}
                                            label={t(`queue_${q}` as 'queue_myOpen')}
                                            count={queue === q ? records.total : undefined}
                                            active={queue === q}
                                            reduce={reduce}
                                            onClick={() => setQueue(q)}
                                        />
                                    ))}
                                    <div className="mx-3 my-3 h-px bg-border" />
                                    <QueueButton
                                        Icon={QUEUE_ICON.completed}
                                        label={t('queue_completed')}
                                        count={queue === 'completed' ? records.total : undefined}
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
                                    {(
                                        <MultiSelectFilter
                                            label={tf('assignee')}
                                            ariaLabel={tf('assignee')}
                                            options={dimensionOptions.assignees}
                                        search={{ value: memberSearch.query, onChange: memberSearch.onInputValueChange, state: memberSearch.serverSearch }}
                                            selected={assigneeFilter}
                                            onToggle={(v) => toggleInSet(setAssigneeFilter, v)}
                                            onClear={() => setAssigneeFilter(new Set())}
                                            clearLabel={tf('clear')}
                                            scroll
                                        />
                                    )}
                                    {(
                                        <MultiSelectFilter
                                            label={tf('contact')}
                                            ariaLabel={tf('contact')}
                                            options={dimensionOptions.persons}
                                        search={{ value: personSearch.query, onChange: personSearch.onInputValueChange, state: personSearch.serverSearch }}
                                            selected={personFilter}
                                            onToggle={(v) => toggleInSet(setPersonFilter, v)}
                                            onClear={() => setPersonFilter(new Set())}
                                            clearLabel={tf('clear')}
                                            scroll
                                        />
                                    )}
                                    {(
                                        <MultiSelectFilter
                                            label={tf('deal')}
                                            ariaLabel={tf('deal')}
                                            options={dimensionOptions.deals}
                                        search={{ value: dealSearch.query, onChange: dealSearch.onInputValueChange, state: dealSearch.serverSearch }}
                                            selected={dealFilter}
                                            onToggle={(v) => toggleInSet(setDealFilter, v)}
                                            onClear={() => setDealFilter(new Set())}
                                            clearLabel={tf('clear')}
                                            scroll
                                        />
                                    )}
                                    {(
                                        <MultiSelectFilter
                                            label={tf('company')}
                                            ariaLabel={tf('company')}
                                            options={dimensionOptions.companies}
                                        search={{ value: companySearch.query, onChange: companySearch.onInputValueChange, state: companySearch.serverSearch }}
                                            selected={companyFilter}
                                            onToggle={(v) => toggleInSet(setCompanyFilter, v)}
                                            onClear={() => setCompanyFilter(new Set())}
                                            clearLabel={tf('clear')}
                                            scroll
                                        />
                                    )}
                                </FilterBar>

                                <RecordListState error={records.error} loading={records.loading} hasRecords={tasks.length > 0} onRetry={records.reload}>
                                {isEmpty ? (
                                    taskEmptyState
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
                                </RecordListState>
                            </div>
                        </div>
                    )}
                </Rise>
            </PageShell>

            {editingTask && (
                <EditTaskSheet
                    task={editingTask}
                    open={!!editingTask}
                    onOpenChange={(open) => {
                        if (!open) { setEditingTask(null); records.reload(); }
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
        </>
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
    count?: number;
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
                active ? 'text-brand-text' : 'text-foreground hover:bg-muted',
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
            <span className={cn('relative z-10 shrink-0 text-xs tabular-nums', active ? 'text-brand-text/70' : 'text-muted-foreground')}>
                {count ?? ''}
            </span>
        </button>
    );
}

type TaskRowProps = {
    task: Task;
    reduce: boolean;
    checked: boolean;
    person?: ContactPickerOption;
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

                <div className="pointer-events-none relative z-10 min-w-0 flex-1">
                    <span
                        onPointerDown={(event) => {
                            if (event.target instanceof Element && event.target.closest('a')) {
                                event.stopPropagation();
                            }
                        }}
                        className={cn(
                            'pointer-events-none relative z-10 block min-w-0 line-clamp-2 break-words text-sm [overflow-wrap:anywhere] [&_a]:pointer-events-auto',
                            checked || isCompletedRow ? 'text-muted-foreground line-through' : 'text-foreground',
                        )}
                    >
                        <NoteContent content={task.description} references={task.references} />
                    </span>

                    <div className="relative z-10 mt-1 flex min-w-0 flex-wrap items-center gap-1.5">
                        {person && (
                            <Link
                                href={`/records/contacts/${person.id}`}
                                onClick={(event) => event.stopPropagation()}
                                onPointerDown={(event) => event.stopPropagation()}
                                className="pointer-events-auto inline-flex max-w-full items-center gap-1 rounded-full bg-brand-light/50 px-2 py-0.5 text-xs font-medium text-brand-text ring-1 ring-inset ring-brand-dark/10 transition hover:bg-brand-light"
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
                                className="pointer-events-auto inline-flex max-w-full items-center gap-1 rounded-full bg-card px-2 py-0.5 text-xs font-medium text-foreground ring-1 ring-inset ring-border transition hover:bg-muted"
                                title={deal.name}
                            >
                                <BriefcaseIcon className="size-3 shrink-0" />
                                <span className="truncate">{deal.name}</span>
                            </Link>
                        )}
                        <div className="pointer-events-none relative z-10 text-right">
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
                    </div>
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
