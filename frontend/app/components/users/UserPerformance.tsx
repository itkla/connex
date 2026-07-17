'use client';

import { useEffect, useState } from 'react';
import Link from 'next/link';
import { useTranslations } from 'next-intl';
import { ArrowRightIcon } from '@heroicons/react/24/outline';

import { getDealKpis, getDealMetrics, getTaskSummary } from '@/app/lib/api';
import { type MemberScopeParams } from '@/app/lib/types';
import StatCard from '@/app/components/me/StatCard';
import SectionHeader from '@/app/components/dashboard/SectionHeader';

const RANGE = '90d';

type Snapshot = {
    wonDeals: number;
    winRate: number;
    openDeals: number;
    openTasks: number;
};

const EMPTY: Snapshot = { wonDeals: 0, winRate: 0, openDeals: 0, openTasks: 0 };

/**
 * Compact 90-day performance snapshot for one workspace member, computed from the member-scoped
 * analytics aggregates (deals KPIs, deal metrics, task summary) rather than loading their records
 * client-side. Each metric fails soft to zero so an inaccessible member or API error never breaks
 * the surrounding profile page. Links through to the full Analytics board pre-filtered to the member.
 */
export default function UserPerformance({ userId }: { userId: number }) {
    const t = useTranslations('UsersPage');
    const [snapshot, setSnapshot] = useState<Snapshot | null>(null);

    useEffect(() => {
        let cancelled = false;
        const scope: MemberScopeParams = { scope: 'members', memberIds: [userId] };
        Promise.all([
            getDealKpis(undefined, RANGE, scope).catch(() => null),
            getDealMetrics(scope).catch(() => null),
            getTaskSummary(scope).catch(() => null),
        ]).then(([kpis, metrics, taskSummary]) => {
            if (cancelled) return;
            const wonDeals = kpis?.wonCount ?? 0;
            const decided = wonDeals + (kpis?.lostCount ?? 0);
            const winRate = decided > 0 ? Math.round((wonDeals / decided) * 100) : 0;
            const openDeals = metrics?.byCurrency.reduce((sum, entry) => sum + entry.openCount, 0) ?? 0;
            const openTasks = taskSummary ? taskSummary.todo + taskSummary.inProgress : 0;
            setSnapshot({ wonDeals, winRate, openDeals, openTasks });
        });
        return () => { cancelled = true; };
    }, [userId]);

    const data = snapshot ?? EMPTY;
    const loading = snapshot === null;

    return (
        <section aria-busy={loading}>
            <SectionHeader title={t('performance')} />
            <p className="-mt-2 mb-3 text-xs text-muted-foreground">{t('performanceWindow')}</p>
            <div className={`grid grid-cols-2 gap-3 sm:grid-cols-4 transition-opacity duration-300 ${loading ? 'opacity-60' : 'opacity-100'}`}>
                <StatCard label={t('wonDeals')} value={data.wonDeals} />
                <StatCard label={t('winRate')} value={data.winRate} display={`${data.winRate}%`} />
                <StatCard label={t('openDeals')} value={data.openDeals} />
                <StatCard label={t('openTasks')} value={data.openTasks} />
            </div>
            <Link
                href={`/overview/analytics?owner=${userId}&range=${RANGE}`}
                className="mt-3 inline-flex items-center gap-1.5 text-sm font-medium text-brand transition-colors hover:text-brand-hover"
            >
                {t('viewFullAnalytics')}
                <ArrowRightIcon className="size-4" />
            </Link>
        </section>
    );
}
