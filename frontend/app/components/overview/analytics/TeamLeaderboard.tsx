'use client';

import { useMemo } from 'react';
import Link from 'next/link';
import { useTranslations } from 'next-intl';

import { type TeamLeaderboardEntry, type User } from '@/app/lib/types';
import UserAvatar from '@/app/components/records/users/UserAvatar';

type Standing = { user: User; touches: number };

/**
 * Team activity leaderboard. Joins server-computed {@link TeamLeaderboardEntry} touch counts
 * (already ordered by touches, descending) to {@code users} for names and avatars, dropping
 * entries with no matching user, and renders the top five.
 */
export default function TeamLeaderboard({
    users,
    standings,
}: {
    users: User[];
    standings: TeamLeaderboardEntry[];
}) {
    const t = useTranslations('AnalyticsTeam');

    const rows = useMemo<Standing[]>(() => {
        const userById = new Map(users.map((user) => [user.id, user]));
        return standings
            .map((entry) => {
                const user = userById.get(entry.userId);
                return user ? { user, touches: entry.touches } : null;
            })
            .filter((standing): standing is Standing => standing !== null)
            .slice(0, 5);
    }, [users, standings]);

    if (rows.length === 0) {
        return <div className="flex h-64 items-center justify-center text-sm text-muted-foreground">{t('empty')}</div>;
    }

    const max = Math.max(...rows.map((s) => s.touches), 1);

    return (
        <ul className="flex flex-col gap-4">
            {rows.map((s, i) => (
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
