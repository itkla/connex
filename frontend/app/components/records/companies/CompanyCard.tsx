'use client';

import { Loader2Icon } from 'lucide-react';
import { useRouter } from 'next/navigation';
import { useLocale, useTranslations } from 'next-intl';
import { ChevronRightIcon } from '@heroicons/react/24/solid';
import { Button } from '@/components/ui/button';
import { type Company, type CompanyMetrics, type LoadStatus } from '@/app/lib/types';
import CompanyAvatar from '@/app/components/records/companies/CompanyAvatar';
import { AvatarFallback, Avatar, AvatarGroup, AvatarGroupCount, AvatarImage } from '@/components/ui/avatar';
import { useMemo, useState } from 'react';
import { Area, AreaChart, LabelList, ResponsiveContainer, Tooltip as RechartsTooltip, XAxis } from 'recharts';
import { formatCompactCurrency } from '@/app/lib/utils';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';

interface CompanyCardProps {
    company: Company;
    metrics?: CompanyMetrics;
    metricsStatus: LoadStatus;
    onFirstExpand?: () => void;
    onQuickEdit?: () => void;
    onDelete?: () => void;
}

export default function CompanyCard({ company, metrics, metricsStatus, onFirstExpand }: CompanyCardProps) {
    const [isExpanded, setIsExpanded] = useState(false);
    const router = useRouter();
    const t = useTranslations('CompaniesCard');

    const open = () => router.push(`/records/companies/${company.id}`);
    const toggleExpand = () => {
        if (!isExpanded) onFirstExpand?.();
        setIsExpanded((prev) => !prev);
    };

    return (
        <div className="group rounded-2xl bg-white ring-1 ring-black/5 transition duration-200 hover:ring-black/10 hover:shadow-[0_10px_30px_-12px_rgb(0_0_0/0.18)]">
            <div
                className="flex cursor-pointer items-center gap-4 rounded-2xl p-4 transition-colors hover:bg-neutral-50"
                onClick={toggleExpand}
            >
                <CompanyAvatar company={company} type="large" />

                <div className="flex-1 min-w-0">
                    <h3 className="text-base font-semibold text-neutral-900 truncate">
                        {company.name}
                    </h3>
                    {company.industry && (
                        <p className="mt-0.5 text-sm text-neutral-500 truncate">
                            {company.industry}
                        </p>
                    )}
                </div>

                <Button
                    variant="outline"
                    size="sm"
                    onClick={(e) => {
                        e.stopPropagation();
                        open();
                    }}
                    aria-label={t('openCompanyAriaLabel')}
                    className="size-10 shrink-0 border-none bg-neutral-100 text-neutral-500 shadow-none outline-none hover:bg-neutral-200 hover:text-neutral-700"
                >
                    <ChevronRightIcon className="size-4" />
                </Button>
            </div>

            {isExpanded && (
                <div className="border-t border-neutral-100 p-4 space-y-4">
                    {metricsStatus === 'loading' && (
                        <div className="flex items-center justify-center py-4 text-sm text-neutral-500">
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
                                <AvatarSection kind="employees" label={t('employees')} people={metrics.persons} fallbackKey="name" imageKey="imageUrl" />
                                <AvatarSection kind="relations" label={t('relations')} people={metrics.relatedUsers} fallbackKey="displayName" imageKey="profilePictureUrl" />
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
    fallbackKey,
    imageKey,
}: {
    kind: 'employees' | 'relations';
    label: string;
    people: AvatarSubject[];
    fallbackKey: 'name' | 'displayName';
    imageKey: 'imageUrl' | 'profilePictureUrl';
}) {
    const visible = people.slice(0, 5);
    const overflow = people.length - visible.length;

    //TODO: clean up the avatar section so that they're distinct
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
            <p className="text-xs font-medium tracking-[0.12em] text-neutral-500 uppercase mb-2">
                {t('labelWithCount', { label, count: people.length })}
            </p>
            <AvatarGroup>
                {visible.map((p) => {
                    const fallback = (p[fallbackKey] ?? '?').charAt(0);
                    return (
                        <Tooltip key={p.id}>
                            <TooltipTrigger asChild>
                                <Avatar key={p.id} className="border-white w-10 h-10 cursor-pointer hover:scale-110 transition-all duration-300 bg-white" onClick={() => handleClick(p.id)}>
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
                    <AvatarGroupCount className="ring-transparent bg-neutral-200 text-neutral-600 w-10 h-10">
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
    const total = data.reduce((s, d) => s + d.count, 0);
    return (
        <div className="md:col-span-2 rounded-xl bg-transparent p-3 ring-1 ring-black/5">
            <p className="text-xs uppercase tracking-wider text-neutral-500">{t('engagement12w')}</p>
            {total === 0 ? (
                <p className="text-xs text-neutral-400 mt-3">{t('noEngagement')}</p>
            ) : (
                <div className="h-28 mt-1">
                    <ResponsiveContainer width="100%" height="100%">
                        <AreaChart data={data} margin={{ top: 14, right: 8, bottom: 0, left: 8 }}>
                            <XAxis
                                dataKey="weekStart"
                                tickFormatter={(ts: number) => dateFormatter.format(new Date(ts))}
                                tick={{ fontSize: 10, fill: '#737373' }}
                                tickLine={false}
                                axisLine={false}
                                interval={1}
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
                                    fill="#404040"
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
            <div className="rounded-xl bg-transparent p-3 ring-1 ring-black/5">
                <p className="text-xs uppercase tracking-wider text-neutral-500">{t('closedRevenue')}</p>
                <p className="mt-1 text-lg font-semibold text-neutral-900">
                    {formatCompactCurrency(pastRevenue, currency, locale)}
                </p>
            </div>
            <div className="rounded-xl bg-transparent p-3 ring-1 ring-black/5">
                <p className="text-xs uppercase tracking-wider text-neutral-500">{t('projected')}</p>
                <p className="mt-1 text-lg font-semibold text-neutral-900">
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
        <div className="rounded-md bg-white p-2 text-xs ring-1 ring-black/5 shadow-md">
            <div className="font-medium text-neutral-700 mb-1.5">
                {dateFormatter.format(new Date(d.weekStart))}
            </div>
            <div className="space-y-0.5">
                <div className="flex items-center gap-1.5 text-neutral-600">
                    <span className="inline-block size-2 rounded-sm bg-brand" />
                    {t('tooltipActivities', { count: d.activities })}
                </div>
                <div className="flex items-center gap-1.5 text-neutral-600">
                    <span className="inline-block size-2 rounded-sm bg-amber-500" />
                    {t('tooltipTasks', { count: d.tasks })}
                </div>
                <div className="flex items-center gap-1.5 text-neutral-600">
                    <span className="inline-block size-2 rounded-sm bg-emerald-500" />
                    {t('tooltipNotes', { count: d.notes })}
                </div>
            </div>
        </div>
    );
}