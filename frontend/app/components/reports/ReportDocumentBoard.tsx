'use client';

import Link from 'next/link';
import { useEffect, useRef, useState, type RefObject } from 'react';
import { useLocale, useTranslations } from 'next-intl';
import {
    ArchiveBoxArrowDownIcon,
    ArrowDownTrayIcon,
    ArrowPathIcon,
    ClockIcon,
    DocumentTextIcon,
    PencilSquareIcon,
    PhotoIcon,
    PrinterIcon,
    TrashIcon,
} from '@heroicons/react/24/outline';

import ReportWidgetRenderer, { formatReportValue } from '@/app/components/reports/ReportWidgetRenderer';
import {
    createReportSnapshot,
    deleteReportSnapshot,
    exportReportCsv,
    exportReportSnapshotCsv,
    generateReport,
    getReportSnapshot,
} from '@/app/lib/api';
import { toastError, toastSuccess } from '@/app/lib/toast';
import type {
    ReportCitation,
    ReportDefinition,
    ReportDocument,
    ReportGenerateInput,
    ReportNarrativeClaim,
    ReportSnapshot,
    ReportSnapshotSummary,
} from '@/app/lib/types';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Skeleton } from '@/components/ui/skeleton';

type DocumentState =
    | { status: 'loading' }
    | { status: 'error' }
    | { status: 'ready'; document: ReportDocument };

function isValidAttainmentRange(start: string, end: string, cadence: ReportDefinition['cadence']): boolean {
    const match = /^(\d{4})-(\d{2})-01$/.exec(start);
    if (!match || end < start) return false;
    const year = Number(match[1]);
    const month = Number(match[2]);
    if (cadence === 'quarterly' && ![1, 4, 7, 10].includes(month)) return false;
    const months = cadence === 'quarterly' ? 3 : 1;
    const nextPeriod = new Date(Date.UTC(year, month - 1 + months, 1)).toISOString().slice(0, 10);
    return end < nextPeriod;
}

