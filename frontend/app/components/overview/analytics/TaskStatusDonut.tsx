'use client';

import { Pie, PieChart, ResponsiveContainer, Tooltip as RechartsTooltip } from 'recharts';
import { useTranslations } from 'next-intl';

import { type TaskStatus } from '@/app/lib/types';
import { TASK_STATUSES } from '@/app/components/overview/analytics/relationshipMetrics';

const STATUS_COLOR: Record<TaskStatus, string> = {
    todo: 'var(--chart-2)',
    in_progress: 'var(--chart-open)',
    done: 'var(--chart-won)',
};

type Slice = { key: TaskStatus; label: string; count: number; fill: string };

/**
 * Task-status donut. Consumes server-computed status counts and renders the done-share as the
 * centred completion percentage plus a per-status legend.
 */
export default function TaskStatusDonut({
    counts,
}: {
    counts: { todo: number; inProgress: number; done: number };
}) {
    const t = useTranslations('AnalyticsTaskStatus');

    const byStatus: Record<TaskStatus, number> = {
        todo: counts.todo,
        in_progress: counts.inProgress,
        done: counts.done,
    };
    const total = TASK_STATUSES.reduce((sum, status) => sum + byStatus[status], 0);

    if (total === 0) {
        return <div className="flex h-56 items-center justify-center text-sm text-muted-foreground">{t('empty')}</div>;
    }

    const completion = Math.round((byStatus.done / total) * 100);
    const data: Slice[] = TASK_STATUSES.map((status) => ({
        key: status,
        label: t(status),
        count: byStatus[status],
        fill: STATUS_COLOR[status],
    }));

    return (
        <div className="flex h-full flex-col items-center justify-center gap-5 sm:flex-row sm:gap-7">
            <div className="relative h-40 w-40 shrink-0">
                <ResponsiveContainer width="100%" height="100%">
                    <PieChart>
                        <Pie
                            data={data.filter((slice) => slice.count > 0)}
                            dataKey="count"
                            nameKey="label"
                            innerRadius={52}
                            outerRadius={72}
                            startAngle={90}
                            endAngle={-270}
                            stroke="var(--chart-stroke)"
                            strokeWidth={2}
                            isAnimationActive={false}
                        />
                        <RechartsTooltip content={<StatusTooltip />} />
                    </PieChart>
                </ResponsiveContainer>
                <div className="pointer-events-none absolute inset-0 flex flex-col items-center justify-center">
                    <span className="text-3xl font-semibold tabular-nums text-foreground">{completion}%</span>
                    <span className="text-[10px] uppercase tracking-[0.12em] text-muted-foreground">{t('done')}</span>
                </div>
            </div>
            <ul className="w-full max-w-[14rem] space-y-3">
                {data.map((slice) => (
                    <li key={slice.key} className="flex items-center gap-3">
                        <span className="size-2.5 shrink-0 rounded-sm" style={{ backgroundColor: slice.fill }} />
                        <span className="flex-1 text-sm text-muted-foreground">{slice.label}</span>
                        <span className="text-right text-sm tabular-nums text-foreground">
                            {t('count', { count: slice.count })}
                        </span>
                    </li>
                ))}
            </ul>
        </div>
    );
}

function StatusTooltip({ active, payload }: { active?: boolean; payload?: Array<{ payload: Slice }> }) {
    if (!active || !payload?.length) return null;
    const slice = payload[0].payload;
    return (
        <div className="rounded-md border border-border bg-popover p-2 text-xs text-popover-foreground shadow-md">
            <div className="flex items-center gap-1.5 font-medium text-foreground">
                <span className="inline-block size-2 rounded-sm" style={{ backgroundColor: slice.fill }} />
                {slice.label}
            </div>
            <div className="mt-1 tabular-nums text-muted-foreground">{slice.count}</div>
        </div>
    );
}
