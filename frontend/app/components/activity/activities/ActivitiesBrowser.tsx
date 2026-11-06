'use client';

import { getDateTimeFormatter } from '@/app/lib/intlFormatters';
import { calendarDayInTimeZone } from '@/app/lib/timeZone';
import { parseCalendarDateUtc } from '@/app/lib/utils';
import { useEffect, useMemo, useState, type ComponentType } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import { useTimeZone, useLocale, useTranslations } from 'next-intl';
import Link from 'next/link';
import { AnimatePresence, motion, useReducedMotion } from 'motion/react';
import {
    MagnifyingGlassIcon,
    UserIcon,
    BriefcaseIcon,
    Squares2X2Icon,
    PencilIcon,
    TrashIcon,
    EllipsisHorizontalIcon,
    InboxStackIcon,
    ClockIcon,
} from '@heroicons/react/24/outline';
import { PlusIcon } from '@heroicons/react/24/solid';

import { useLiveNow } from '@/app/hooks/useNow';
import { useServerRecords } from '@/app/hooks/useServerRecords';
import { useActivityListFilters } from '@/app/hooks/useActivityListFilters';
import { useContactTargetSearch, useDealTargetSearch, useMemberTargetSearch } from '@/app/hooks/useRecordTargetSearch';
import { useCompanySearch } from '@/app/hooks/useCompanySearch';
import { RecordListState } from '@/app/components/records/RecordListState';
import ActivityListPagination from '@/app/components/activity/ActivityListPagination';
import { SearchField, FilterBar, MultiSelectFilter, type FilterChipData } from '@/app/components/filters';
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';
import { Button } from '@/components/ui/button';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import {
    DropdownMenu,
    DropdownMenuTrigger,
    DropdownMenuContent,
    DropdownMenuItem,
    DropdownMenuSeparator,
} from '@/components/ui/dropdown-menu';

import EditActivitySheet from '@/app/components/activity/activities/EditActivitySheet';
import ActivityDialog from '@/app/components/activity/activities/ActivityDialog';
import NoteContent from '@/app/components/activity/notes/NoteContent';
import ProviderCaptureEvidence from '@/app/components/activity/ProviderCaptureEvidence';
import DeleteRecordDialog from '@/app/components/records/DeleteRecordDialog';
import Rise from '@/app/components/motion/Rise';
import { ACTIVITY_TYPES, TYPE_META, normalizeType, type ActivityType } from '@/app/components/activity/activities/activityTypes';
import { PageHeader } from '@/app/components/PageHeader';
import { PageShell } from '@/app/components/PageShell';
import { deleteActivity, getActivityById, getActivitiesPage } from '@/app/lib/api';
import { isProviderOwnedActivity } from '@/app/lib/connectedCapture';
import { ACTIVITY_URL_KEY, parseDeepLinkId } from '@/app/hooks/listStateUrl';
import { useOwnedUrlParams } from '@/app/hooks/useOwnedUrlParams';
import { recordDetailNavigationPath } from '@/app/lib/recordReturnPath';
import { useRecordReturnScroll } from '@/app/hooks/useRecordReturnSelection';
import { useScopedViewPreference } from '@/app/hooks/useScopedViewPreference';
import { useWorkspace } from '@/app/hooks/useWorkspace';
import { useApiErrorToast } from '@/app/hooks/useApiErrorToast';
import { toastSuccess } from '@/app/lib/toast';
import { parseMysqlDateTime } from '@/app/lib/utils';
import { cn } from '@/lib/utils';
import type { Activity, Contact, Deal, User } from '@/app/lib/types';

type Props = {
    currentUserId: number;
    originWorkspaceId: number | null;
};

type IconType = ComponentType<{ className?: string }>;
type Filter = 'all' | ActivityType;

const FILTERS: Filter[] = ['all', ...ACTIVITY_TYPES];
const FILTER_STORAGE_KEY = 'activities:filter';
const EASE_OUT: [number, number, number, number] = [0.23, 1, 0.32, 1];
const DAY_MS = 24 * 60 * 60 * 1000;
const NODE_ANCHOR = 18;
const DATE_ANCHOR = 18;