export default function ReportDocumentBoard({
    definition,
    initialSnapshots,
}: {
    definition: ReportDefinition;
    initialSnapshots: ReportSnapshotSummary[];
}) {
    const t = useTranslations('Reports');
    const locale = useLocale();
    const [state, setState] = useState<DocumentState>({ status: 'loading' });
    const [snapshots, setSnapshots] = useState<ReportSnapshotSummary[]>(initialSnapshots);
    const [activeSnapshotId, setActiveSnapshotId] = useState<number | null>(null);
    const [activeSnapshot, setActiveSnapshot] = useState<ReportSnapshot | null>(null);
    const [start, setStart] = useState('');
    const [end, setEnd] = useState('');
    const [refreshKey, setRefreshKey] = useState(0);
    const [snapshotting, setSnapshotting] = useState(false);
    const [deletingSnapshotId, setDeletingSnapshotId] = useState<number | null>(null);
    const [exporting, setExporting] = useState(false);
    const [exportingPng, setExportingPng] = useState(false);
    const generationInputRef = useRef<ReportGenerateInput>({});
    const snapshotRequestRef = useRef(0);
    const paperRef = useRef<HTMLElement>(null);
    const hasAttainment = definition.config.widgets.some((widget) => widget.measure === 'attainment');

    const document = activeSnapshotId != null
        ? activeSnapshot?.computedResult ?? null
        : state.status === 'ready' ? state.document : null;

    useEffect(() => {
        let cancelled = false;
        generateReport(definition.id, generationInputRef.current)
            .then((generated) => {
                if (cancelled) return;
                setState({ status: 'ready', document: generated });
                setStart(generated.periodStart);
                setEnd(generated.periodEnd);
            })
            .catch(() => {
                if (!cancelled) setState({ status: 'error' });
            });
        return () => {
            cancelled = true;
        };
    }, [definition.id, refreshKey]);

    const generationInput = (): ReportGenerateInput | null => {
        if ((start && !end) || (!start && end)) {
            toastError(t('document.rangePairRequired'));
            return null;
        }
        if (hasAttainment && start && end && !isValidAttainmentRange(start, end, definition.cadence)) {
            toastError(t('document.attainmentRangeRequired'));
            return null;
        }
        return start && end ? { start, end } : {};
    };

    const generate = () => {
        const input = generationInput();
        if (!input) return;
        generationInputRef.current = input;
        snapshotRequestRef.current += 1;
        setActiveSnapshotId(null);
        setActiveSnapshot(null);
        setState({ status: 'loading' });
        setRefreshKey((key) => key + 1);
    };

    const createSnapshot = async () => {
        const input = generationInput();
        if (!input) return;
        setSnapshotting(true);
        try {
            const snapshot = await createReportSnapshot(definition.id, input);
            setSnapshots((current) => [snapshot, ...current]);
            setActiveSnapshotId(snapshot.id);
            setActiveSnapshot(snapshot);
            toastSuccess(t('document.snapshotCreated'));
        } catch (error) {
            toastError(error instanceof Error ? error.message : t('common.requestFailed'));
        } finally {
            setSnapshotting(false);
        }
    };

    const removeSnapshot = async (snapshot: ReportSnapshotSummary) => {
        if (!window.confirm(t('document.deleteSnapshotConfirm'))) return;
        setDeletingSnapshotId(snapshot.id);
        if (activeSnapshotId === snapshot.id) {
            snapshotRequestRef.current += 1;
        }
        try {
            await deleteReportSnapshot(definition.id, snapshot.id);
            setSnapshots((current) => current.filter((item) => item.id !== snapshot.id));
            if (activeSnapshotId === snapshot.id) {
                setActiveSnapshotId(null);
                setActiveSnapshot(null);
            }
            toastSuccess(t('document.snapshotDeleted'));
        } catch (error) {
            toastError(error instanceof Error ? error.message : t('common.requestFailed'));
        } finally {
            setDeletingSnapshotId(null);
        }
    };

    const openSnapshot = async (snapshot: ReportSnapshotSummary) => {
        const requestId = snapshotRequestRef.current + 1;
        snapshotRequestRef.current = requestId;
        setActiveSnapshotId(snapshot.id);
        setActiveSnapshot(null);
        try {
            const loaded = await getReportSnapshot(definition.id, snapshot.id);
            if (snapshotRequestRef.current === requestId) {
                setActiveSnapshot(loaded);
            }
        } catch (error) {
            if (snapshotRequestRef.current === requestId) {
                setActiveSnapshotId(null);
                toastError(error instanceof Error ? error.message : t('common.requestFailed'));
            }
        }
    };

    const exportCsv = async () => {
        const input = activeSnapshot ? null : generationInput();
        if (!activeSnapshot && !input) return;
        setExporting(true);
        try {
            if (activeSnapshot) {
                await exportReportSnapshotCsv(definition.id, activeSnapshot.id);
            } else {
                await exportReportCsv(definition.id, input ?? {}, `${definition.name}.csv`);
            }
        } catch (error) {
            toastError(error instanceof Error ? error.message : t('common.requestFailed'));
        } finally {
            setExporting(false);
        }
    };

    const exportPng = async () => {
        if (!paperRef.current) return;
        setExportingPng(true);
        try {
            await exportElementPng(paperRef.current, `${definition.name}.png`);
        } catch {
            toastError(t('document.pngFailed'));
        } finally {
            setExportingPng(false);
        }
    };

    return (
        <div className="report-page min-h-full bg-muted/30 px-2 pb-16 pt-8">
            <div className="mx-auto w-full max-w-[100rem]">
                <div className="report-controls mb-6 flex flex-wrap items-end justify-between gap-4 rounded-2xl border border-border bg-card p-4">
                    <div className="flex flex-wrap items-end gap-3">
                        <div className="space-y-1.5">
                            <Label htmlFor="report-live-start">{t('builder.startDate')}</Label>
                            <Input id="report-live-start" type="date" value={start} onChange={(event) => setStart(event.target.value)} />
                        </div>
                        <div className="space-y-1.5">
                            <Label htmlFor="report-live-end">{t('builder.endDate')}</Label>
                            <Input id="report-live-end" type="date" value={end} onChange={(event) => setEnd(event.target.value)} />
                        </div>
                        <Button variant="outline" onClick={generate}>
                            <ArrowPathIcon />
                            {t('document.run')}
                        </Button>
                    </div>
                    <div className="flex flex-wrap items-center gap-2">
                        <Button variant="outline" onClick={createSnapshot} disabled={!document || snapshotting}>
                            <ArchiveBoxArrowDownIcon />
                            {snapshotting ? t('document.snapshotting') : t('document.snapshot')}
                        </Button>
                        <Button variant="outline" onClick={exportCsv} disabled={!document || exporting}>
                            <ArrowDownTrayIcon />
                            {t('document.csv')}
                        </Button>
                        <Button variant="outline" onClick={exportPng} disabled={!document || exportingPng}>
                            <PhotoIcon />
                            {exportingPng ? t('document.exportingPng') : t('document.png')}
                        </Button>
                        <Button variant="outline" onClick={() => window.print()} disabled={!document}>
                            <PrinterIcon />
                            {t('document.pdf')}
                        </Button>
                        <Button asChild variant="brand">
                            <Link href={`/overview/reports/${definition.id}/edit`}>
                                <PencilSquareIcon />
                                {t('common.edit')}
                            </Link>
                        </Button>
                    </div>
                </div>

                {snapshots.length > 0 ? (
                    <section className="report-controls mb-6 rounded-2xl border border-border bg-card p-4" aria-labelledby="snapshots-title">
                        <div className="flex items-center gap-2">
                            <ClockIcon className="size-4 text-muted-foreground" />
                            <h2 id="snapshots-title" className="text-sm font-semibold text-foreground">{t('document.snapshots')}</h2>
                        </div>
                        <div className="mt-3 flex gap-2 overflow-x-auto pb-1">
                            <Button
                                variant={activeSnapshotId == null ? 'secondary' : 'ghost'}
                                size="sm"
                                onClick={() => {
                                    snapshotRequestRef.current += 1;
                                    setActiveSnapshotId(null);
                                    setActiveSnapshot(null);
                                }}
                            >
                                {t('document.live')}
                            </Button>
                            {snapshots.map((snapshot) => (
                                <div key={snapshot.id} className="flex shrink-0 items-center rounded-md border border-border">
                                    <button
                                        type="button"
                                        onClick={() => openSnapshot(snapshot)}
                                        aria-pressed={activeSnapshotId === snapshot.id}
                                        className={activeSnapshotId === snapshot.id
                                            ? 'bg-muted px-3 py-1.5 text-sm font-medium text-foreground'
                                            : 'px-3 py-1.5 text-sm text-foreground hover:bg-muted'}
                                    >
                                        {new Intl.DateTimeFormat(locale, { dateStyle: 'medium' }).format(new Date(snapshot.generatedAt))}
                                    </button>
                                    <button
                                        type="button"
                                        onClick={() => removeSnapshot(snapshot)}
                                        disabled={deletingSnapshotId === snapshot.id}
                                        aria-label={t('document.deleteSnapshotNamed', {
                                            date: new Intl.DateTimeFormat(locale, { dateStyle: 'medium' })
                                                .format(new Date(snapshot.generatedAt)),
                                        })}
                                        className="border-l border-border p-2 text-muted-foreground hover:bg-destructive/10 hover:text-destructive"
                                    >
                                        <TrashIcon className="size-3.5" />
                                    </button>
                                </div>
                            ))}
                        </div>
                    </section>
                ) : null}

                {activeSnapshotId != null && activeSnapshot == null ? (
                    <ReportDocumentSkeleton />
                ) : activeSnapshot == null && state.status === 'loading' ? (
                    <ReportDocumentSkeleton />
                ) : activeSnapshot == null && state.status === 'error' ? (
                    <div className="rounded-2xl border border-border bg-card px-6 py-20 text-center">
                        <DocumentTextIcon className="mx-auto size-8 text-muted-foreground" />
                        <h2 className="mt-4 text-lg font-semibold text-foreground">{t('document.errorTitle')}</h2>
                        <p className="mt-1 text-sm text-muted-foreground">{t('document.errorBody')}</p>
                        <Button className="mt-6" variant="outline" onClick={generate}>
                            <ArrowPathIcon />
                            {t('common.retry')}
                        </Button>
                    </div>
                ) : document ? (
                    <ReportPaper document={document} snapshot={activeSnapshot} paperRef={paperRef} />
                ) : null}
            </div>
        </div>
    );
}

