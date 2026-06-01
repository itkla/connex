'use client';

import { useEffect, useMemo, useState, type ComponentType } from 'react';
import { useRouter } from 'next/navigation';
import { useLocale, useTranslations } from 'next-intl';
import Link from 'next/link';
import { toastError, toastSuccess } from '@/app/lib/toast';
import {
    MagnifyingGlassIcon,
    UserIcon,
    BriefcaseIcon,
    PhoneIcon,
    EnvelopeIcon,
    UserGroupIcon,
    PencilSquareIcon,
    SparklesIcon,
    Squares2X2Icon,
    PencilIcon,
    TrashIcon,
    EllipsisHorizontalIcon,
} from '@heroicons/react/24/outline';
import { PlusIcon } from '@heroicons/react/24/solid';

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
import DeleteRecordDialog from '@/app/components/records/DeleteRecordDialog';
import { deleteActivity } from '@/app/lib/api';
import { parseMysqlDateTime } from '@/app/lib/utils';
import type { Activity, Contact, Deal, User } from '@/app/lib/types';

type Props = {
    activities: Activity[];
    persons: Contact[];
    deals: Deal[];
    users: User[];
    currentUserId: number;
};

type IconType = ComponentType<{ className?: string }>;
type ActivityType = 'Call' | 'Email' | 'Meeting' | 'Note' | 'Other';
type Filter = 'all' | ActivityType;

const ACTIVITY_TYPES: ActivityType[] = ['Call', 'Email', 'Meeting', 'Note', 'Other'];
const FILTERS: Filter[] = ['all', ...ACTIVITY_TYPES];
const FILTER_STORAGE_KEY = 'activities:filter';

const TYPE_META: Record<ActivityType, { Icon: IconType; chip: string }> = {
    Call: { Icon: PhoneIcon, chip: 'bg-emerald-50 text-emerald-600 ring-emerald-600/10' },
    Email: { Icon: EnvelopeIcon, chip: 'bg-sky-50 text-sky-600 ring-sky-600/10' },
    Meeting: { Icon: UserGroupIcon, chip: 'bg-violet-50 text-violet-600 ring-violet-600/10' },
    Note: { Icon: PencilSquareIcon, chip: 'bg-amber-50 text-amber-600 ring-amber-600/10' },
    Other: { Icon: SparklesIcon, chip: 'bg-neutral-100 text-neutral-500 ring-black/5' },
};

const FILTER_ICON: Record<Filter, IconType> = {
    all: Squares2X2Icon,
    Call: PhoneIcon,
    Email: EnvelopeIcon,
    Meeting: UserGroupIcon,
    Note: PencilSquareIcon,
    Other: SparklesIcon,
};

function normalizeType(value?: string | null): ActivityType {
    if (!value) return 'Other';
    const match = ACTIVITY_TYPES.find((t) => t.toLowerCase() === value.toLowerCase());
    return match ?? 'Other';
}

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

function todayKey(): number {
    const d = new Date();
    d.setHours(0, 0, 0, 0);
    return d.getTime();
}