type FeedEntry =
    | { kind: 'date'; id: string; label: string; count: number }
    | { kind: 'activity'; id: string; activity: Activity };

function isFilter(value: unknown): value is Filter {
    return typeof value === 'string' && (FILTERS as string[]).includes(value);
}

function activityTime(a: Activity): number {
    const ts = parseMysqlDateTime(a.timestamp);
    return Number.isNaN(ts) ? -Infinity : ts;
}

function startOfDayKey(ts: number, timeZone: string): number {
    return parseCalendarDateUtc(calendarDayInTimeZone(ts, timeZone));
}

function isPlanned(a: Activity, now: number, timeZone: string): boolean {
    const ts = activityTime(a);
    return ts !== -Infinity && startOfDayKey(ts, timeZone) > startOfDayKey(now, timeZone);
}

function toggleInSet(setter: React.Dispatch<React.SetStateAction<Set<string>>>, value: string) {
    setter((prev) => {
        const next = new Set(prev);
        if (next.has(value)) next.delete(value);
        else next.add(value);
        return next;
    });
}

export default function ActivitiesBrowser({
    currentUserId,
    originWorkspaceId,
}: Props) {
    const router = useRouter();
    const { activeWorkspaceId } = useWorkspace();
    const t = useTranslations('ActivityPage');
    const tf = useTranslations('Filters');
    const showApiError = useApiErrorToast('ActivityPage');
    const showPickerError = useApiErrorToast('ActivityList');
    const locale = useLocale();
    const timeZone = useTimeZone() ?? 'UTC';
    const reduce = useReducedMotion() ?? false;
    const now = useLiveNow();

    const searchParams = useSearchParams();
    const urlPreference = searchParams.get('type');
    const listFilters = useActivityListFilters();
    const { personFilter, setPersonFilter, dealFilter, setDealFilter, companyFilter, setCompanyFilter } = listFilters;
    const [creatorFilter, setCreatorFilter] = [listFilters.memberFilter, listFilters.setMemberFilter];
    const [filter, setFilter] = useScopedViewPreference<Filter>({
        storageKey: FILTER_STORAGE_KEY,
        userId: currentUserId,
        workspaceId: activeWorkspaceId,
        initialValue: isFilter(urlPreference) ? urlPreference : listFilters.hasRecordContext ? 'all' : null,
        fallback: 'all',
        isValue: isFilter,
    });
    const records = useServerRecords(getActivitiesPage, { ...listFilters.params, type: filter }, { urlSync: true, retainPreviousData: false });
    const activities = records.items;
    const { query, setQuery } = records;
    const personSearch = useContactTargetSearch(true, [...records.items.map((item) => item.personId), ...personFilter].map((id) => id == null ? null : Number(id)));
    const dealSearch = useDealTargetSearch(true, [...records.items.map((item) => item.dealId), ...dealFilter].map((id) => id == null ? null : Number(id)));
    const memberSearch = useMemberTargetSearch(true, [currentUserId, ...records.items.map((item) => item.createdById), ...listFilters.memberFilter].map(Number));
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

    const [editing, setEditing] = useState<Activity | null>(null);
    const [creating, setCreating] = useState(false);
    const [deleting, setDeleting] = useState<Activity | null>(null);
    const [isDeleting, setIsDeleting] = useState(false);
    const returnSnapshot = useRecordReturnScroll('activities', true);

    useOwnedUrlParams({ type: filter });
    const [deepLinkSettled, setDeepLinkSettled] = useState(
        () => parseDeepLinkId(searchParams.get(ACTIVITY_URL_KEY)) === null,
    );
    useEffect(() => {
        const activityId = parseDeepLinkId(searchParams.get(ACTIVITY_URL_KEY));
        if (activityId === null) return;
        getActivityById(activityId)
            .then((activity) => {
                if (!isProviderOwnedActivity(activity)) {
                    setEditing(activity);
                }
            })
            .catch(() => {})
            .finally(() => setDeepLinkSettled(true));
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);
    useOwnedUrlParams(
        { [ACTIVITY_URL_KEY]: editing ? String(editing.id) : undefined },
        deepLinkSettled,
    );

    const dimensionOptions = {
        creators: users.map((u) => ({ value: String(u.id), label: u.displayName || u.username })),
        persons: persons.map((p) => ({ value: String(p.id), label: p.name })),
        deals: deals.map((d) => ({ value: String(d.id), label: d.name })),
        companies: companySearch.companies.map((c) => ({ value: String(c.id), label: c.name })),
    };

    const filtered = activities;

    const groups = useMemo(() => {
        const today = startOfDayKey(now, timeZone);
        const map = new Map<string, { id: string; label: string; sortKey: number; items: Activity[] }>();
        for (const a of filtered) {
            const ts = activityTime(a);
            let id: string;
            let label: string;
            let sortKey: number;
            if (ts === -Infinity) {
                id = '__nodate';
                label = t('groupNoDate');
                sortKey = -Infinity;
            } else {
                const dayKey = startOfDayKey(ts, timeZone);
                id = String(dayKey);
                sortKey = dayKey;
                const diffDays = Math.round((today - dayKey) / DAY_MS);
                if (diffDays === 0) label = t('groupToday');
                else if (diffDays === 1) label = t('groupYesterday');
                else if (diffDays === -1) label = t('groupTomorrow');
                else {
                    const sameYear = calendarDayInTimeZone(ts, timeZone).slice(0, 4) === calendarDayInTimeZone(now, timeZone).slice(0, 4);
                    label = getDateTimeFormatter(locale, {
                        timeZone: 'UTC',
                        weekday: 'long',
                        month: 'long',
                        day: 'numeric',
                        ...(sameYear ? {} : { year: 'numeric' }),
                    }).format(new Date(dayKey));
                }
            }
            if (!map.has(id)) map.set(id, { id, label, sortKey, items: [] });
            map.get(id)!.items.push(a);
        }
        return Array.from(map.values()).sort((a, b) => b.sortKey - a.sortKey);
    }, [filtered, t, locale, now, timeZone]);

    const entries = useMemo<FeedEntry[]>(() => {
        const out: FeedEntry[] = [];
        for (const g of groups) {
            out.push({ kind: 'date', id: `date-${g.id}`, label: g.label, count: g.items.length });
            for (const a of g.items) out.push({ kind: 'activity', id: `act-${a.id}`, activity: a });
        }
        return out;
    }, [groups]);

    const confirmDelete = async () => {
        if (!deleting) return;
        setIsDeleting(true);
        try {
            await deleteActivity(deleting.id);
            toastSuccess(t('toastDeleted'));
            setDeleting(null);
            records.reload();
        } catch (err) {
            showApiError(err, 'toastFailedDelete');
        } finally {
            setIsDeleting(false);
        }
    };

    const formatTime = (a: Activity) => {
        const ts = activityTime(a);
        if (ts === -Infinity) return '';
        return getDateTimeFormatter(locale, { hour: 'numeric', minute: '2-digit', timeZone }).format(new Date(ts));
    };

    const isEmpty = filtered.length === 0;
    const dimensionsActive =
        creatorFilter.size > 0 || personFilter.size > 0 || dealFilter.size > 0 || companyFilter.size > 0;
    const emptyMessage = query.trim() || filter !== 'all' || dimensionsActive ? t('emptyFiltered') : t('empty');

    const labelFor = (options: { value: string; label: string }[], value: string) =>
        options.find((o) => o.value === value)?.label ?? value;
    const chips: FilterChipData[] = [
        ...(query.trim() ? [{ id: 'q', label: tf('chipSearch', { query: query.trim() }), onRemove: () => setQuery('') }] : []),
        ...[...creatorFilter].map((v) => ({ id: `creator-${v}`, label: labelFor(dimensionOptions.creators, v), onRemove: () => toggleInSet(setCreatorFilter, v) })),
        ...[...personFilter].map((v) => ({ id: `person-${v}`, label: labelFor(dimensionOptions.persons, v), onRemove: () => toggleInSet(setPersonFilter, v) })),
        ...[...dealFilter].map((v) => ({ id: `deal-${v}`, label: labelFor(dimensionOptions.deals, v), onRemove: () => toggleInSet(setDealFilter, v) })),
        ...[...companyFilter].map((v) => ({ id: `company-${v}`, label: labelFor(dimensionOptions.companies, v), onRemove: () => toggleInSet(setCompanyFilter, v) })),
    ];
    const clearAllFilters = () => {
        setQuery('');
        setCreatorFilter(new Set());
        setPersonFilter(new Set());
        setDealFilter(new Set());
        setCompanyFilter(new Set());
    };

    return (
        <>
            <PageShell>
                <Rise>
                    <PageHeader
                        title={t('title')}
                        description={t('subtitle')}
                        actions={
                            <Button
                                variant="brand"
                                className="shadow-sm transition-transform active:scale-[0.98]"
                                aria-label={t('newAria')}
                                onClick={() => setCreating(true)}
                            >
                                <PlusIcon strokeWidth={2.5} />
                                {t('new')}
                            </Button>
                        }
                    />
                </Rise>

                <Rise delay={0.12}>
                    <div className="grid grid-cols-1 gap-6 md:grid-cols-[200px_minmax(0,1fr)] md:gap-10">
                        <aside className="md:sticky md:top-6 md:self-start">
                            <h2 className="mb-2 px-3 text-xs font-medium uppercase tracking-[0.12em] text-muted-foreground">
                                {t('filtersHeader')}
                            </h2>
                            <nav className="space-y-0.5">
                                {FILTERS.map((f) => (
                                    <FilterButton
                                        key={f}
                                        Icon={f === 'all' ? Squares2X2Icon : TYPE_META[f].Icon}
                                        tint={f === 'all' ? undefined : TYPE_META[f].chip}
                                        label={f === 'all' ? t('filterAll') : t(`type${f}` as 'typeCall')}
                                        count={filter === f ? records.total : undefined}
                                        active={filter === f}
                                        reduce={reduce}
                                        onClick={() => setFilter(f)}
                                    />
                                ))}
                            </nav>
                        </aside>

                        <div className="min-w-0 space-y-5">
                            <FilterBar
                                reduce={reduce}
                                chips={chips}
                                hasActiveFilters={query.trim() !== '' || dimensionsActive}
                                onClearAll={clearAllFilters}
                                clearAllLabel={tf('clearAll')}
                                className="py-0"
                                search={
                                    <SearchField
                                        value={query}
                                        onChange={setQuery}
                                        onClear={() => setQuery('')}
                                        placeholder={t('searchPlaceholder')}
                                        searchAria={tf('searchAria')}
                                        clearAria={tf('clearSearchAria')}
                                    />
                                }
                            >
                                {(
                                    <MultiSelectFilter
                                        label={tf('creator')}
                                        ariaLabel={tf('creator')}
                                        options={dimensionOptions.creators}
                                        search={{ value: memberSearch.query, onChange: memberSearch.onInputValueChange, state: memberSearch.serverSearch }}
                                        selected={creatorFilter}
                                        onToggle={(v) => toggleInSet(setCreatorFilter, v)}
                                        onClear={() => setCreatorFilter(new Set())}
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
                            <ActivityListPagination page={records.page} size={records.size} total={records.total} loading={records.loading} onPageChange={records.setPage} />
                            <RecordListState error={records.error} loading={records.loading} hasRecords={activities.length > 0} onRetry={records.reload}>
                            {records.loading && !activities.length ? null : isEmpty ? (
                                <ActivityEmptyState filtered={!!query.trim() || filter !== 'all'} message={emptyMessage} />
                            ) : (
                                <ul className="relative">
                                    <AnimatePresence initial={false} mode="popLayout">
                                        {entries.map((entry, i) => {
                                            const connectUp = i > 0;
                                            const connectDown = i < entries.length - 1;
                                            if (entry.kind === 'date') {
                                                return (
                                                    <DateMarker
                                                        key={entry.id}
                                                        reduce={reduce}
                                                        label={entry.label}
                                                        count={entry.count}
                                                        connectUp={connectUp}
                                                        connectDown={connectDown}
                                                    />
                                                );
                                            }
                                            const activity = entry.activity;
                                            return (
                                                <TimelineRow
                                                    key={entry.id}
                                                    activity={activity}
                                                    reduce={reduce}
                                                    connectUp={connectUp}
                                                    connectDown={connectDown}
                                                    person={activity.personId ? personById.get(activity.personId) : undefined}
                                                    deal={activity.dealId ? dealById.get(activity.dealId) : undefined}
                                                    creator={userById.get(activity.createdById)}
                                                    time={formatTime(activity)}
                                                    typeLabel={t(`type${normalizeType(activity.type)}` as 'typeCall')}
                                                    planned={isPlanned(activity, now, timeZone)}
                                                    plannedLabel={t('planned')}
                                                    onOpen={() => {
                                                        if (isProviderOwnedActivity(activity)) {
                                                            router.push(recordDetailNavigationPath(
                                                                'activities',
                                                                activity.id,
                                                                returnSnapshot,
                                                            ));
                                                        } else {
                                                            setEditing(activity);
                                                        }
                                                    }}
                                                    onEdit={() => setEditing(activity)}
                                                    onDelete={() => setDeleting(activity)}
                                                    editLabel={t('edit')}
                                                    deleteLabel={t('delete')}
                                                    actionsAria={t('actionsAria')}
                                                />
                                            );
                                        })}
                                    </AnimatePresence>
                                </ul>
                            )}
                            </RecordListState>
                        </div>
                    </div>
                </Rise>
            </PageShell>

            {editing && (
                <EditActivitySheet
                    activity={editing}
                    open={!!editing}
                    onOpenChange={(open) => {
                        if (!open) { setEditing(null); records.reload(); }
                    }}
                    persons={persons}
                    deals={deals}
                    originWorkspaceId={originWorkspaceId}
                />
            )}

            <ActivityDialog
                open={creating}
                onOpenChange={(open) => { setCreating(open); if (!open) records.reload(); }}
                persons={persons}
                deals={deals}
                currentUserId={currentUserId}
            />

            <DeleteRecordDialog
                open={deleting !== null}
                onOpenChange={(open) => {
                    if (!open) setDeleting(null);
                }}
                selectedIds={new Set(deleting ? [deleting.id] : [])}
                selectedItems={deleting ? [deleting] : []}
                entityLabel={t('entityLabel')}
                getDisplayName={(a) => a.subject}
                isDeleting={isDeleting}
                confirmDelete={confirmDelete}
            />
        </>
    );
}

function FilterButton({
    Icon,
    tint,
    label,
    count,
    active,
    reduce,
    onClick,
}: {
    Icon: IconType;
    tint?: string;
    label: string;
    count?: number;
    active: boolean;
    reduce: boolean;
    onClick: () => void;
}) {
    const iconColor = tint ? tint.split(' ').find((c) => c.startsWith('text-')) : undefined;
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
                    layoutId="activity-filter-pill"
                    className="absolute inset-0 z-0 rounded-lg bg-brand-light/60"
                    transition={reduce ? { duration: 0 } : { type: 'spring', stiffness: 520, damping: 42 }}
                />
            )}
            <span className="relative z-10 flex min-w-0 items-center gap-2.5">
                <Icon className={cn('size-4 shrink-0', iconColor ?? (active ? 'text-brand-dark' : 'text-muted-foreground'))} />
                <span className={cn('truncate', active && 'font-medium')}>{label}</span>
            </span>
            <span className={cn('relative z-10 shrink-0 text-xs tabular-nums', active ? 'text-brand-text/70' : 'text-muted-foreground')}>
                {count ?? ''}
            </span>
        </button>
    );
}

