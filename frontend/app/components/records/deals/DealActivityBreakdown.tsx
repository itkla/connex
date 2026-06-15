'use client';

import { Cell, Pie, PieChart, ResponsiveContainer, Tooltip as RechartsTooltip } from 'recharts';
import { useTranslations } from 'next-intl';

import { type Activity } from '@/app/lib/types';

const TYPE_COLORS: Record<string, string> = {
    // TODO: define these globally, maybe in globals.css?
    Call: 'var(--color-brand)',
    Email: '#0ea5e9',
    Meeting: '#10b981',
    Note: '#f59e0b',
    Other: 'var(--muted-foreground)',
};

type Slice = { type: string; value: number; color: string };

export default function DealActivityBreakdown({ activities }: { activities: Activity[] }) {
    const t = useTranslations('DealsActivityBreakdown');
    const counts = new Map<string, number>();
    for (const a of activities) {
        const key = a.type?.trim() || 'Other';
        counts.set(key, (counts.get(key) ?? 0) + 1);
    }
    const data: Slice[] = Array.from(counts.entries())
        .map(([type, value]) => ({ type, value, color: TYPE_COLORS[type] ?? 'var(--muted-foreground)' }))
        .sort((a, b) => b.value - a.value);

    const total = activities.length;

    return (
        <div className="rounded-xl bg-transparent p-3 ring-1 ring-border">
            <p className="text-xs uppercase tracking-wider text-muted-foreground">{t('activityMix')}</p>
            {total === 0 ? (
                <p className="mt-3 text-xs text-muted-foreground">{t('noActivitiesYet')}</p>
            ) : (
                <div className="mt-1 flex items-center gap-4">
                    <div className="relative h-28 w-28 shrink-0">
                        <ResponsiveContainer width="100%" height="100%">
                            <PieChart>
                                <Pie
                                    data={data}
                                    dataKey="value"
                                    nameKey="type"
                                    innerRadius={32}
                                    outerRadius={48}
                                    stroke="none"
                                    isAnimationActive={false}
                                >
                                    {data.map((d) => (
                                        <Cell key={d.type} fill={d.color} />
                                    ))}
                                </Pie>
                                <RechartsTooltip content={<BreakdownTooltip />} />
                            </PieChart>
                        </ResponsiveContainer>
                        <div className="pointer-events-none absolute inset-0 flex flex-col items-center justify-center">
                            <span className="text-lg font-semibold text-foreground">{total}</span>
                            <span className="text-[10px] uppercase tracking-wider text-muted-foreground">{t('total')}</span>
                        </div>
                    </div>
                    <ul className="min-w-0 flex-1 space-y-1 text-xs">
                        {data.map((d) => (
                            <li key={d.type} className="flex items-center gap-2">
                                <span
                                    className="inline-block size-2 rounded-sm"
                                    style={{ backgroundColor: d.color }}
                                />
                                <span className="flex-1 truncate text-muted-foreground">{d.type}</span>
                                <span className="tabular-nums text-foreground">{d.value}</span>
                            </li>
                        ))}
                    </ul>
                </div>
            )}
        </div>
    );
}

function BreakdownTooltip({
    active,
    payload,
}: {
    active?: boolean;
    payload?: Array<{ payload: Slice }>;
}) {
    if (!active || !payload?.length) return null;
    const d = payload[0].payload;
    return (
        <div className="rounded-md bg-popover text-popover-foreground p-2 text-xs shadow-md border border-border">
            <div className="font-medium text-popover-foreground">{d.type}</div>
            <div className="text-muted-foreground">{d.value}</div>
        </div>
    );
}