function ReportPaper({
    document,
    snapshot,
    paperRef,
}: {
    document: ReportDocument;
    snapshot: ReportSnapshot | null;
    paperRef: RefObject<HTMLElement | null>;
}) {
    const t = useTranslations('Reports');
    const locale = useLocale();
    const citationIndex = new Map(document.citations.map((citation, index) => [citation.sourceId, index + 1]));
    const layoutById = new Map(document.definition.config.layout.map((item) => [item.widgetId, item]));
    const measureLabelByWidgetId = new Map(
        document.widgets.map((widget) => [widget.widgetId, t(`measure.${widget.measure}`)]),
    );
    const widgetTitleById = new Map(document.definition.config.widgets.map((widget) => [
        widget.id,
        widget.title?.trim() || t(`measure.${widget.measure}`),
    ]));
    const measureByWidgetId = new Map(document.widgets.map((widget) => [widget.widgetId, widget.measure]));
    const hasComparison = document.widgets.some((widget) => widget.measure !== 'attainment' && (
        widget.priorTotal != null || widget.points.some((point) => point.priorValue != null)));
    const hasAttainmentRows = document.appendix.some((row) => measureByWidgetId.get(row.widgetId) === 'attainment');
    const hasNonAttainmentRows = document.appendix.some((row) => measureByWidgetId.get(row.widgetId) !== 'attainment');
    const hasMixedSemantics = hasAttainmentRows && hasNonAttainmentRows;
    const hasPriorRows = document.appendix.some((row) =>
        measureByWidgetId.get(row.widgetId) !== 'attainment' && row.priorValue != null);
    const localizedSourceLabel = (label: string, widgetId: string) => {
        const display = sourceDisplayLabel(label, measureLabelByWidgetId.get(widgetId));
        const separator = display.lastIndexOf(' · ');
        const prefix = separator >= 0 ? display.slice(0, separator + 3) : '';
        const value = separator >= 0 ? display.slice(separator + 3) : display;
        const key = value.trim().toLowerCase().replaceAll(' ', '_');
        if (/^\d{4}-\d{2}(?:-\d{2})?$/.test(value)) {
            const date = new Date(`${value}${value.length === 7 ? '-01' : ''}T00:00:00Z`);
            return prefix + new Intl.DateTimeFormat(locale, {
                timeZone: 'UTC',
                year: 'numeric',
                month: 'short',
                ...(value.length === 10 ? { day: 'numeric' } : {}),
            }).format(date);
        }
        const translated = (() => {
            switch (key) {
                case 'open': return t('status.open');
                case 'won': return t('status.won');
                case 'lost': return t('status.lost');
                case 'todo': return t('status.todo');
                case 'in_progress': return t('status.in_progress');
                case 'done': return t('status.done');
                case 'hot': return t('warmth.hot');
                case 'warm': return t('warmth.warm');
                case 'cool': return t('warmth.cool');
                case 'cold': return t('warmth.cold');
                case 'high': return t('risk.high');
                case 'medium': return t('risk.medium');
                case 'low': return t('risk.low');
                case 'rising': return t('trend.rising');
                case 'steady': return t('trend.steady');
                case 'cooling': return t('trend.cooling');
                case 'total': return t('label.total');
                case 'unassigned': return t('label.unassigned');
                case 'unspecified': return t('label.unspecified');
                case 'other': return t('label.other');
                case 'workspace-wide': return t('label.workspaceWide');
                default: return value;
            }
        })();
        return prefix + translated;
    };
    return (
        <article ref={paperRef} className="report-paper mx-auto max-w-6xl rounded-2xl border border-border bg-card px-6 py-10 shadow-sm sm:px-10 lg:px-16">
            <header className="border-b border-border pb-8">
                <div className="flex flex-wrap items-start justify-between gap-6">
                    <div className="max-w-3xl">
                        <p className="text-xs font-medium uppercase tracking-[0.16em] text-brand-dark">{t('document.reportLabel')}</p>
                        <h1 className="mt-3 text-4xl font-extrabold tracking-tight text-foreground sm:text-5xl">
                            {document.definition.name}
                        </h1>
                        {document.definition.description ? (
                            <p className="mt-4 max-w-2xl text-base leading-relaxed text-muted-foreground">
                                {document.definition.description}
                            </p>
                        ) : null}
                    </div>
                    <dl className="grid shrink-0 gap-3 text-sm">
                        <div>
                            <dt className="text-xs uppercase tracking-[0.12em] text-muted-foreground">{t('document.period')}</dt>
                            <dd className="mt-1 font-medium text-foreground">{formatRange(document.periodStart, document.periodEnd, locale)}</dd>
                        </div>
                        {hasComparison ? (
                            <div>
                                <dt className="text-xs uppercase tracking-[0.12em] text-muted-foreground">{t('document.comparison')}</dt>
                                <dd className="mt-1 text-foreground">{formatRange(document.priorPeriodStart, document.priorPeriodEnd, locale)}</dd>
                            </div>
                        ) : null}
                        {snapshot ? (
                            <div>
                                <dt className="text-xs uppercase tracking-[0.12em] text-muted-foreground">{t('document.snapshot')}</dt>
                                <dd className="mt-1 text-foreground">{formatDateTime(snapshot.generatedAt, locale)}</dd>
                            </div>
                        ) : null}
                    </dl>
                </div>
            </header>

            <section className="mt-10" aria-labelledby="executive-summary-title">
                <SectionHeading id="executive-summary-title" title={t('document.executiveSummary')} />
                {document.narrative.available && document.narrative.sections.length > 0 ? (
                    <div className="mt-6 grid gap-8 lg:grid-cols-[minmax(0,2fr)_minmax(16rem,1fr)]">
                        <div className="space-y-8">
                            {document.narrative.sections.map((section) => (
                                <div key={section.title}>
                                    <h3 className="text-lg font-semibold text-foreground">{section.title}</h3>
                                    <div className="mt-3 space-y-3">
                                        {section.claims.map((claim, index) => (
                                            <NarrativeClaim key={`${section.title}-${index}`} claim={claim} citationIndex={citationIndex} />
                                        ))}
                                    </div>
                                </div>
                            ))}
                        </div>
                        <aside className="rounded-2xl bg-muted/60 p-5">
                            <h3 className="text-xs font-medium uppercase tracking-[0.12em] text-muted-foreground">{t('document.findings')}</h3>
                            <div className="mt-4 space-y-4">
                                {document.narrative.findings.map((claim, index) => (
                                    <NarrativeClaim key={index} claim={claim} citationIndex={citationIndex} compact />
                                ))}
                            </div>
                        </aside>
                    </div>
                ) : (
                    <div className="mt-6 rounded-2xl border border-dashed border-border px-5 py-8">
                        <p className="text-sm font-medium text-foreground">{t('document.narrativeUnavailable')}</p>
                        <p className="mt-1 text-sm text-muted-foreground">{t('document.narrativeUnavailableBody')}</p>
                    </div>
                )}
            </section>

            <section className="mt-12" aria-labelledby="figures-title">
                <SectionHeading id="figures-title" title={t('document.figures')} />
                <div className="mt-6 grid grid-cols-1 gap-5 lg:grid-cols-2">
                    {document.widgets.map((widget) => (
                        <section
                            key={widget.widgetId}
                            className={(layoutById.get(widget.widgetId)?.width ?? 6) >= 12
                                ? 'rounded-2xl border border-border p-5 lg:col-span-2'
                                : 'rounded-2xl border border-border p-5'}
                        >
                            <div className="mb-5 flex flex-wrap items-start justify-between gap-3">
                                <div>
                                    <h3 className="font-semibold text-foreground">
                                        {widgetTitleById.get(widget.widgetId) ?? widget.title}
                                    </h3>
                                    <p className="mt-1 text-xs text-muted-foreground">
                                        {t('document.widgetScope', {
                                            source: t(`source.${widget.dataSource}`),
                                            group: t(`group.${widget.groupBy ?? 'none'}`),
                                        })}
                                    </p>
                                </div>
                                {widget.total != null ? (
                                    <div className="text-right">
                                        <p className="text-lg font-semibold tabular-nums text-foreground">
                                            {formatReportValue(widget.total, widget.unit, locale)}
                                        </p>
                                        {widget.changePercent != null ? (
                                            <p className="text-xs tabular-nums text-muted-foreground">
                                                {widget.measure === 'attainment'
                                                    ? t('document.attainmentValue', {
                                                        value: new Intl.NumberFormat(locale, { maximumFractionDigits: 1 }).format(widget.changePercent),
                                                    })
                                                    : t('document.changeValue', {
                                                        value: new Intl.NumberFormat(locale, { maximumFractionDigits: 1, signDisplay: 'always' }).format(widget.changePercent),
                                                    })}
                                            </p>
                                        ) : null}
                                    </div>
                                ) : null}
                            </div>
                            <ReportWidgetRenderer widget={widget} />
                        </section>
                    ))}
                </div>
            </section>

            <section className="mt-12" aria-labelledby="appendix-title">
                <SectionHeading id="appendix-title" title={t('document.appendix')} />
                <div className="mt-6 overflow-x-auto rounded-2xl border border-border">
                    <table className="w-full text-left text-sm">
                        <thead className="border-b border-border bg-muted/50 text-xs uppercase tracking-[0.12em] text-muted-foreground">
                            <tr>
                                <th className="px-4 py-3 font-medium">{t('document.source')}</th>
                                <th className="px-4 py-3 font-medium">{t('document.metric')}</th>
                                <th className="px-4 py-3 text-right font-medium">
                                    {hasMixedSemantics
                                        ? t('document.value')
                                        : hasAttainmentRows && !hasPriorRows
                                            ? t('document.actual')
                                            : t('document.currentPeriod')}
                                </th>
                                <th className="px-4 py-3 text-right font-medium">
                                    {hasMixedSemantics
                                        ? t('document.comparisonValue')
                                        : hasAttainmentRows
                                        ? hasPriorRows ? t('document.priorOrTarget') : t('document.target')
                                        : t('document.priorPeriod')}
                                </th>
                            </tr>
                        </thead>
                        <tbody className="divide-y divide-border">
                            {document.appendix.map((row) => (
                                <tr key={row.sourceId} id={`source-${row.sourceId}`}>
                                    <td className="px-4 py-3 text-xs font-medium text-brand-dark">
                                        [{citationIndex.get(row.sourceId) ?? row.sourceId}]
                                    </td>
                                    <td className="px-4 py-3 text-foreground">
                                        {localizedSourceLabel(row.label, row.widgetId)}
                                    </td>
                                    <td className="px-4 py-3 text-right tabular-nums text-foreground">
                                        {formatReportValue(row.value, row.unit, locale)}
                                    </td>
                                    <td className="px-4 py-3 text-right tabular-nums text-muted-foreground">
                                        {formatReportValue(row.priorValue, row.unit, locale)}
                                    </td>
                                </tr>
                            ))}
                        </tbody>
                    </table>
                </div>
            </section>

            {document.citations.length > 0 ? (
                <section className="mt-12 border-t border-border pt-8" aria-labelledby="citations-title">
                    <h2 id="citations-title" className="text-xs font-medium uppercase tracking-[0.12em] text-muted-foreground">
                        {t('document.citations')}
                    </h2>
                    <ol className="mt-4 grid gap-2 text-sm text-muted-foreground sm:grid-cols-2">
                        {document.citations.map((citation, index) => (
                            <Citation
                                key={citation.sourceId}
                                citation={citation}
                                label={localizedSourceLabel(citation.label, citation.widgetId)}
                                index={index + 1}
                                locale={locale}
                                comparisonLabel={measureByWidgetId.get(citation.widgetId) === 'attainment'
                                    ? t('document.targetCitation')
                                    : t('document.priorCitation')}
                            />
                        ))}
                    </ol>
                </section>
            ) : null}

            <footer className="mt-10 flex flex-wrap justify-between gap-3 border-t border-border pt-5 text-xs text-muted-foreground">
                <span>{t('document.generatedBy')}</span>
                <span>{formatDateTime(document.generatedAt, locale)}</span>
            </footer>
        </article>
    );
}

