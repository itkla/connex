'use client';

import { useCallback, useMemo } from 'react';
import { useLocale, useTranslations } from 'next-intl';
import {
    Area,
    AreaChart,
    Bar,
    BarChart,
    CartesianGrid,
    Cell,
    Funnel,
    FunnelChart,
    LabelList,
    Pie,
    PieChart,
    XAxis,
    YAxis,
} from 'recharts';

import type { ReportWidgetData } from '@/app/lib/types';
import {
    ChartContainer,
    ChartTooltip,
    ChartTooltipContent,
    type ChartConfig,
} from '@/components/ui/chart';

const CHART_COLORS = [
    'var(--chart-1)',
    'var(--chart-2)',
    'var(--chart-3)',
    'var(--chart-4)',
    'var(--chart-5)',
];

export function formatReportValue(value: number | null, unit: string | null, locale: string): string {
    if (value == null) return '-';
    if (unit && /^[A-Z]{3}$/.test(unit)) {
        return new Intl.NumberFormat(locale, {
            style: 'currency',
            currency: unit,
            maximumFractionDigits: 0,
            notation: Math.abs(value) >= 10000 ? 'compact' : 'standard',
        }).format(value);
    }
    if (unit === 'percent' || unit === '%') {
        return new Intl.NumberFormat(locale, { maximumFractionDigits: 1 }).format(value) + '%';
    }
    return new Intl.NumberFormat(locale, {
        maximumFractionDigits: 1,
        notation: Math.abs(value) >= 10000 ? 'compact' : 'standard',
    }).format(value);
}