export default function ActivitiesBrowser({ activities, persons, deals, users, currentUserId }: Props) {
    const router = useRouter();
    const t = useTranslations('ActivityPage');
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
    const [filter, setFilter] = useState<Filter>('all');
    const [filterInitialized, setFilterInitialized] = useState(false);
    const [editing, setEditing] = useState<Activity | null>(null);
    const [creating, setCreating] = useState(false);
    const [deleting, setDeleting] = useState<Activity | null>(null);
    const [isDeleting, setIsDeleting] = useState(false);

    useEffect(() => {
        const stored = window.localStorage.getItem(FILTER_STORAGE_KEY);
        // eslint-disable-next-line react-hooks/set-state-in-effect
        if (isFilter(stored)) setFilter(stored);
        setFilterInitialized(true);
    }, []);

    useEffect(() => {
        if (!filterInitialized) return;
        window.localStorage.setItem(FILTER_STORAGE_KEY, filter);
    }, [filter, filterInitialized]);

    const typeCounts = useMemo(() => {
        const counts: Record<Filter, number> = { all: 0, Call: 0, Email: 0, Meeting: 0, Note: 0, Other: 0 };
        for (const a of activities) {
            counts.all++;
            counts[normalizeType(a.type)]++;
        }
        return counts;
    }, [activities]);

    const filtered = useMemo(() => {
        const q = query.trim().toLowerCase();
        return activities
            .filter((a) => {
                if (filter !== 'all' && normalizeType(a.type) !== filter) return false;
                if (!q) return true;
                const haystacks = [
                    a.subject,
                    a.notes,
                    a.personId ? personById.get(a.personId)?.name : null,
                    a.dealId ? dealById.get(a.dealId)?.name : null,
                    userById.get(a.createdById)?.displayName,
                ];
                return haystacks.some((s) => s?.toLowerCase().includes(q));
            })
            .sort((a, b) => activityTime(b) - activityTime(a));
    }, [activities, query, filter, personById, dealById, userById]);

    const groups = useMemo(() => {
        const today = todayKey();
        const dayMs = 1000 * 60 * 60 * 24;
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
                const diffDays = Math.round((today - dayKey) / dayMs);
                if (diffDays === 0) label = t('groupToday');
                else if (diffDays === 1) label = t('groupYesterday');
                else label = new Intl.DateTimeFormat(locale, { dateStyle: 'full' }).format(new Date(dayKey));
            }
            if (!map.has(id)) map.set(id, { id, label, sortKey, items: [] });
            map.get(id)!.items.push(a);
        }
        return Array.from(map.values()).sort((a, b) => b.sortKey - a.sortKey);
    }, [filtered, t, locale]);

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
    const emptyMessage = query.trim()
        ? t('emptyFiltered')
        : filter !== 'all'
          ? t('emptyFiltered')
          : t('empty');

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
                        {t('filtersHeader')}
                    </h2>
                    <nav className="space-y-0.5">
                        {FILTERS.map((f) => (
                            <FilterButton
                                key={f}
                                Icon={FILTER_ICON[f]}
                                label={f === 'all' ? t('filterAll') : t(`type${f}` as 'typeCall')}
                                count={typeCounts[f]}
                                active={filter === f}
                                onClick={() => setFilter(f)}
                            />
                        ))}
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
                        <div className="space-y-6">
                            {groups.map((group) => (
                                <section key={group.id}>
                                    <h3 className="mb-2 px-1 text-xs font-semibold uppercase tracking-[0.12em] text-neutral-400">
                                        {group.label}
                                    </h3>
                                    <div className="overflow-hidden rounded-2xl bg-white ring-1 ring-black/5">
                                        <ul className="divide-y divide-neutral-100">
                                            {group.items.map((activity) => (
                                                <ActivityRow
                                                    key={activity.id}
                                                    activity={activity}
                                                    person={activity.personId ? personById.get(activity.personId) : undefined}
                                                    deal={activity.dealId ? dealById.get(activity.dealId) : undefined}
                                                    creator={userById.get(activity.createdById)}
                                                    time={formatTime(activity)}
                                                    onOpen={() => setEditing(activity)}
                                                    onEdit={() => setEditing(activity)}
                                                    onDelete={() => setDeleting(activity)}
                                                    typeLabel={t(`type${normalizeType(activity.type)}` as 'typeCall')}
                                                    editLabel={t('edit')}
                                                    deleteLabel={t('delete')}
                                                    actionsAria={t('actionsAria')}
                                                />
                                            ))}
                                        </ul>
                                    </div>
                                </section>
                            ))}
                        </div>
                    )}
                </div>
            </div>

            {editing && (
                <EditActivitySheet
                    activity={editing}
                    open={!!editing}
                    onOpenChange={(open) => {
                        if (!open) setEditing(null);
                    }}
                    persons={persons}
                    deals={deals}
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
        </div>
    );
}

function FilterButton({
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
                <Icon className={`size-4 shrink-0 ${active ? 'text-brand-dark' : 'text-neutral-400'}`} />
                <span className="truncate">{label}</span>
            </span>
            <span className={`shrink-0 text-xs tabular-nums ${active ? 'text-brand-dark/70' : 'text-neutral-400'}`}>
                {count}
            </span>
        </button>
    );
}