function NarrativeClaim({
    claim,
    citationIndex,
    compact = false,
}: {
    claim: ReportNarrativeClaim;
    citationIndex: Map<string, number>;
    compact?: boolean;
}) {
    const references = claim.sourceIds
        .map((sourceId) => ({ sourceId, index: citationIndex.get(sourceId) }))
        .filter((reference): reference is { sourceId: string; index: number } => reference.index !== undefined);
    return (
        <p className={compact ? 'text-sm leading-relaxed text-foreground' : 'text-base leading-7 text-foreground'}>
            {claim.text}{' '}
            {references.map((reference) => (
                <a
                    key={reference.sourceId}
                    href={`#source-${reference.sourceId}`}
                    className="align-super text-[0.7em] font-semibold text-brand-dark hover:underline"
                >
                    [{reference.index}]
                </a>
            ))}
        </p>
    );
}

function Citation({
    citation,
    label,
    index,
    locale,
    comparisonLabel,
}: {
    citation: ReportCitation;
    label: string;
    index: number;
    locale: string;
    comparisonLabel: string;
}) {
    return (
        <li className="flex gap-2">
            <span className="font-semibold text-brand-dark">[{index}]</span>
            <span>
                {label} · {formatReportValue(citation.value, citation.unit, locale)}
                {citation.priorValue != null
                    ? ` · ${formatReportValue(citation.priorValue, citation.unit, locale)} ${comparisonLabel}`
                    : ''}
            </span>
        </li>
    );
}