type TimelineRowProps = {
    activity: Activity;
    reduce: boolean;
    connectUp: boolean;
    connectDown: boolean;
    person?: Contact;
    deal?: Deal;
    creator?: User;
    time: string;
    planned: boolean;
    plannedLabel: string;
    onOpen: () => void;
    onEdit: () => void;
    onDelete: () => void;
    typeLabel: string;
    editLabel: string;
    deleteLabel: string;
    actionsAria: string;
};

function TimelineRow({
    activity,
    reduce,
    connectUp,
    connectDown,
    person,
    deal,
    creator,
    time,
    planned,
    plannedLabel,
    onOpen,
    onEdit,
    onDelete,
    typeLabel,
    editLabel,
    deleteLabel,
    actionsAria,
}: TimelineRowProps) {
    const meta = TYPE_META[normalizeType(activity.type)];
    const Icon = meta.Icon;
    const creatorName = creator?.displayName || creator?.username || '';
    const providerOwned = isProviderOwnedActivity(activity);

    return (
        <motion.li
            layout={!reduce}
            initial={false}
            exit={reduce ? { opacity: 0 } : { opacity: 0, x: 8, transition: { duration: 0.2, ease: EASE_OUT } }}
            transition={{ duration: 0.22, ease: EASE_OUT }}
            className="grid grid-cols-[2rem_minmax(0,1fr)] gap-3"
        >
            <div className="relative flex flex-col items-center">
                <span
                    aria-hidden
                    className="absolute left-1/2 w-px -translate-x-1/2 bg-border"
                    style={{
                        top: connectUp ? 0 : NODE_ANCHOR,
                        bottom: connectDown ? 0 : `calc(100% - ${NODE_ANCHOR}px)`,
                    }}
                />
                <span
                    className={cn(
                        'relative z-10 mt-0.5 flex size-8 items-center justify-center rounded-full ring-1 ring-inset',
                        meta.chip,
                        planned && 'outline-1 outline-dashed outline-offset-2 outline-muted-foreground/40',
                    )}
                    aria-hidden
                >
                    <Icon className="size-4" />
                </span>
            </div>

            <div className="pb-6">
                <div className="group rounded-xl px-3 py-2 transition-colors hover:bg-muted">
                <div className="flex items-start gap-2">
                    <button
                        type="button"
                        className="min-w-0 flex-1 rounded-md text-left outline-none focus-visible:ring-2 focus-visible:ring-ring/50"
                        onClick={onOpen}
                    >
                        <p className="truncate text-sm font-medium text-foreground">{activity.subject}</p>
                        <div className="mt-0.5 flex items-center gap-1.5">
                            <span className="text-xs text-muted-foreground">{typeLabel}</span>
                            {planned && (
                                <span className="inline-flex items-center gap-1 rounded-full border border-dashed border-muted-foreground/40 px-1.5 py-px text-[10px] font-medium text-muted-foreground">
                                    <ClockIcon className="size-3 shrink-0" aria-hidden />
                                    {plannedLabel}
                                </span>
                            )}
                        </div>
                    </button>
                    <div className="flex shrink-0 items-center gap-0.5">
                        <span className="text-xs tabular-nums text-muted-foreground">{time}</span>
                        {!providerOwned ? (
                            <DropdownMenu>
                                <DropdownMenuTrigger asChild>
                                    <button
                                        type="button"
                                        aria-label={actionsAria}
                                        onClick={(e) => e.stopPropagation()}
                                        className="flex size-7 shrink-0 items-center justify-center rounded-full text-muted-foreground opacity-0 transition hover:bg-muted hover:text-foreground group-hover:opacity-100 focus:opacity-100 aria-expanded:opacity-100 data-[state=open]:opacity-100"
                                    >
                                        <EllipsisHorizontalIcon className="size-4" />
                                    </button>
                                </DropdownMenuTrigger>
                                <DropdownMenuContent align="end" side="bottom" className="w-40" onClick={(e) => e.stopPropagation()}>
                                    <DropdownMenuItem
                                        onSelect={(e) => {
                                            e.preventDefault();
                                            onEdit();
                                        }}
                                    >
                                        <PencilIcon className="size-4 text-muted-foreground" />
                                        {editLabel}
                                    </DropdownMenuItem>
                                    <DropdownMenuSeparator />
                                    <DropdownMenuItem
                                        className="text-destructive hover:bg-destructive/10"
                                        onSelect={(e) => {
                                            e.preventDefault();
                                            onDelete();
                                        }}
                                    >
                                        <TrashIcon className="size-4 text-destructive" />
                                        {deleteLabel}
                                    </DropdownMenuItem>
                                </DropdownMenuContent>
                            </DropdownMenu>
                        ) : null}
                    </div>
                </div>

                {activity.notes ? (
                    <p className="mt-1 line-clamp-2 text-sm text-muted-foreground"><NoteContent content={activity.notes} references={activity.references} /></p>
                ) : null}

                {activity.captureEvidence ? (
                    <ProviderCaptureEvidence evidence={activity.captureEvidence} compact />
                ) : null}

                {(person || deal || creator) && (
                    <div className="mt-2 flex flex-wrap items-center gap-1.5">
                        {person && (
                            <Link
                                href={`/records/contacts/${person.id}`}
                                onClick={(e) => e.stopPropagation()}
                                className="inline-flex max-w-48 items-center gap-1 rounded-full bg-brand-light/50 px-2 py-0.5 text-xs font-medium text-brand-text ring-1 ring-inset ring-brand-dark/10 transition hover:bg-brand-light"
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
                                className="inline-flex max-w-48 items-center gap-1 rounded-full bg-card px-2 py-0.5 text-xs font-medium text-foreground ring-1 ring-inset ring-border transition hover:bg-muted"
                                title={deal.name}
                            >
                                <BriefcaseIcon className="size-3 shrink-0" />
                                <span className="truncate">{deal.name}</span>
                            </Link>
                        )}
                        {creator && (
                            <Tooltip>
                                <TooltipTrigger asChild>
                                    <span className="ml-auto inline-flex" onClick={(e) => e.stopPropagation()}>
                                        <Avatar size="sm" className="ring-1 ring-border">
                                            {creator.profilePictureUrl ? (
                                                <AvatarImage src={creator.profilePictureUrl} alt={creatorName} />
                                            ) : (
                                                <AvatarFallback>
                                                    <UserIcon className="size-3 text-muted-foreground" />
                                                </AvatarFallback>
                                            )}
                                        </Avatar>
                                    </span>
                                </TooltipTrigger>
                                <TooltipContent side="bottom" align="end">
                                    {creatorName}
                                </TooltipContent>
                            </Tooltip>
                        )}
                    </div>
                )}
                </div>
            </div>
        </motion.li>
    );
}

