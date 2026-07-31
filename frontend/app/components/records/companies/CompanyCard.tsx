'use client';

import { Loader2Icon } from 'lucide-react';
import { useRouter } from 'next/navigation';
import { useLocale, useTranslations } from 'next-intl';
import { ChevronRightIcon } from '@heroicons/react/24/solid';
import { UserCircleIcon } from '@heroicons/react/24/outline';
import { Button } from '@/components/ui/button';
import { type Company, type CompanyMetrics, type LoadStatus } from '@/app/lib/types';
import CompanyAvatar from '@/app/components/records/companies/CompanyAvatar';
import { AvatarFallback, Avatar, AvatarGroup, AvatarGroupCount, AvatarImage } from '@/components/ui/avatar';
import { useMemo, useState } from 'react';
import { Area, AreaChart, LabelList, ResponsiveContainer, Tooltip as RechartsTooltip, XAxis } from 'recharts';
import { formatCompactCurrency } from '@/app/lib/utils';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import { RecordActionMenuTrigger } from '@/app/components/records/RecordActionMenu';
import type { RecordRemoveIntent } from '@/app/components/records/types';
import { cn } from '@/lib/utils';
import {
    recordDetailNavigationPath,
    type RecordReturnSelectionSnapshot,
} from '@/app/lib/recordReturnPath';

interface CompanyCardProps {
    company: Company;
    ownerName?: string;
    metrics?: CompanyMetrics;
    metricsStatus: LoadStatus;
    onFirstExpand?: () => void;
    onQuickEdit?: () => void;
    onDelete?: () => void;
    readOnly?: boolean;
    removeIntent?: RecordRemoveIntent;
    returnSelection?: RecordReturnSelectionSnapshot;
}

export default function CompanyCard({
    company,
    ownerName,
    metrics,
    metricsStatus,
    onFirstExpand,
    onQuickEdit,
    onDelete,
    readOnly = false,
    removeIntent = 'archive',
    returnSelection,
}: CompanyCardProps) {
    const [isExpanded, setIsExpanded] = useState(false);
    const router = useRouter();
    const t = useTranslations('CompaniesCard');

    const open = () => router.push(recordDetailNavigationPath(
        'companies',
        company.id,
        returnSelection,
    ));
    const toggleExpand = () => {
        if (!isExpanded) onFirstExpand?.();
        setIsExpanded((prev) => !prev);
    };

    return (
        <div className="group rounded-2xl border border-border bg-card transition duration-200 hover:shadow-lg dark:hover:shadow-[0_10px_30px_-12px_rgb(0_0_0/0.5)]">
            <div
                className={cn(
                    'flex items-center gap-4 rounded-2xl p-4 transition-colors',
                    !readOnly && 'cursor-pointer hover:bg-muted',
                )}
                onClick={readOnly ? undefined : toggleExpand}
            >
                <CompanyAvatar company={company} type="large" />

                <div className="flex-1 min-w-0">
                    <h3 className="text-base font-semibold text-foreground truncate">
                        {company.name}
                    </h3>
                    {company.industry && (
                        <p className="mt-0.5 text-sm text-muted-foreground truncate">
                            {company.industry}
                        </p>
                    )}
                    {ownerName && (
                        <p className="mt-0.5 flex items-center gap-1 text-xs text-muted-foreground truncate">
                            <UserCircleIcon className="size-3.5 shrink-0" />
                            <span className="truncate">{t('ownerLabel', { name: ownerName })}</span>
                        </p>
                    )}
                </div>

                <div className="flex shrink-0 items-center gap-1">
                    {!readOnly && (
                        <Button
                            variant="outline"
                            size="sm"
                            onClick={(event) => {
                                event.stopPropagation();
                                open();
                            }}
                            aria-label={t('openCompanyAriaLabel')}
                            className="size-10 shrink-0 border-none bg-muted text-muted-foreground shadow-none outline-none hover:bg-muted hover:text-foreground"
                        >
                            <ChevronRightIcon className="size-4" />
                        </Button>
                    )}
                    {onDelete && (
                        <div onClick={(event) => event.stopPropagation()}>
                            <RecordActionMenuTrigger
                                model={{
                                    record: { type: 'company', id: company.id, label: company.name },
                                    includeRecordActions: !readOnly,
                                    onQuickEdit: !readOnly ? onQuickEdit : undefined,
                                    onRemove: onDelete,
                                    removeIntent,
                                }}
                                triggerClassName="size-10 opacity-100"
                            />
                        </div>
                    )}
                </div>
            </div>

            {isExpanded && (
                <div className="border-t border-border p-4 space-y-4">
                    {metricsStatus === 'loading' && (
                        <div className="flex items-center justify-center py-4 text-sm text-muted-foreground">
                            <Loader2Icon className="size-4 animate-spin mr-2" />
                            {t('loadingMetrics')}
                        </div>
                    )}

                    {metricsStatus === 'error' && (
                        <div className="flex items-center justify-between py-2 text-sm">
                            <span className="text-destructive">{t('metricsLoadFailed')}</span>
                            <Button
                                variant="outline"
                                size="sm"
                                onClick={(e) => {
                                    e.stopPropagation();
                                    onFirstExpand?.();
                                }}
                            >
                                {t('retry')}
                            </Button>
                        </div>
                    )}

                    {metricsStatus === 'ready' && metrics && (
                        <>
                            <div className="flex flex-wrap items-start gap-8">
                                <AvatarSection kind="employees" label={t('employees')} people={metrics.persons} total={metrics.personCount} fallbackKey="name" imageKey="imageUrl" />
                                <AvatarSection kind="relations" label={t('relations')} people={metrics.relatedUsers} total={metrics.relatedUserCount} fallbackKey="displayName" imageKey="profilePictureUrl" />
                            </div>

                            <div className="grid grid-cols-1 md:grid-cols-3 gap-3">
                                <EngagementSparkline data={metrics.weeklyEngagement} />
                                <RevenueTiles
                                    pastRevenue={metrics.pastRevenue}
                                    projectedRevenue={metrics.projectedRevenue}
                                    currency={metrics.currency}
                                />
                            </div>
                        </>
                    )}
                </div>
            )}
        </div>
    );
}