function SectionHeading({ id, title }: { id: string; title: string }) {
    return (
        <div className="border-b border-border pb-3">
            <h2 id={id} className="text-2xl font-bold tracking-tight text-foreground">{title}</h2>
        </div>
    );
}

function formatRange(start: string, end: string, locale: string): string {
    const formatter = new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeZone: 'UTC' });
    return `${formatter.format(new Date(`${start}T00:00:00Z`))} - ${formatter.format(new Date(`${end}T00:00:00Z`))}`;
}

function formatDateTime(value: string, locale: string): string {
    return new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value));
}

function sourceDisplayLabel(label: string, measureLabel: string | undefined): string {
    if (!measureLabel) return label;
    const separator = label.indexOf(' · ');
    return separator >= 0 ? measureLabel + label.slice(separator) : label;
}

function ReportDocumentSkeleton() {
    return (
        <div className="mx-auto max-w-6xl rounded-2xl border border-border bg-card px-6 py-10 sm:px-10 lg:px-16">
            <Skeleton className="h-3 w-24" />
            <Skeleton className="mt-4 h-12 w-2/3" />
            <Skeleton className="mt-4 h-4 w-1/2" />
            <div className="mt-12 space-y-3">
                <Skeleton className="h-8 w-56" />
                <Skeleton className="h-4 w-full" />
                <Skeleton className="h-4 w-11/12" />
                <Skeleton className="h-4 w-4/5" />
            </div>
            <div className="mt-12 grid grid-cols-1 gap-5 md:grid-cols-2">
                <Skeleton className="h-80 rounded-2xl" />
                <Skeleton className="h-80 rounded-2xl" />
            </div>
        </div>
    );
}

