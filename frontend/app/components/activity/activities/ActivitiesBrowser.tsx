'use client';

import { useCallback, useEffect, useMemo, useState, type ComponentType } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import { useLocale, useTranslations } from 'next-intl';
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
import { PageShell } from '@/app/components/PageShell';
import { deleteActivity, getActivityById } from '@/app/lib/api';
import { isProviderOwnedActivity } from '@/app/lib/connectedCapture';
import { parseDeepLinkId } from '@/app/hooks/listStateUrl';
import { useOwnedUrlParams } from '@/app/hooks/useOwnedUrlParams';
import { recordDetailNavigationPath } from '@/app/lib/recordReturnPath';
import { useRecordReturnScroll } from '@/app/hooks/useRecordReturnSelection';
import { useScopedViewPreference } from '@/app/hooks/useScopedViewPreference';
import { useWorkspace } from '@/app/hooks/useWorkspace';
import { toastError, toastSuccess } from '@/app/lib/toast';
import { noteContentToPlainText } from '@/app/lib/references';
import { parseMysqlDateTime } from '@/app/lib/utils';
import { cn } from '@/lib/utils';
import type { Activity, Contact, Deal, User } from '@/app/lib/types';

type Props = {
    activities: Activity[];
    persons: Contact[];
    deals: Deal[];
    users: User[];
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

function startOfDayKey(ts: number): number {
    const d = new Date(ts);
    d.setHours(0, 0, 0, 0);
    return d.getTime();
}

function isPlanned(a: Activity, now: number): boolean {
    const ts = activityTime(a);
    return ts !== -Infinity && startOfDayKey(ts) > startOfDayKey(now);
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

export default function ActivitiesBrowser({
    activities,
    persons,
    deals,
    users,
    currentUserId,
    originWorkspaceId,
}: Props) {
    const router = useRouter();
    const { activeWorkspaceId } = useWorkspace();
    const t = useTranslations('ActivityPage');
    const tf = useTranslations('Filters');
    const locale = useLocale();
    const reduce = useReducedMotion() ?? false;
    const [now] = useState(() => Date.now());

    const personById = useMemo(() => new Map(persons.map((p) => [p.id, p])), [persons]);
    const dealById = useMemo(() => new Map(deals.map((d) => [d.id, d])), [deals]);
    const userById = useMemo(() => new Map(users.map((u) => [u.id, u])), [users]);

    const [query, setQuery] = useState('');
    const [creatorFilter, setCreatorFilter] = useState<Set<string>>(new Set());
    const [personFilter, setPersonFilter] = useState<Set<string>>(new Set());
    const [dealFilter, setDealFilter] = useState<Set<string>>(new Set());
    const [companyFilter, setCompanyFilter] = useState<Set<string>>(new Set());
    const [filter, setFilter] = useScopedViewPreference<Filter>({
        storageKey: FILTER_STORAGE_KEY,
        userId: currentUserId,
        workspaceId: activeWorkspaceId,
        initialValue: null,
        fallback: 'all',
        isValue: isFilter,
    });
    const [editing, setEditing] = useState<Activity | null>(null);
    const [creating, setCreating] = useState(false);
    const [deleting, setDeleting] = useState<Activity | null>(null);
    const [isDeleting, setIsDeleting] = useState(false);
    const returnSnapshot = useRecordReturnScroll('activities', true);

    const searchParams = useSearchParams();
    const [deepLinkSettled, setDeepLinkSettled] = useState(
        () => parseDeepLinkId(searchParams.get('activity')) === null,
    );
    useEffect(() => {
        const activityId = parseDeepLinkId(searchParams.get('activity'));
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
    useOwnedUrlParams({ activity: editing ? String(editing.id) : undefined }, deepLinkSettled);

    const typeCounts = useMemo(() => {
        const counts: Record<Filter, number> = { all: 0, Call: 0, Email: 0, Meeting: 0, Note: 0, Other: 0 };
        for (const a of activities) {
            counts.all++;
            counts[normalizeType(a.type)]++;
        }
        return counts;
    }, [activities]);

    const weekPulse = useMemo(() => {
        const today = startOfDayKey(now);
        const days = Array.from({ length: 7 }, (_, i) => ({ key: today - (6 - i) * DAY_MS, count: 0 }));
        const index = new Map(days.map((d, i) => [d.key, i]));
        for (const a of activities) {
            const ts = activityTime(a);
            if (ts === -Infinity) continue;
            const i = index.get(startOfDayKey(ts));
            if (i != null) days[i].count++;
        }
        const total = days.reduce((sum, d) => sum + d.count, 0);
        const max = Math.max(1, ...days.map((d) => d.count));
        return { days, total, max, today };
    }, [activities, now]);

    const companyIdForActivity = useCallback(
        (a: Activity) => {
            if (a.personId == null) return null;
            const person = personById.get(a.personId);
            return person?.company?.id ?? person?.companyId ?? null;
        },
        [personById],
    );

    const typeActivities = useMemo(
        () => activities.filter((a) => filter === 'all' || normalizeType(a.type) === filter),
        [activities, filter],
    );

    const dimensionOptions = useMemo(() => {
        const creators = new Map<string, { label: string; count: number }>();
        const persons_ = new Map<string, { label: string; count: number }>();
        const deals_ = new Map<string, { label: string; count: number }>();
        const companies = new Map<string, { label: string; count: number }>();
        for (const a of typeActivities) {
            const creator = userById.get(a.createdById);
            if (creator) bumpOption(creators, String(a.createdById), creator.displayName || creator.username);
            if (a.personId != null) {
                const person = personById.get(a.personId);
                if (person) bumpOption(persons_, String(person.id), person.name);
            }
            if (a.dealId != null) {
                const deal = dealById.get(a.dealId);
                if (deal) bumpOption(deals_, String(deal.id), deal.name);
            }
            const companyId = companyIdForActivity(a);
            if (companyId != null) {
                const company = a.personId != null ? personById.get(a.personId)?.company : undefined;
                if (company) bumpOption(companies, String(companyId), company.name);
            }
        }
        return {
            creators: toOptions(creators),
            persons: toOptions(persons_),
            deals: toOptions(deals_),
            companies: toOptions(companies),
        };
    }, [typeActivities, userById, personById, dealById, companyIdForActivity]);

    const filtered = useMemo(() => {
        const q = query.trim().toLowerCase();
        return typeActivities
            .filter((a) => {
                if (creatorFilter.size && !creatorFilter.has(String(a.createdById))) return false;
                if (personFilter.size && !(a.personId != null && personFilter.has(String(a.personId)))) return false;
                if (dealFilter.size && !(a.dealId != null && dealFilter.has(String(a.dealId)))) return false;
                if (companyFilter.size) {
                    const companyId = companyIdForActivity(a);
                    if (!(companyId != null && companyFilter.has(String(companyId)))) return false;
                }
                if (!q) return true;
                const haystacks = [
                    a.subject,
                    a.notes ? noteContentToPlainText(a.notes) : null,
                    a.personId ? personById.get(a.personId)?.name : null,
                    a.dealId ? dealById.get(a.dealId)?.name : null,
                    userById.get(a.createdById)?.displayName,
                ];
                return haystacks.some((s) => s?.toLowerCase().includes(q));
            })
            .sort((a, b) => activityTime(b) - activityTime(a));
    }, [typeActivities, query, creatorFilter, personFilter, dealFilter, companyFilter, companyIdForActivity, personById, dealById, userById]);

    const groups = useMemo(() => {
        const today = startOfDayKey(now);
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
                const dayKey = startOfDayKey(ts);
                id = String(dayKey);
                sortKey = dayKey;
                const diffDays = Math.round((today - dayKey) / DAY_MS);
                if (diffDays === 0) label = t('groupToday');
                else if (diffDays === 1) label = t('groupYesterday');
                else if (diffDays === -1) label = t('groupTomorrow');
                else {
                    const sameYear = new Date(dayKey).getFullYear() === new Date(now).getFullYear();
                    label = new Intl.DateTimeFormat(locale, {
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
    }, [filtered, t, locale, now]);

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
            router.refresh();
        } catch (err) {
            toastError(err instanceof Error ? err.message : t('toastFailedDelete'));
        } finally {
            setIsDeleting(false);
        }
    };

    const formatTime = (a: Activity) => {
        const ts = activityTime(a);
        if (ts === -Infinity) return '';
        return new Intl.DateTimeFormat(locale, { hour: 'numeric', minute: '2-digit' }).format(new Date(ts));
    };

    const isEmpty = filtered.length === 0;
    const dimensionsActive =
        creatorFilter.size > 0 || personFilter.size > 0 || dealFilter.size > 0 || companyFilter.size > 0;
    const emptyMessage = query.trim() || filter !== 'all' || dimensionsActive ? t('emptyFiltered') : t('empty');
    const hasAny = activities.length > 0;

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
            <PageShell tier="wide">
                <Rise>
                    <header className="flex flex-wrap items-start justify-between gap-4">
                        <div>
                            <h1 className="text-4xl font-extrabold tracking-tight">{t('title')}</h1>
                            <p className="mt-1 text-sm text-muted-foreground">{t('subtitle')}</p>
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
                    </header>
                </Rise>

                {hasAny && (
                    <Rise delay={0.06}>
                        <WeekPulse pulse={weekPulse} reduce={reduce} t={t} />
                    </Rise>
                )}

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
                                        count={typeCounts[f]}
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
                                {dimensionOptions.creators.length > 0 && (
                                    <MultiSelectFilter
                                        label={tf('creator')}
                                        ariaLabel={tf('creator')}
                                        options={dimensionOptions.creators}
                                        selected={creatorFilter}
                                        onToggle={(v) => toggleInSet(setCreatorFilter, v)}
                                        onClear={() => setCreatorFilter(new Set())}
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
                                                    planned={isPlanned(activity, now)}
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
                        </div>
                    </div>
                </Rise>
            </PageShell>

            {editing && (
                <EditActivitySheet
                    activity={editing}
                    open={!!editing}
                    onOpenChange={(open) => {
                        if (!open) setEditing(null);
                    }}
                    persons={persons}
                    deals={deals}
                    originWorkspaceId={originWorkspaceId}
                />
            )}

            <ActivityDialog
                open={creating}
                onOpenChange={setCreating}
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

function WeekPulse({
    pulse,
    reduce,
    t,
}: {
    pulse: { days: { key: number; count: number }[]; total: number; max: number; today: number };
    reduce: boolean;
    t: ReturnType<typeof useTranslations>;
}) {
    return (
        <div className="flex items-center gap-5 rounded-2xl border border-border bg-card p-4">
            <div className="shrink-0">
                <div className="text-2xl font-semibold tabular-nums text-foreground">{pulse.total}</div>
                <div className="mt-0.5 text-xs font-medium text-muted-foreground">{t('weekTotalLabel')}</div>
            </div>
            <div
                className="ml-auto flex h-10 items-end gap-1.5"
                role="img"
                aria-label={t('weekPulseAria', { count: pulse.total })}
            >
                {pulse.days.map((day, i) => {
                    const isToday = day.key === pulse.today;
                    const height = Math.max(0.1, day.count / pulse.max) * 40;
                    return (
                        <motion.span
                            key={day.key}
                            className={cn('block w-2 rounded-full', isToday ? 'bg-brand' : 'bg-brand/35')}
                            style={{ height, transformOrigin: 'bottom' }}
                            initial={reduce ? false : { scaleY: 0 }}
                            animate={{ scaleY: 1 }}
                            transition={{ duration: reduce ? 0 : 0.5, delay: reduce ? 0 : i * 0.04, ease: EASE_OUT }}
                        />
                    );
                })}
            </div>
        </div>
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
    count: number;
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
                active ? 'text-brand-dark' : 'text-foreground hover:bg-muted',
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
            <span className={cn('relative z-10 shrink-0 text-xs tabular-nums', active ? 'text-brand-dark/70' : 'text-muted-foreground')}>
                {count}
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
                                className="inline-flex max-w-48 items-center gap-1 rounded-full bg-brand-light/50 px-2 py-0.5 text-xs font-medium text-brand-dark ring-1 ring-inset ring-brand-dark/10 transition hover:bg-brand-light"
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
            <div className="mx-auto flex size-14 items-center justify-center rounded-2xl bg-brand-light text-brand-dark">
                <Icon className="size-7" strokeWidth={1.75} />
            </div>
            <p className="mx-auto mt-5 max-w-sm text-sm font-medium text-foreground">{message}</p>
        </div>
    );
}