function DateMarker({
    reduce,
    label,
    count,
    connectUp,
    connectDown,
}: {
    reduce: boolean;
    label: string;
    count: number;
    connectUp: boolean;
    connectDown: boolean;
}) {
    return (
        <motion.li
            layout={!reduce}
            initial={false}
            transition={{ duration: 0.22, ease: EASE_OUT }}
            className="grid grid-cols-[2rem_minmax(0,1fr)] gap-3"
        >
            <div className="relative flex flex-col items-center">
                <span
                    aria-hidden
                    className="absolute left-1/2 w-px -translate-x-1/2 bg-border"
                    style={{
                        top: connectUp ? 0 : DATE_ANCHOR,
                        bottom: connectDown ? 0 : `calc(100% - ${DATE_ANCHOR}px)`,
                    }}
                />
                <span aria-hidden className="relative z-10 mt-3.5 size-2 rounded-full bg-muted-foreground ring-4 ring-background" />
            </div>
            <div className="flex items-center gap-2 pt-2 pb-2">
                <h3 className="text-sm font-semibold text-foreground">{label}</h3>
                <span className="text-xs tabular-nums text-muted-foreground">{count}</span>
            </div>
        </motion.li>
    );
}

function ActivityEmptyState({ filtered, message }: { filtered: boolean; message: string }) {
    const Icon = filtered ? MagnifyingGlassIcon : InboxStackIcon;
    return (
        <div className="rounded-2xl border border-border bg-card px-6 py-20 text-center">
            <div className="mx-auto flex size-14 items-center justify-center rounded-2xl bg-brand-light text-brand-text">
                <Icon className="size-7" strokeWidth={1.75} />
            </div>
            <p className="mx-auto mt-5 max-w-sm text-sm font-medium text-foreground">{message}</p>
        </div>
    );
}
