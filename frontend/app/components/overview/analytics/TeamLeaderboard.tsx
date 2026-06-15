'use client';

import { useMemo, useState } from 'react';
import Link from 'next/link';
import Image from 'next/image';
import { useTranslations } from 'next-intl';

import { type Activity, type Note, type Task, type User } from '@/app/lib/types';
import { parseMysqlDateTime } from '@/app/lib/utils';
import { RANGE_DAYS, type RangeKey } from '@/app/components/overview/analytics/metrics';
import UserAvatar from '@/app/components/records/users/UserAvatar';

type Standing = { user: User; touches: number };

export default function TeamLeaderboard({
    users,
    activities,
    tasks,
    notes,
    range,
}: {
    users: User[];
    activities: Activity[];
    tasks: Task[];
    notes: Note[];
    range: RangeKey;
}) {
    const t = useTranslations('AnalyticsTeam');
    const [now] = useState(() => Date.now());

    const standings = useMemo<Standing[]>(() => {
        const start = now - RANGE_DAYS[range] * 86400000; // 1 day in milliseconds
        const within = (value?: string) => {
            const ts = parseMysqlDateTime(value);
            return Number.isFinite(ts) && ts >= start && ts <= now;
        };

        const touches = new Map<number, number>();
        const bump = (id: number | null | undefined) => {
            if (id == null) return;
            touches.set(id, (touches.get(id) ?? 0) + 1);
        };

        for (const a of activities) if (within(a.timestamp)) bump(a.createdById);
        for (const task of tasks) if (task.completed && within(task.updatedAt)) bump(task.assignedToId);
        for (const note of notes) if (within(note.createdAt)) bump(note.author);

        return users
            .map((user) => ({ user, touches: touches.get(user.id) ?? 0 }))
            .filter((s) => s.touches > 0)
            .sort((a, b) => b.touches - a.touches)
            .slice(0, 5);
    }, [users, activities, tasks, notes, range, now]);

    if (standings.length === 0) {
        return <div className="flex h-64 items-center justify-center text-sm text-muted-foreground">{t('empty')}</div>;
    }

    const max = Math.max(...standings.map((s) => s.touches), 1);

    return (
        <ul className="flex flex-col gap-4">
            {standings.map((s, i) => (
                <li key={s.user.id}>
                    <Link
                        href={`/users/${s.user.id}`}
                        className="group flex items-center gap-3 rounded-lg px-2 py-1.5 -mx-2 transition hover:bg-muted"
                    >
                        <span className="w-4 shrink-0 text-sm tabular-nums text-muted-foreground">{i + 1}</span>
                        <UserAvatar user={s.user} type="small" />
                        <div className="min-w-0 flex-1">
                            <div className="flex items-baseline justify-between gap-2">
                                <span className="min-w-0 truncate text-sm font-medium text-foreground">
                                    {s.user.displayName}
                                </span>
                                <span className="shrink-0 text-xs tabular-nums text-muted-foreground">
                                    {t('touches', { count: s.touches })}
                                </span>
                            </div>
                            <div className="mt-1.5 h-1.5 overflow-hidden rounded-full bg-muted">
                                <div
                                    className="h-full rounded-full bg-brand transition-[width] duration-500 ease-out motion-reduce:transition-none"
                                    style={{ width: `${(s.touches / max) * 100}%` }}
                                />
                            </div>
                        </div>
                    </Link>
                </li>
            ))}
        </ul>
    );
}