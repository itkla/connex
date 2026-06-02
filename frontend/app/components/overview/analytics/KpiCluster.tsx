'use client';

import { useLocale, useTranslations } from 'next-intl';
import { ArrowDownIcon, ArrowUpIcon } from '@heroicons/react/16/solid';

import { formatCompactCurrency } from '@/app/lib/utils';
import { type Kpi } from '@/app/components/overview/analytics/metrics';
import InfoTip from '@/app/components/overview/analytics/InfoTip';

function Sparkline({ series, positive }: { series: number[]; positive: boolean }) {
    const w = 80;
    const h = 28;
    const pad = 3;
    const stroke = positive ? 'var(--color-brand)' : '#a3a3a3';
    if (series.length < 2 || series.every((v) => v === 0)) {
        return (
            <svg width={w} height={h} viewBox={`0 0 ${w} ${h}`} aria-hidden className="overflow-visible">
                <line x1={pad} y1={h - pad} x2={w - pad} y2={h - pad} stroke="#e5e5e5" strokeWidth={1.5} />
            </svg>
        );
    }
    const max = Math.max(...series);
    const min = Math.min(...series);
    const range = max - min || 1;
    const step = (w - 2 * pad) / (series.length - 1);
    const points = series.map((v, i) => {
        const x = pad + i * step;
        const y = h - pad - ((v - min) / range) * (h - 2 * pad);
        return [x, y] as const;
    });
    const line = points.map(([x, y], i) => `${i === 0 ? 'M' : 'L'}${x.toFixed(1)} ${y.toFixed(1)}`).join(' ');
    const area = `${line} L${(w - pad).toFixed(1)} ${h - pad} L${pad.toFixed(1)} ${h - pad} Z`;
    const gradientId = `spark-${positive ? 'up' : 'flat'}`;
    return (
        <svg width={w} height={h} viewBox={`0 0 ${w} ${h}`} aria-hidden className="overflow-visible">
            <defs>
                <linearGradient id={gradientId} x1="0" y1="0" x2="0" y2="1">
                    <stop offset="0%" stopColor={stroke} stopOpacity={0.18} />
                    <stop offset="100%" stopColor={stroke} stopOpacity={0} />
                </linearGradient>
            </defs>
            <path d={area} fill={`url(#${gradientId})`} />
            <path d={line} fill="none" stroke={stroke} strokeWidth={1.75} strokeLinecap="round" strokeLinejoin="round" />
            <circle cx={points[points.length - 1][0]} cy={points[points.length - 1][1]} r={2.2} fill={stroke} />
        </svg>
    );
}

function DeltaChip({ kpi }: { kpi: Kpi }) {
    const t = useTranslations('AnalyticsKpis');
    if (kpi.delta == null) {
        return <span className="text-xs text-neutral-500">{t('noBaseline')}</span>;
    }
    const threshold = 0.005;
    const flat = Math.abs(kpi.delta) < threshold;
    const up = kpi.delta > 0;
    const good = flat ? null : up === kpi.goodWhenUp;
    const tone = good == null ? 'text-neutral-500' : good ? 'text-emerald-700' : 'text-red-600';
    const magnitude = Math.round(Math.abs(kpi.delta) * 100);
    const suffix = kpi.deltaKind === 'pp' ? 'pp' : '%';
    const Icon = up ? ArrowUpIcon : ArrowDownIcon;
    return (
        <span className={`inline-flex items-center gap-1 text-xs font-medium tabular-nums ${tone}`}>
            {!flat && <Icon className="size-3" />}
            {magnitude}
            {suffix}
            <span className="font-normal text-neutral-500">{t('vsPrevious')}</span>
        </span>
    );
}

export default function KpiCluster({ kpis, currency }: { kpis: Kpi[]; currency: string }) {
    const t = useTranslations('AnalyticsKpis');
    const locale = useLocale();

    const formatValue = (kpi: Kpi) => {
        if (kpi.format === 'currency') return formatCompactCurrency(kpi.value, currency, locale);
        if (kpi.format === 'percent') return `${Math.round(kpi.value * 100)}%`;
        return t('days', { count: Math.round(kpi.value) });
    };

    return (
        <div className="grid grid-cols-1 gap-px overflow-hidden rounded-2xl bg-neutral-200 ring-1 ring-black/5 sm:grid-cols-2 lg:grid-cols-4">
            {kpis.map((kpi) => (
                <div key={kpi.key} className="flex flex-col gap-3 bg-white p-6">
                    <div className="flex items-start justify-between gap-3">
                        <div className="flex items-center gap-1.5">
                            <span className="text-xs font-medium uppercase tracking-[0.12em] text-neutral-500">
                                {t(kpi.key)}
                            </span>
                            <InfoTip
                                title={t(kpi.key)}
                                label={t('infoAria')}
                                body={
                                    <div className="flex flex-col gap-2">
                                        <p>{t(`${kpi.key}Tooltip`)}</p>
                                        <p>{t('comparisonNote')}</p>
                                    </div>
                                }
                            />
                        </div>
                        <Sparkline series={kpi.series} positive={kpi.goodWhenUp} />
                    </div>
                    <span className="text-3xl leading-none text-black tabular-nums">{formatValue(kpi)}</span>
                    <DeltaChip kpi={kpi} />
                </div>
            ))}
        </div>
    );
}