type AvatarSubject = {
    id: number;
    name?: string;
    displayName?: string;
    imageUrl?: string;
    profilePictureUrl?: string;
};

function AvatarSection({
    kind,
    label,
    people,
    total,
    fallbackKey,
    imageKey,
}: {
    kind: 'employees' | 'relations';
    label: string;
    people: AvatarSubject[];
    total: number;
    fallbackKey: 'name' | 'displayName';
    imageKey: 'imageUrl' | 'profilePictureUrl';
}) {
    const visible = people.slice(0, 5);
    const overflow = Math.max(0, total - visible.length);

    const router = useRouter();

    const handleClick = (id: number) => {
        if (kind === 'employees') {
            router.push(`/records/contacts/${id}`);
        } else {
            router.push(`/users/${id}`);
        }
    };
    const t = useTranslations('CompaniesCard');
    return (
        <div>
            <p className="text-xs font-medium tracking-[0.12em] text-muted-foreground uppercase mb-2">
                {t('labelWithCount', { label, count: total })}
            </p>
            <AvatarGroup>
                {visible.map((p) => {
                    const fallback = (p[fallbackKey] ?? '?').charAt(0);
                    return (
                        <Tooltip key={p.id}>
                            <TooltipTrigger asChild>
                                <Avatar key={p.id} className="border-card w-10 h-10 cursor-pointer hover:scale-110 transition-all duration-300 bg-card" onClick={() => handleClick(p.id)}>
                                    <AvatarImage src={p[imageKey]} className="w-10 h-10" />
                                    <AvatarFallback className="w-10 h-10">{fallback}</AvatarFallback>
                                </Avatar>
                            </TooltipTrigger>
                            <TooltipContent side="bottom" align="center">
                                {p[fallbackKey] ?? '?'}
                            </TooltipContent>
                        </Tooltip>
                    );
                })}
                {overflow > 0 && (
                    <AvatarGroupCount className="ring-transparent bg-muted text-muted-foreground w-10 h-10">
                        +{overflow}
                    </AvatarGroupCount>
                )}
            </AvatarGroup>
        </div>
    );
}

export type EngagementPoint = {
    weekStart: number;
    count: number;
    activities: number;
    tasks: number;
    notes: number;
};