export default function ReportWidgetRenderer({ widget }: { widget: ReportWidgetData }) {
    const t = useTranslations('Reports');
    const locale = useLocale();
    const localizeLabel = useCallback((label: string) => {
        const separator = label.lastIndexOf(' · ');
        const prefix = separator >= 0 ? label.slice(0, separator + 3) : '';
        const value = separator >= 0 ? label.slice(separator + 3) : label;
        if (/^\d{4}-\d{2}(?:-\d{2})?$/.test(value)) {
            const date = new Date(`${value}${value.length === 7 ? '-01' : ''}T00:00:00Z`);
            return prefix + new Intl.DateTimeFormat(locale, {
                timeZone: 'UTC',
                year: 'numeric',
                month: 'short',
                ...(value.length === 10 ? { day: 'numeric' } : {}),
            }).format(date);
        }
        let translated: string;
        switch (value.trim().toLowerCase().replaceAll(' ', '_')) {
            case 'open': translated = t('status.open'); break;
            case 'won': translated = t('status.won'); break;
            case 'lost': translated = t('status.lost'); break;
            case 'todo': translated = t('status.todo'); break;
            case 'in_progress': translated = t('status.in_progress'); break;
            case 'done': translated = t('status.done'); break;
            case 'hot': translated = t('warmth.hot'); break;
            case 'warm': translated = t('warmth.warm'); break;
            case 'cool': translated = t('warmth.cool'); break;
            case 'cold': translated = t('warmth.cold'); break;
            case 'high': translated = t('risk.high'); break;
            case 'medium': translated = t('risk.medium'); break;
            case 'low': translated = t('risk.low'); break;
            case 'rising': translated = t('trend.rising'); break;
            case 'steady': translated = t('trend.steady'); break;
            case 'cooling': translated = t('trend.cooling'); break;
            case 'total': translated = t('label.total'); break;
            case 'unassigned': translated = t('label.unassigned'); break;
            case 'unspecified': translated = t('label.unspecified'); break;
            case 'other': translated = t('label.other'); break;
            default: translated = value;
        }
        return prefix + translated;
    }, [locale, t]);
    const data = useMemo(
        () => widget.points.map((point) => ({
            ...point,
            label: localizeLabel(point.label),
            current: point.value,
            prior: point.priorValue,
        })),
        [widget.points, localizeLabel],
    );
    const config = {
        current: { label: t('document.currentPeriod'), color: 'var(--chart-1)' },
        prior: { label: t('document.priorPeriod'), color: 'var(--chart-2)' },
    } satisfies ChartConfig;

    if (widget.chartType === 'kpi') {
        return (
            <div className="flex min-h-48 flex-col justify-center">
                <p className="text-4xl font-semibold tracking-tight text-foreground tabular-nums">
                    {formatReportValue(widget.total, widget.unit, locale)}
                </p>
                <div className="mt-3 flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
                    <span>{t('document.priorValue', { value: formatReportValue(widget.priorTotal, widget.unit, locale) })}</span>
                    {widget.changePercent != null ? (
                        <span className={widget.changePercent >= 0 ? 'text-brand-dark' : 'text-destructive'}>
                            {t('document.changeValue', {
                                value: new Intl.NumberFormat(locale, { maximumFractionDigits: 1, signDisplay: 'always' }).format(widget.changePercent),
                            })}
                        </span>
                    ) : null}
                </div>
            </div>
        );
    }

    if (data.length === 0) {
        return <div className="flex min-h-48 items-center justify-center text-sm text-muted-foreground">{t('document.noData')}</div>;
    }

    if (widget.chartType === 'table') {
        return (
            <div className="overflow-x-auto">
                <table className="w-full text-left text-sm">
                    <thead className="border-b border-border text-xs uppercase tracking-[0.12em] text-muted-foreground">
                        <tr>
                            <th className="px-3 py-2 font-medium">{t('document.dimension')}</th>
                            <th className="px-3 py-2 text-right font-medium">{t('document.currentPeriod')}</th>
                            <th className="px-3 py-2 text-right font-medium">{t('document.priorPeriod')}</th>
                        </tr>
                    </thead>
                    <tbody className="divide-y divide-border">
                        {data.map((point) => (
                            <tr key={point.key}>
                                <td className="px-3 py-2.5 text-foreground">{point.label}</td>
                                <td className="px-3 py-2.5 text-right tabular-nums text-foreground">
                                    {formatReportValue(point.current, widget.unit, locale)}
                                </td>
                                <td className="px-3 py-2.5 text-right tabular-nums text-muted-foreground">
                                    {formatReportValue(point.prior, widget.unit, locale)}
                                </td>
                            </tr>
                        ))}
                    </tbody>
                </table>
            </div>
        );
    }

    if (widget.chartType === 'donut') {
        return (
            <div>
                <ChartContainer config={config} className="mx-auto aspect-square h-56 max-h-56">
                    <PieChart accessibilityLayer>
                        <ChartTooltip content={<ChartTooltipContent hideLabel />} />
                        <Pie data={data} dataKey="current" nameKey="label" innerRadius="52%" outerRadius="78%" paddingAngle={2}>
                            {data.map((point, index) => (
                                <Cell key={point.key} fill={CHART_COLORS[index % CHART_COLORS.length]} />
                            ))}
                        </Pie>
                    </PieChart>
                </ChartContainer>
                <ul className="mt-3 flex flex-wrap justify-center gap-x-4 gap-y-2 text-xs text-muted-foreground">
                    {data.map((point, index) => (
                        <li key={point.key} className="flex items-center gap-1.5">
                            <span
                                aria-hidden
                                className="size-2 rounded-full"
                                style={{ backgroundColor: CHART_COLORS[index % CHART_COLORS.length] }}
                            />
                            <span>{point.label}</span>
                            <span className="tabular-nums text-foreground">
                                {formatReportValue(point.current, widget.unit, locale)}
                            </span>
                        </li>
                    ))}
                </ul>
            </div>
        );
    }

    if (widget.chartType === 'funnel') {
        return (
            <ChartContainer config={config} className="aspect-auto h-64 w-full">
                <FunnelChart accessibilityLayer>
                    <ChartTooltip content={<ChartTooltipContent />} />
                    <Funnel data={data} dataKey="current" nameKey="label" stroke="var(--card)">
                        {data.map((point, index) => (
                            <Cell key={point.key} fill={CHART_COLORS[index % CHART_COLORS.length]} />
                        ))}
                        <LabelList dataKey="label" position="right" fill="var(--foreground)" fontSize={11} />
                    </Funnel>
                </FunnelChart>
            </ChartContainer>
        );
    }

    if (widget.chartType === 'line-area') {
        return (
            <ChartContainer config={config} className="aspect-auto h-64 w-full">
                <AreaChart data={data} margin={{ top: 12, right: 8, left: -8, bottom: 0 }} accessibilityLayer>
                    <CartesianGrid vertical={false} stroke="var(--chart-grid)" strokeDasharray="3 3" />
                    <XAxis dataKey="label" tickLine={false} axisLine={false} tickMargin={10} />
                    <YAxis tickLine={false} axisLine={false} tickFormatter={(value: number) => formatReportValue(value, widget.unit, locale)} />
                    <ChartTooltip content={<ChartTooltipContent />} />
                    <Area dataKey="prior" type="monotone" stroke="var(--chart-2)" fill="var(--chart-2)" fillOpacity={0.08} strokeWidth={1.5} />
                    <Area dataKey="current" type="monotone" stroke="var(--chart-1)" fill="var(--chart-1)" fillOpacity={0.18} strokeWidth={2} />
                </AreaChart>
            </ChartContainer>
        );
    }

    return (
        <ChartContainer config={config} className="aspect-auto h-64 w-full">
            <BarChart data={data} margin={{ top: 12, right: 8, left: -8, bottom: 0 }} accessibilityLayer>
                <CartesianGrid vertical={false} stroke="var(--chart-grid)" strokeDasharray="3 3" />
                <XAxis dataKey="label" tickLine={false} axisLine={false} tickMargin={10} />
                <YAxis tickLine={false} axisLine={false} tickFormatter={(value: number) => formatReportValue(value, widget.unit, locale)} />
                <ChartTooltip content={<ChartTooltipContent />} />
                <Bar dataKey="current" fill="var(--chart-1)" radius={[6, 6, 0, 0]} />
            </BarChart>
        </ChartContainer>
    );
}
