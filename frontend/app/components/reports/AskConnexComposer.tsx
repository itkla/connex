'use client';

import { useState } from 'react';
import { useRouter } from 'next/navigation';
import { useLocale, useTranslations } from 'next-intl';
import {
    ArrowPathIcon,
    CalendarDaysIcon,
    CheckCircleIcon,
    CircleStackIcon,
    FunnelIcon,
    SparklesIcon,
} from '@heroicons/react/24/outline';

import { createReport, previewReportComposer } from '@/app/lib/api';
import { toastError, toastSuccess } from '@/app/lib/toast';
import type { ReportComposerPreview } from '@/app/lib/types';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Textarea } from '@/components/ui/textarea';

export default function AskConnexComposer() {
    const t = useTranslations('Reports.composer');
    const reportsT = useTranslations('Reports');
    const locale = useLocale();
    const router = useRouter();
    const [prompt, setPrompt] = useState('');
    const [preview, setPreview] = useState<ReportComposerPreview | null>(null);
    const [unavailableReason, setUnavailableReason] = useState<string | null>(null);
    const [generating, setGenerating] = useState(false);
    const [saving, setSaving] = useState(false);
    const localizedDefinition = preview?.definition
        ? {
            ...preview.definition,
            name: preview.definition.templateKey
                ? reportsT(`templates.${preview.definition.templateKey}.name`)
                : reportsT(`measure.${preview.definition.config.widgets[0]?.measure ?? 'count'}`),
            description: preview.definition.templateKey
                ? reportsT(`templates.${preview.definition.templateKey}.description`)
                : t('generatedDescription'),
            config: {
                ...preview.definition.config,
                widgets: preview.definition.config.widgets.map((widget) => ({
                    ...widget,
                    title: reportsT(`measure.${widget.measure}`),
                })),
            },
        }
        : null;

    const generatePreview = async () => {
        const request = prompt.trim();
        if (!request || generating) return;
        setGenerating(true);
        setUnavailableReason(null);
        try {
            const result = await previewReportComposer(request);
            if (!result.available || !result.definition) {
                setPreview(null);
                setUnavailableReason(result.reason ?? 'provider_error');
                return;
            }
            setPreview(result);
        } catch (error) {
            toastError(error instanceof Error ? error.message : reportsT('common.requestFailed'));
        } finally {
            setGenerating(false);
        }
    };

    const savePreview = async () => {
        if (!localizedDefinition || saving) return;
        setSaving(true);
        try {
            const created = await createReport(localizedDefinition);
            toastSuccess(t('created'));
            router.push(`/overview/reports/${created.id}`);
        } catch (error) {
            toastError(error instanceof Error ? error.message : reportsT('common.requestFailed'));
            setSaving(false);
        }
    };

    return (
        <section aria-labelledby="ask-connex-title" className="overflow-hidden rounded-2xl border border-brand/25 bg-card">
            <div className="grid gap-0 lg:grid-cols-[minmax(0,0.9fr)_minmax(0,1.1fr)]">
                <div className="flex flex-col justify-between gap-8 bg-brand-light/35 p-6 sm:p-8">
                    <div>
                        <div className="flex size-11 items-center justify-center rounded-xl bg-brand text-brand-foreground">
                            <SparklesIcon className="size-5" />
                        </div>
                        <p className="mt-5 text-xs font-medium uppercase tracking-[0.12em] text-brand-dark">
                            {t('eyebrow')}
                        </p>
                        <h2 id="ask-connex-title" className="mt-2 text-2xl font-bold tracking-tight text-foreground">
                            {t('title')}
                        </h2>
                        <p className="mt-2 max-w-xl text-sm leading-relaxed text-muted-foreground">
                            {t('subtitle')}
                        </p>
                    </div>
                    <p className="flex items-start gap-2 text-xs leading-relaxed text-muted-foreground">
                        <CheckCircleIcon className="mt-0.5 size-4 shrink-0 text-brand-dark" />
                        {t('grounding')}
                    </p>
                </div>

                <div className="p-6 sm:p-8">
                    <label htmlFor="report-request" className="text-sm font-semibold text-foreground">
                        {t('requestLabel')}
                    </label>
                    <Textarea
                        id="report-request"
                        value={prompt}
                        onChange={(event) => {
                            setPrompt(event.target.value);
                            setPreview(null);
                            setUnavailableReason(null);
                        }}
                        placeholder={t('placeholder')}
                        maxLength={1200}
                        rows={4}
                        className="mt-2 resize-none"
                        disabled={generating || saving}
                    />
                    <div className="mt-3 flex flex-wrap items-center justify-between gap-3">
                        <p className="text-xs text-muted-foreground">{t('requestHint')}</p>
                        <Button
                            variant="brand"
                            onClick={generatePreview}
                            disabled={!prompt.trim() || generating || saving}
                        >
                            {generating ? (
                                <ArrowPathIcon className="animate-spin motion-reduce:animate-none" />
                            ) : (
                                <SparklesIcon />
                            )}
                            {generating ? t('generating') : preview ? t('regenerate') : t('generate')}
                        </Button>
                    </div>

                    {unavailableReason ? (
                        <div role="status" className="mt-6 rounded-xl border border-border bg-muted/40 p-4">
                            <p className="font-medium text-foreground">{t('unavailableTitle')}</p>
                            <p className="mt-1 text-sm text-muted-foreground">
                                {t(`unavailable.${unavailableReason}`)}
                            </p>
                        </div>
                    ) : null}

                    {preview?.definition && localizedDefinition ? (
                        <div className="mt-8 border-t border-border pt-6">
                            <div className="flex flex-wrap items-start justify-between gap-4">
                                <div>
                                    <p className="text-xs font-medium uppercase tracking-[0.12em] text-muted-foreground">
                                        {t('previewEyebrow')}
                                    </p>
                                    <h3 className="mt-1 text-xl font-bold tracking-tight text-foreground">
                                        {localizedDefinition.name}
                                    </h3>
                                    {localizedDefinition.description ? (
                                        <p className="mt-1 max-w-2xl text-sm leading-relaxed text-muted-foreground">
                                            {localizedDefinition.description}
                                        </p>
                                    ) : null}
                                </div>
                                <Badge variant="secondary">
                                    {reportsT(`cadence.${localizedDefinition.cadence}`)}
                                </Badge>
                            </div>

                            <div className="mt-6 grid gap-5 sm:grid-cols-3">
                                <PreviewFact
                                    icon={CircleStackIcon}
                                    label={t('fields')}
                                    value={t('fieldCount', { count: preview.evidence.length })}
                                />
                                <PreviewFact
                                    icon={FunnelIcon}
                                    label={t('filters')}
                                    value={filterSummary(preview, reportsT, t)}
                                />
                                <PreviewFact
                                    icon={CalendarDaysIcon}
                                    label={t('dateRange')}
                                    value={rangeSummary(preview)}
                                />
                            </div>

                            <div className="mt-6">
                                <h4 className="text-sm font-semibold text-foreground">{t('evidence')}</h4>
                                <ul className="mt-3 divide-y divide-border rounded-xl border border-border">
                                    {preview.evidence.map((item) => (
                                        <li key={item.widgetId} className="flex flex-wrap items-center gap-x-2 gap-y-1 px-4 py-3 text-sm">
                                            <span className="font-medium text-foreground">
                                                {reportsT(`measure.${item.measure}`)}
                                            </span>
                                            <span className="text-muted-foreground">
                                                {reportsT(`source.${item.dataSource}`)} · {reportsT(`group.${item.groupBy}`)}
                                            </span>
                                            <Badge variant="outline" className="ml-auto font-normal">
                                                {reportsT(`chart.${item.chartType}`)}
                                            </Badge>
                                        </li>
                                    ))}
                                </ul>
                            </div>

                            <div className="mt-6 grid gap-5 sm:grid-cols-2">
                                <div>
                                    <h4 className="text-sm font-semibold text-foreground">{t('scope')}</h4>
                                    <p className="mt-2 text-sm text-muted-foreground">{t('scopeValue')}</p>
                                </div>
                                <div>
                                    <h4 className="text-sm font-semibold text-foreground">{t('assumptions')}</h4>
                                    <ul className="mt-2 space-y-1 text-sm text-muted-foreground">
                                        {preview.assumptionCodes.map((code) => (
                                            <li key={code}>• {t(`assumption.${code}`)}</li>
                                        ))}
                                    </ul>
                                </div>
                            </div>

                            <div className="mt-6 flex flex-wrap items-center justify-between gap-3 border-t border-border pt-5">
                                <p className="text-xs text-muted-foreground">
                                    {t('freshness', {
                                        date: preview.generatedAt
                                            ? new Intl.DateTimeFormat(locale, {
                                                dateStyle: 'medium',
                                                timeStyle: 'short',
                                            }).format(new Date(preview.generatedAt))
                                            : '',
                                    })}
                                </p>
                                <Button variant="brand" onClick={savePreview} disabled={saving}>
                                    {saving ? <ArrowPathIcon className="animate-spin motion-reduce:animate-none" /> : null}
                                    {saving ? t('saving') : t('save')}
                                </Button>
                            </div>
                        </div>
                    ) : null}
                </div>
            </div>
        </section>
    );
}