async function exportElementPng(element: HTMLElement, filename: string): Promise<void> {
    const clonedNode = element.cloneNode(true);
    if (!(clonedNode instanceof HTMLElement)) throw new Error('Report cloning is unavailable');
    const clone = clonedNode;
    const sources = [element, ...Array.from(element.querySelectorAll('*'))];
    const targets = [clone, ...Array.from(clone.querySelectorAll('*'))];
    sources.forEach((source, index) => {
        const target = targets[index];
        if (!(target instanceof HTMLElement || target instanceof SVGElement)) return;
        const computed = window.getComputedStyle(source);
        target.style.cssText = Array.from(computed)
            .map((property) => `${property}:${computed.getPropertyValue(property)};`)
            .join('');
    });
    const width = Math.ceil(element.scrollWidth);
    const height = Math.ceil(element.scrollHeight);
    const maximumDimension = 16384;
    const scale = Math.min(2, maximumDimension / width, maximumDimension / height);
    const serialized = new XMLSerializer().serializeToString(clone);
    const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}"><foreignObject width="100%" height="100%"><div xmlns="http://www.w3.org/1999/xhtml">${serialized}</div></foreignObject></svg>`;
    const svgUrl = URL.createObjectURL(new Blob([svg], { type: 'image/svg+xml;charset=utf-8' }));
    try {
        const image = new Image();
        image.src = svgUrl;
        await image.decode();
        const canvas = window.document.createElement('canvas');
        canvas.width = Math.max(1, Math.floor(width * scale));
        canvas.height = Math.max(1, Math.floor(height * scale));
        const context = canvas.getContext('2d');
        if (!context) throw new Error('Canvas rendering is unavailable');
        context.scale(scale, scale);
        context.drawImage(image, 0, 0, width, height);
        const blob = await new Promise<Blob>((resolve, reject) => {
            canvas.toBlob((result) => result ? resolve(result) : reject(new Error('PNG export failed')), 'image/png');
        });
        const url = URL.createObjectURL(blob);
        const anchor = window.document.createElement('a');
        anchor.href = url;
        anchor.download = filename;
        anchor.click();
        URL.revokeObjectURL(url);
    } finally {
        URL.revokeObjectURL(svgUrl);
    }
}