type ActivityRowProps = {
    activity: Activity;
    person?: Contact;
    deal?: Deal;
    creator?: User;
    time: string;
    onOpen: () => void;
    onEdit: () => void;
    onDelete: () => void;
    typeLabel: string;
    editLabel: string;
    deleteLabel: string;
    actionsAria: string;
};

function ActivityRow({
    activity,
    person,
    deal,
    creator,
    time,
    onOpen,
    onEdit,
    onDelete,
    typeLabel,
    editLabel,
    deleteLabel,
    actionsAria,
}: ActivityRowProps) {
    const meta = TYPE_META[normalizeType(activity.type)];
    const Icon = meta.Icon;
    const creatorName = creator?.displayName || creator?.username || '';

    return (
        <li className="group flex cursor-pointer items-start gap-3 px-5 py-3.5 transition-colors hover:bg-neutral-50/70" onClick={onOpen}>
            <span
                className={`mt-0.5 flex size-7 shrink-0 items-center justify-center rounded-full ring-1 ring-inset ${meta.chip}`}
                aria-hidden
            >
                <Icon className="size-4" />
            </span>

            <div className="min-w-0 flex-1">
                <div className="flex min-w-0 items-start gap-2">
                    <p className="min-w-0 flex-1 truncate text-sm font-medium text-neutral-900">
                        <span className="text-neutral-400">{typeLabel} · </span>
                        {activity.subject}
                    </p>
                    <span className="shrink-0 text-xs tabular-nums text-neutral-400">{time}</span>
                </div>

                {activity.notes ? (
                    <p className="mt-0.5 line-clamp-2 text-sm text-neutral-500">{activity.notes}</p>
                ) : null}

                <div className="mt-1.5 flex flex-wrap items-center gap-1.5">
                    {person && (
                        <Link
                            href={`/records/contacts/${person.id}`}
                            onClick={(e) => e.stopPropagation()}
                            className="inline-flex max-w-[12rem] items-center gap-1 rounded-full bg-brand-light/50 px-2 py-0.5 text-xs font-medium text-brand-dark ring-1 ring-inset ring-brand-dark/10 transition hover:bg-brand-light"
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
                            className="inline-flex max-w-[12rem] items-center gap-1 rounded-full bg-white px-2 py-0.5 text-xs font-medium text-neutral-700 ring-1 ring-inset ring-neutral-200 transition hover:bg-neutral-50"
                            title={deal.name}
                        >
                            <BriefcaseIcon className="size-3 shrink-0" />
                            <span className="truncate">{deal.name}</span>
                        </Link>
                    )}
                </div>
            </div>

            <div className="flex shrink-0 items-center gap-1">
                {creator ? (
                    <Tooltip>
                        <TooltipTrigger asChild>
                            <Avatar size="sm" className="ring-1 ring-black/5">
                                {creator.profilePictureUrl ? (
                                    <AvatarImage src={creator.profilePictureUrl} alt={creatorName} />
                                ) : (
                                    <AvatarFallback>
                                        <UserIcon className="size-3 text-neutral-500" />
                                    </AvatarFallback>
                                )}
                            </Avatar>
                        </TooltipTrigger>
                        <TooltipContent side="bottom" align="end">
                            {creatorName}
                        </TooltipContent>
                    </Tooltip>
                ) : (
                    <div className="size-6" />
                )}

                <DropdownMenu>
                    <DropdownMenuTrigger asChild>
                        <button
                            type="button"
                            aria-label={actionsAria}
                            onClick={(e) => e.stopPropagation()}
                            className="flex size-7 shrink-0 items-center justify-center rounded-full text-neutral-400 opacity-0 transition hover:bg-neutral-100 hover:text-neutral-700 group-hover:opacity-100 focus:opacity-100"
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
                            <PencilIcon className="size-4 text-neutral-500" />
                            {editLabel}
                        </DropdownMenuItem>
                        <DropdownMenuSeparator />
                        <DropdownMenuItem
                            className="text-destructive hover:bg-red-500/10"
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
            </div>
        </li>
    );
}