export function EngagementSparkline({ data }: { data: EngagementPoint[] }) {
    const t = useTranslations('CompaniesCard');
    const locale = useLocale();
    const dateFormatter = useMemo(
        () => new Intl.DateTimeFormat(locale, { month: 'short', day: 'numeric' }),
        [locale],
    );
    const ticks = useMemo(() => {
        const out: number[] = [];
        for (let i = data.length - 1; i >= 0; i -= 2) out.push(data[i].weekStart);
        return out.reverse();
    }, [data]);
    const total = data.reduce((s, d) => s + d.count, 0);
    return (
        <div className="md:col-span-2 rounded-xl bg-transparent p-3 ring-1 ring-border">
            <p className="text-xs uppercase tracking-wider text-muted-foreground">{t('engagement12w')}</p>
            {total === 0 ? (
                <p className="text-xs text-muted-foreground mt-3">{t('noEngagement')}</p>
            ) : (
                <div className="h-28 mt-1">
                    <ResponsiveContainer width="100%" height="100%">
                        <AreaChart data={data} margin={{ top: 14, right: 8, bottom: 0, left: 8 }}>
                            <XAxis
                                dataKey="weekStart"
                                tickFormatter={(ts: number) => dateFormatter.format(new Date(ts))}
                                tick={{ fontSize: 10, fill: 'var(--chart-axis)' }}
                                tickLine={false}
                                axisLine={false}
                                ticks={ticks}
                                interval={0}
                            />
                            <RechartsTooltip
                                cursor={{ stroke: 'var(--color-brand)', strokeOpacity: 0.3, strokeWidth: 1 }}
                                content={<EngagementTooltip />}
                            />
                            <Area
                                type="monotone"
                                dataKey="count"
                                stroke="var(--color-brand)"
                                fill="var(--color-brand)"
                                fillOpacity={0.25}
                                strokeWidth={1.5}
                                isAnimationActive={false}
                                dot={{ r: 2, fill: 'var(--color-brand)', stroke: 'var(--color-brand)' }}
                            >
                                <LabelList
                                    dataKey="count"
                                    position="top"
                                    fontSize={10}
                                    fill="var(--chart-axis)"
                                    formatter={(v: unknown) => (typeof v === 'number' && v > 0 ? String(v) : '')}
                                />
                            </Area>
                        </AreaChart>
                    </ResponsiveContainer>
                </div>
            )}
        </div>
    );
}

export function RevenueTiles({
    pastRevenue,
    projectedRevenue,
    currency = 'USD',
}: {
    pastRevenue: number;
    projectedRevenue: number;
    currency?: string;
}) {
    const t = useTranslations('CompaniesCard');
    const locale = useLocale();
    return (
        <div className="space-y-2">
            <div className="rounded-xl bg-transparent p-3 ring-1 ring-border">
                <p className="text-xs uppercase tracking-wider text-muted-foreground">{t('closedRevenue')}</p>
                <p className="mt-1 text-lg font-semibold tabular-nums text-foreground">
                    {formatCompactCurrency(pastRevenue, currency, locale)}
                </p>
            </div>
            <div className="rounded-xl bg-transparent p-3 ring-1 ring-border">
                <p className="text-xs uppercase tracking-wider text-muted-foreground">{t('projected')}</p>
                <p className="mt-1 text-lg font-semibold tabular-nums text-foreground">
                    {formatCompactCurrency(projectedRevenue, currency, locale)}
                </p>
            </div>
        </div>
    );
}

interface EngagementTooltipProps {
    active?: boolean;
    payload?: Array<{ payload: EngagementPoint }>;
}

function EngagementTooltip({ active, payload }: EngagementTooltipProps) {
    const t = useTranslations('CompaniesCard');
    const locale = useLocale();
    const dateFormatter = useMemo(
        () => new Intl.DateTimeFormat(locale, { month: 'short', day: 'numeric' }),
        [locale],
    );
    if (!active || !payload?.length) return null;
    const d = payload[0].payload;
    return (
        <div className="rounded-md bg-popover text-popover-foreground p-2 text-xs border border-border shadow-md">
            <div className="font-medium text-foreground mb-1.5">
                {dateFormatter.format(new Date(d.weekStart))}
            </div>
            <div className="space-y-0.5">
                <div className="flex items-center gap-1.5 text-muted-foreground">
                    <span className="inline-block size-2 rounded-sm bg-brand" />
                    {t('tooltipActivities', { count: d.activities })}
                </div>
                <div className="flex items-center gap-1.5 text-muted-foreground">
                    <span className="inline-block size-2 rounded-sm bg-amber-500" />
                    {t('tooltipTasks', { count: d.tasks })}
                </div>
                <div className="flex items-center gap-1.5 text-muted-foreground">
                    <span className="inline-block size-2 rounded-sm bg-emerald-500" />
                    {t('tooltipNotes', { count: d.notes })}
                </div>
            </div>
        </div>
    );
}
