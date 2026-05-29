'use client';

import { useMemo, useState } from 'react';
import Link from 'next/link';
import { useTranslations } from 'next-intl';
import { ChevronLeftIcon, ChevronRightIcon } from '@heroicons/react/24/outline';

import type { Task, Activity, Deal, Contact, Note } from '@/app/lib/types';
import { parseMysqlDateTime } from '@/app/lib/utils';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';

type Props = {
    activities?: Activity[];
    tasks?: Task[];
    persons?: Contact[];
    deals?: Deal[];
    notes?: Note[];
};

type EntryKind = 'task' | 'activity' | 'deal' | 'note';

type Entry = {
    id: string;
    kind: EntryKind;
    sortAt: number;
    label: string;
    content?: string;
    href: string;
};

const CHIP_CLASS: Record<EntryKind, string> = {
    task: 'bg-brand-light text-brand-dark hover:bg-brand/20',
    activity: 'bg-blue-100 text-blue-900 hover:bg-blue-200',
    deal: 'bg-amber-100 text-amber-900 hover:bg-amber-200',
    note: 'bg-neutral-200 text-neutral-700 hover:bg-neutral-300',
};

const DAY_MS = 1000 * 60 * 60 * 24;

function dateKey(d: Date): string {
    const y = d.getFullYear();
    const m = String(d.getMonth() + 1).padStart(2, '0');
    const day = String(d.getDate()).padStart(2, '0');
    return `${y}-${m}-${day}`;
}

function startOfMonth(d: Date): Date {
    return new Date(d.getFullYear(), d.getMonth(), 1);
}

function startOfGrid(monthStart: Date): Date {
    const d = new Date(monthStart);
    d.setDate(d.getDate() - d.getDay());
    d.setHours(0, 0, 0, 0);
    return d;
}