function PreviewFact({
    icon: Icon,
    label,
    value,
}: {
    icon: typeof CalendarDaysIcon;
    label: string;
    value: string;
}) {
    return (
        <div className="flex items-start gap-3">
            <Icon className="mt-0.5 size-4 shrink-0 text-brand-dark" />
            <div>
                <p className="text-xs font-medium uppercase tracking-[0.08em] text-muted-foreground">{label}</p>
                <p className="mt-1 text-sm text-foreground">{value}</p>
            </div>
        </div>
    );
}

function filterSummary(
    preview: ReportComposerPreview,
    reportsT: ReturnType<typeof useTranslations<'Reports'>>,
    t: ReturnType<typeof useTranslations<'Reports.composer'>>,
): string {
    const filters = preview.definition?.config.filters;
    const values = [
        ...(filters?.statuses ?? []).map((status) => reportsT(`status.${status}`)),
        ...(filters?.warmthBands ?? []).map((band) => reportsT(`warmth.${band}`)),
    ];
    return values.length > 0 ? values.join(', ') : t('allCompatible');
}

function rangeSummary(preview: ReportComposerPreview): string {
    if (preview.effectiveRange) {
        return `${preview.effectiveRange.start} – ${preview.effectiveRange.end}`;
    }
    return '';
}