export default function Calendar({ activities, tasks, persons, deals, notes }: Props) {
    const t = useTranslations('Calendar');
    const [monthAnchor, setMonthAnchor] = useState<Date>(() => startOfMonth(new Date()));

    const personById = useMemo(() => {
        const map = new Map<number, Contact>();
        for (const p of persons ?? []) map.set(p.id, p);
        return map;
    }, [persons]);

    const entriesByDay = useMemo(() => {
        const map = new Map<string, Entry[]>();
        const push = (entry: Entry, when: number) => {
            const key = dateKey(new Date(when));
            const arr = map.get(key);
            if (arr) arr.push(entry);
            else map.set(key, [entry]);
        };

        for (const task of tasks ?? []) {
            const when = parseMysqlDateTime(task.dueDate);
            if (Number.isNaN(when)) continue;
            push(
                {
                    id: `task-${task.id}`,
                    kind: 'task',
                    sortAt: when,
                    label: task.description,
                    content: task.description,
                    href: '/activity/tasks',
                },
                when,
            );
        }

        for (const activity of activities ?? []) {
            const when = parseMysqlDateTime(activity.timestamp);
            if (Number.isNaN(when)) continue;
            push(
                {
                    id: `activity-${activity.id}`,
                    kind: 'activity',
                    sortAt: when,
                    label: activity.subject || activity.type,
                    content: activity.subject || activity.type,
                    href: '/activity',
                },
                when,
            );
        }

        for (const deal of deals ?? []) {
            const when = parseMysqlDateTime(deal.expectedCloseDate);
            if (Number.isNaN(when)) continue;
            push(
                {
                    id: `deal-${deal.id}`,
                    kind: 'deal',
                    sortAt: when,
                    label: deal.name,
                    content: deal.name,
                    href: `/records/deals/${deal.id}`,
                },
                when,
            );
        }

        for (const note of notes ?? []) {
            const when = parseMysqlDateTime(note.createdAt);
            if (Number.isNaN(when)) continue;
            const author = personById.get(note.person ?? -1);
            const preview = note.content.length > 60 ? note.content.slice(0, 60) + '…' : note.content;
            push(
                {
                    id: `note-${note.id}`,
                    kind: 'note',
                    sortAt: when,
                    label: author ? `${author.name}: ${preview}` : preview,
                    // TODO: add param to /notes so that if specified, the note is highlighted on page visit
                    content: note.content,
                    href: `/activity/notes?id=${note.id}`,
                },
                when,
            );
        }

        for (const arr of map.values()) arr.sort((a, b) => a.sortAt - b.sortAt);
        return map;
    }, [tasks, activities, deals, notes, personById]);

    const gridStart = useMemo(() => startOfGrid(monthAnchor), [monthAnchor]);
    const cells = useMemo(() => {
        return Array.from({ length: 42 }, (_, i) => new Date(gridStart.getTime() + i * DAY_MS));
    }, [gridStart]);

    const todayKey = useMemo(() => dateKey(new Date()), []);
    const monthLabel = useMemo(
        () => new Intl.DateTimeFormat(undefined, { month: 'long', year: 'numeric' }).format(monthAnchor),
        [monthAnchor],
    );
    const weekdayLabels = useMemo(() => {
        const fmt = new Intl.DateTimeFormat(undefined, { weekday: 'short' });
        return Array.from({ length: 7 }, (_, i) => {
            const d = new Date(gridStart.getTime() + i * DAY_MS);
            return fmt.format(d);
        });
    }, [gridStart]);

    const goPrev = () => setMonthAnchor((d) => new Date(d.getFullYear(), d.getMonth() - 1, 1));
    const goNext = () => setMonthAnchor((d) => new Date(d.getFullYear(), d.getMonth() + 1, 1));
    const goToday = () => setMonthAnchor(startOfMonth(new Date()));

    return (
        <div className="space-y-6">
            <header className="flex flex-wrap items-end justify-between gap-4">
                <div>
                    <h1 className="text-4xl font-extrabold tracking-tight">{t('title')}</h1>
                    <p className="mt-2 text-sm text-neutral-500 tabular-nums">{monthLabel}</p>
                </div>
                <div className="flex items-center gap-2">
                    <button
                        type="button"
                        onClick={goToday}
                        className="h-8 rounded-full bg-neutral-100 px-4 text-xs font-medium text-neutral-700 ring-1 ring-black/5 transition hover:bg-white"
                    >
                        {t('today')}
                    </button>
                    <button
                        type="button"
                        onClick={goPrev}
                        aria-label={t('prev')}
                        className="grid size-8 place-items-center rounded-full bg-neutral-100 text-neutral-700 ring-1 ring-black/5 transition hover:bg-white"
                    >
                        <ChevronLeftIcon className="size-4" />
                    </button>
                    <button
                        type="button"
                        onClick={goNext}
                        aria-label={t('next')}
                        className="grid size-8 place-items-center rounded-full bg-neutral-100 text-neutral-700 ring-1 ring-black/5 transition hover:bg-white"
                    >
                        <ChevronRightIcon className="size-4" />
                    </button>
                </div>
            </header>

            <div className="overflow-hidden rounded-2xl bg-neutral-100 ring-1 ring-black/5">
                <div className="grid grid-cols-7 border-b border-black/5 bg-white">
                    {weekdayLabels.map((label) => (
                        <div
                            key={label}
                            className="px-2 py-2 text-center text-[10px] font-semibold uppercase tracking-[0.14em] text-neutral-500"
                        >
                            {label}
                        </div>
                    ))}
                </div>
                <div className="grid grid-cols-7 gap-px bg-black/5">
                    {cells.map((day) => {
                        const key = dateKey(day);
                        const inMonth = day.getMonth() === monthAnchor.getMonth();
                        const isToday = key === todayKey;
                        // const entries = entriesByDay.get(key) ?? [];

                        // order entries by importance (deal, task, activity, note)
                        const entries = entriesByDay.get(key) ?? [];
                        entries.sort((a, b) => {
                            if (a.kind === 'deal') return -1;
                            if (b.kind === 'deal') return 1;
                            if (a.kind === 'task') return -1;
                            if (b.kind === 'task') return 1;
                            if (a.kind === 'activity') return -1;
                            if (b.kind === 'activity') return 1;
                            if (a.kind === 'note') return -1;
                            if (b.kind === 'note') return 1;
                            return 0;
                        });
                        return (
                            <div
                                key={key}
                                className={`flex h-[140px] flex-col gap-1 p-2 ${
                                    inMonth ? 'bg-white' : 'bg-neutral-50'
                                }`}
                            >
                                <div className="flex items-center justify-end">
                                    <span
                                        className={`grid size-6 place-items-center rounded-full text-xs tabular-nums ${
                                            isToday
                                                ? 'bg-brand font-semibold text-white'
                                                : inMonth
                                                  ? 'text-neutral-700'
                                                  : 'text-neutral-400'
                                        }`}
                                    >
                                        {day.getDate()}
                                    </span>
                                </div>
                                <ul className="flex min-h-0 flex-1 flex-col gap-1 overflow-y-auto">
                                    {entries.map((entry) => (
                                        <li key={entry.id}>
                                            <Tooltip>
                                                <TooltipTrigger asChild>
                                                    <Link
                                                        href={entry.href}
                                                        className={`block truncate rounded-md px-2 py-0.5 text-[11px] font-medium transition ${CHIP_CLASS[entry.kind]}`}
                                                    >
                                                        {entry.label}
                                                    </Link>
                                                </TooltipTrigger>
                                                <TooltipContent side="left" align="center">
                                                    {t(`item${entry.kind.charAt(0).toUpperCase() + entry.kind.slice(1)}` as 'itemTask')}: {entry.content}
                                                </TooltipContent>
                                            </Tooltip>
                                        </li>
                                    ))}
                                </ul>
                            </div>
                        );
                    })}
                </div>
            </div>
        </div>
    );
}