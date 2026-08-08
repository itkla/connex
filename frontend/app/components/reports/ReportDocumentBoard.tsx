'use client';

import Link from 'next/link';
import { useCallback, useEffect, useRef, useState, type RefObject } from 'react';
import { useLocale, useTranslations } from 'next-intl';
import {
    ArchiveBoxArrowDownIcon,
    ArrowDownTrayIcon,
    ArrowPathIcon,
    CalendarDaysIcon,
    ChevronDownIcon,
    ClockIcon,
    DocumentTextIcon,
    ExclamationCircleIcon,
    PencilSquareIcon,
    PhotoIcon,
    PrinterIcon,
    SparklesIcon,
    TrashIcon,
} from '@heroicons/react/24/outline';

import ReportWidgetRenderer, { formatReportValue } from '@/app/components/reports/ReportWidgetRenderer';
import { reportFigureBasisKey } from '@/app/components/reports/reportFigureBasis';
import { CURRENT_STATE_REPORT_MEASURES } from '@/app/components/reports/reportConfig';
import { useReportLabels } from '@/app/components/reports/reportLabels';
import ScheduleManager from '@/app/components/reports/ScheduleManager';
import {
    createReportSnapshot,
    deleteReportSnapshot,
    exportReportCsv,
    exportReportSnapshotCsv,
    generateReport,
    getReportSnapshot,
    resolveAcceptedAiGeneration,
} from '@/app/lib/api';
import { useWorkspace } from '@/app/hooks/useWorkspace';
import { AiGenerationError } from '@/app/lib/aiGeneration';
import { canDeleteOwnedRecord } from '@/app/lib/deletionPolicy';
import { toastError, toastSuccess } from '@/app/lib/toast';
import type {
    ReportCitation,
    ReportDefinition,
    ReportDocument,
    ReportGenerateInput,
    AiGenerationStatus,
    ReportNarrativeClaim,
    ReportSnapshot,
    ReportSnapshotSummary,
} from '@/app/lib/types';
import { Button } from '@/components/ui/button';
import {
    Dialog,
    DialogClose,
    DialogContent,
    DialogDescription,
    DialogFooter,
    DialogHeader,
    DialogTitle,
} from '@/components/ui/dialog';
import {
    DropdownMenu,
    DropdownMenuContent,
    DropdownMenuItem,
    DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Skeleton } from '@/components/ui/skeleton';
import { cn } from '@/lib/utils';

type DocumentState =
    | { status: 'loading' }
    | { status: 'error' }
    | { status: 'ready'; document: ReportDocument };

type NarrativeState = 'idle' | 'generating' | 'error' | 'rateLimited' | 'timedOut';

const FIGURES_TIMEOUT_MS = 30_000;

/**
 * Rejects if the wrapped promise has not settled within {@link ms}. The underlying request keeps
 * running (its result is cached server-side), so a timeout simply surfaces a retry affordance
 * instead of leaving the view spinning indefinitely.
 */
function withTimeout<T>(promise: Promise<T>, ms: number): Promise<T> {
    return new Promise<T>((resolve, reject) => {
        const timer = setTimeout(() => reject(new Error('timeout')), ms);
        promise.then(
            (value) => { clearTimeout(timer); resolve(value); },
            (error) => { clearTimeout(timer); reject(error); },
        );
    });
}

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
    initialSnapshot = null,
    canUpdateReports,
    canDeleteReports,
    currentUserId,
    defaultTimezone,
}: {
    definition: ReportDefinition;
    initialSnapshots: ReportSnapshotSummary[];
    initialSnapshot?: ReportSnapshot | null;
    canUpdateReports: boolean;
    canDeleteReports: boolean;
    currentUserId: number;
    defaultTimezone: string;
}) {
    const t = useTranslations('Reports');
    const locale = useLocale();
    const { activeWorkspace } = useWorkspace();
    const [state, setState] = useState<DocumentState>({ status: 'loading' });
    const [narrativeState, setNarrativeState] = useState<NarrativeState>('idle');
    const [snapshots, setSnapshots] = useState<ReportSnapshotSummary[]>(initialSnapshots);
    const [activeSnapshotId, setActiveSnapshotId] = useState<number | null>(initialSnapshot?.id ?? null);
    const [activeSnapshot, setActiveSnapshot] = useState<ReportSnapshot | null>(initialSnapshot);
    const [liveRequested, setLiveRequested] = useState(initialSnapshot == null);
    const [start, setStart] = useState(initialSnapshot?.computedResult.periodStart ?? '');
    const [end, setEnd] = useState(initialSnapshot?.computedResult.periodEnd ?? '');
    const [refreshKey, setRefreshKey] = useState(0);
    const [snapshotting, setSnapshotting] = useState(false);
    const [deletingSnapshotId, setDeletingSnapshotId] = useState<number | null>(null);
    const [snapshotPendingDelete, setSnapshotPendingDelete] = useState<ReportSnapshotSummary | null>(null);
    const [exporting, setExporting] = useState(false);
    const [exportingPng, setExportingPng] = useState(false);
    const generationInputRef = useRef<ReportGenerateInput>({});
    const snapshotRequestRef = useRef(0);
    const generationRef = useRef(0);
    const generationAbortRef = useRef<AbortController | null>(null);
    const paperRef = useRef<HTMLElement>(null);
    const hasAttainment = definition.config.widgets.some((widget) => widget.measure === 'attainment');

    const document = activeSnapshotId != null
        ? activeSnapshot?.computedResult ?? null
        : state.status === 'ready' ? state.document : null;

    /** Resolves an accepted narrative handle, or starts a fresh handle for an explicit retry. */
    const runNarrative = useCallback(async (
        generationId: number,
        accepted?: AiGenerationStatus<ReportDocument>,
    ) => {
        generationAbortRef.current?.abort();
        const controller = new AbortController();
        generationAbortRef.current = controller;
        setNarrativeState('generating');
        try {
            let generation = accepted;
            if (!generation) {
                const started = await withTimeout(
                    generateReport(definition.id, generationInputRef.current, 'full'),
                    FIGURES_TIMEOUT_MS,
                );
                if (generationRef.current !== generationId) return;
                setState({ status: 'ready', document: started });
                generation = started.generation ?? undefined;
                if (!generation) {
                    setNarrativeState(started.narrative.available ? 'idle' : 'error');
                    return;
                }
            }
            const resolved = await resolveAcceptedAiGeneration<ReportDocument>(
                generation,
                { signal: controller.signal },
            );
            if (generationRef.current !== generationId) return;
            setState({ status: 'ready', document: resolved });
            setNarrativeState('idle');
        } catch (error) {
            if (generationRef.current !== generationId || controller.signal.aborted) return;
            if (error instanceof AiGenerationError && error.status === 'timed_out') {
                setNarrativeState('timedOut');
            } else if (error instanceof AiGenerationError && error.reason === 'rate_limited') {
                setNarrativeState('rateLimited');
            } else {
                setNarrativeState('error');
            }
        } finally {
            if (generationAbortRef.current === controller) {
                generationAbortRef.current = null;
            }
        }
    }, [definition.id]);

    useEffect(() => {
        if (!liveRequested) return;
        const generationId = (generationRef.current += 1);
        (async () => {
            let figures: ReportDocument;
            try {
                figures = await withTimeout(
                    generateReport(definition.id, generationInputRef.current, 'full'),
                    FIGURES_TIMEOUT_MS,
                );
            } catch {
                if (generationRef.current === generationId) setState({ status: 'error' });
                return;
            }
            if (generationRef.current !== generationId) return;
            setState({ status: 'ready', document: figures });
            setStart(figures.periodStart);
            setEnd(figures.periodEnd);
            if (figures.generation) {
                void runNarrative(generationId, figures.generation);
            } else {
                setNarrativeState('idle');
            }
        })();
        return () => {
            generationAbortRef.current?.abort();
            generationRef.current += 1;
        };
    }, [definition.id, refreshKey, runNarrative, liveRequested]);

    const retryNarrative = () => {
        const generationId = (generationRef.current += 1);
        void runNarrative(generationId);
    };

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
        setNarrativeState('idle');
        setLiveRequested(true);
        setRefreshKey((key) => key + 1);
        syncSnapshotUrl(null);
    };

    /**
     * Reflects the selected pill into the URL with a shallow `history.replaceState`, so a frozen
     * snapshot stays shareable and the emailed deep link and the in-page selection agree.
     */
    const syncSnapshotUrl = useCallback((snapshotId: number | null) => {
        const path = snapshotId == null
            ? `/overview/reports/${definition.id}`
            : `/overview/reports/${definition.id}/snapshots/${snapshotId}`;
        if (window.location.pathname === path) return;
        window.history.replaceState(null, '', `${path}${window.location.search}`);
    }, [definition.id]);

    /**
     * Returns the board to the live report. Seeds the generation input from the visible date range
     * so the figures that load match the dates the reader can see.
     */
    const fallbackToLive = useCallback(() => {
        snapshotRequestRef.current += 1;
        generationInputRef.current = start && end ? { start, end } : {};
        setActiveSnapshotId(null);
        setActiveSnapshot(null);
        setLiveRequested(true);
        syncSnapshotUrl(null);
    }, [start, end, syncSnapshotUrl]);

    const createSnapshot = async () => {
        const input = generationInput();
        if (!input) return;
        setSnapshotting(true);
        try {
            const snapshot = await createReportSnapshot(definition.id, input);
            setSnapshots((current) => [snapshot, ...current]);
            setActiveSnapshotId(snapshot.id);
            setActiveSnapshot(snapshot);
            syncSnapshotUrl(snapshot.id);
            toastSuccess(t('document.snapshotCreated'));
        } catch (error) {
            toastError(error instanceof Error ? error.message : t('common.requestFailed'));
        } finally {
            setSnapshotting(false);
        }
    };

    const confirmDeleteSnapshot = async () => {
        const snapshot = snapshotPendingDelete;
        if (!snapshot) return;
        setDeletingSnapshotId(snapshot.id);
        if (activeSnapshotId === snapshot.id) {
            snapshotRequestRef.current += 1;
        }
        try {
            await deleteReportSnapshot(definition.id, snapshot.id);
            setSnapshots((current) => current.filter((item) => item.id !== snapshot.id));
            if (activeSnapshotId === snapshot.id) {
                fallbackToLive();
            }
            toastSuccess(t('document.snapshotDeleted'));
            setSnapshotPendingDelete(null);
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
            const loaded = await withTimeout(
                getReportSnapshot(definition.id, snapshot.id),
                FIGURES_TIMEOUT_MS,
            );
            if (snapshotRequestRef.current === requestId) {
                setActiveSnapshot(loaded);
                syncSnapshotUrl(loaded.id);
            }
        } catch (error) {
            if (snapshotRequestRef.current === requestId) {
                fallbackToLive();
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
        <div className="report-page min-h-full px-2 pb-16 pt-8">
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
                        <ScheduleManager
                            reportId={definition.id}
                            reportName={definition.name}
                            canManage={canUpdateReports}
                            defaultTimezone={defaultTimezone}
                        />
                        <Button variant="outline" onClick={createSnapshot} disabled={!document || snapshotting}>
                            <ArchiveBoxArrowDownIcon />
                            {snapshotting ? t('document.snapshotting') : t('document.snapshot')}
                        </Button>
                        <DropdownMenu>
                            <DropdownMenuTrigger asChild>
                                <Button variant="outline" disabled={!document || exporting || exportingPng}>
                                    <ArrowDownTrayIcon />
                                    {exporting || exportingPng ? t('document.exporting') : t('document.export')}
                                    <ChevronDownIcon className="size-4 opacity-60" />
                                </Button>
                            </DropdownMenuTrigger>
                            <DropdownMenuContent align="end">
                                <DropdownMenuItem onSelect={() => void exportCsv()} disabled={exporting}>
                                    <ArrowDownTrayIcon />
                                    {t('document.csv')}
                                </DropdownMenuItem>
                                <DropdownMenuItem onSelect={() => void exportPng()} disabled={exportingPng}>
                                    <PhotoIcon />
                                    {t('document.png')}
                                </DropdownMenuItem>
                                <DropdownMenuItem onSelect={() => window.print()}>
                                    <PrinterIcon />
                                    {t('document.pdf')}
                                </DropdownMenuItem>
                            </DropdownMenuContent>
                        </DropdownMenu>
                        {canUpdateReports ? (
                            <Button asChild variant="brand">
                                <Link href={`/overview/reports/${definition.id}/edit`}>
                                    <PencilSquareIcon />
                                    {t('common.edit')}
                                </Link>
                            </Button>
                        ) : null}
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
                                onClick={fallbackToLive}
                            >
                                {t('document.live')}
                            </Button>
                            {snapshots.map((snapshot) => {
                                const active = activeSnapshotId === snapshot.id;
                                const dateLabel = new Intl.DateTimeFormat(locale, { dateStyle: 'medium' })
                                    .format(new Date(snapshot.generatedAt));
                                const deletable = canDeleteReports
                                    && canDeleteOwnedRecord(snapshot.generatedBy, currentUserId, activeWorkspace?.role);
                                return (
                                    <div
                                        key={snapshot.id}
                                        className={cn(
                                            'flex shrink-0 items-center rounded-full border',
                                            active ? 'border-brand/40 bg-secondary' : 'border-border',
                                        )}
                                    >
                                        <Button
                                            variant="ghost"
                                            size="sm"
                                            onClick={() => openSnapshot(snapshot)}
                                            aria-pressed={active}
                                            className={cn(
                                                'rounded-l-full hover:bg-transparent',
                                                deletable ? 'rounded-r-none' : 'rounded-r-full',
                                            )}
                                        >
                                            {snapshot.origin === 'scheduled' ? (
                                                <CalendarDaysIcon
                                                    role="img"
                                                    aria-label={t('document.scheduledSnapshot')}
                                                    className="size-3.5 text-muted-foreground"
                                                />
                                            ) : null}
                                            {dateLabel}
                                        </Button>
                                        {deletable ? (
                                            <Button
                                                variant="ghost"
                                                size="icon-sm"
                                                onClick={() => setSnapshotPendingDelete(snapshot)}
                                                disabled={deletingSnapshotId === snapshot.id}
                                                aria-label={t('document.deleteSnapshotNamed', { date: dateLabel })}
                                                className="rounded-l-none rounded-r-full text-muted-foreground hover:bg-destructive/10 hover:text-destructive"
                                            >
                                                <TrashIcon className="size-3.5" />
                                            </Button>
                                        ) : null}
                                    </div>
                                );
                            })}
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
                    <ReportPaper
                        document={document}
                        snapshot={activeSnapshot}
                        paperRef={paperRef}
                        narrativePhase={activeSnapshot != null
                            ? null
                            : narrativeState === 'generating'
                                ? 'generating'
                                : narrativeState === 'timedOut'
                                    ? 'timedOut'
                                    : narrativeState === 'rateLimited'
                                        ? 'rateLimited'
                                        : narrativeState === 'error'
                                            ? 'error'
                                            : null}
                        onRetryNarrative={activeSnapshot != null ? undefined : retryNarrative}
                    />
                ) : null}
            </div>

            <Dialog
                open={snapshotPendingDelete !== null}
                onOpenChange={(open) => !open && deletingSnapshotId === null && setSnapshotPendingDelete(null)}
            >
                <DialogContent>
                    <DialogHeader>
                        <DialogTitle>{t('document.deleteSnapshotTitle')}</DialogTitle>
                        <DialogDescription>{t('document.deleteSnapshotConfirm')}</DialogDescription>
                    </DialogHeader>
                    <DialogFooter>
                        <DialogClose asChild>
                            <Button variant="outline" disabled={deletingSnapshotId !== null}>{t('common.cancel')}</Button>
                        </DialogClose>
                        <Button
                            variant="destructive"
                            onClick={confirmDeleteSnapshot}
                            disabled={deletingSnapshotId !== null}
                        >
                            {deletingSnapshotId !== null ? t('common.deleting') : t('common.delete')}
                        </Button>
                    </DialogFooter>
                </DialogContent>
            </Dialog>
        </div>
    );
}

function ReportPaper({
    document,
    snapshot,
    paperRef,
    narrativePhase,
    onRetryNarrative,
}: {
    document: ReportDocument;
    snapshot: ReportSnapshot | null;
    paperRef: RefObject<HTMLElement | null>;
    narrativePhase: 'generating' | 'error' | 'rateLimited' | 'timedOut' | null;
    onRetryNarrative?: () => void;
}) {
    const t = useTranslations('Reports');
    const locale = useLocale();
    const { localizeLabel } = useReportLabels();
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
    const hasCurrentStateWidgets = document.widgets.some((widget) =>
        CURRENT_STATE_REPORT_MEASURES.has(widget.measure));
    const hasAttainmentRows = document.appendix.some((row) => measureByWidgetId.get(row.widgetId) === 'attainment');
    const hasNonAttainmentRows = document.appendix.some((row) => measureByWidgetId.get(row.widgetId) !== 'attainment');
    const hasCurrentStateRows = document.appendix.some((row) =>
        CURRENT_STATE_REPORT_MEASURES.has(measureByWidgetId.get(row.widgetId) ?? 'count'));
    const hasPeriodRows = document.appendix.some((row) =>
        !CURRENT_STATE_REPORT_MEASURES.has(measureByWidgetId.get(row.widgetId) ?? 'count'));
    const hasMixedSemantics = (hasAttainmentRows && hasNonAttainmentRows)
        || (hasCurrentStateRows && hasPeriodRows);
    const hasPriorRows = document.appendix.some((row) =>
        measureByWidgetId.get(row.widgetId) !== 'attainment' && row.priorValue != null);
    const hasComparisonRows = document.appendix.some((row) => row.priorValue != null);
    const localizedSourceLabel = (label: string, widgetId: string) =>
        localizeLabel(sourceDisplayLabel(label, measureLabelByWidgetId.get(widgetId)));
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
                        {hasCurrentStateWidgets ? (
                            <div>
                                <dt className="text-xs uppercase tracking-[0.12em] text-muted-foreground">
                                    {t('document.currentStateAsOf')}
                                </dt>
                                <dd className="mt-1 text-foreground">{formatDateTime(document.generatedAt, locale)}</dd>
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
                ) : narrativePhase === 'generating' ? (
                    <div className="mt-6 flex items-start gap-3 rounded-2xl border border-dashed border-border px-5 py-8">
                        <SparklesIcon className="mt-0.5 size-5 shrink-0 animate-pulse text-brand-dark motion-reduce:animate-none" aria-hidden />
                        <div>
                            <p className="text-sm font-medium text-foreground">{t('document.narrativeGenerating')}</p>
                            <p className="mt-1 text-sm text-muted-foreground">{t('document.narrativeGeneratingBody')}</p>
                        </div>
                    </div>
                ) : narrativePhase === 'error'
                    || narrativePhase === 'rateLimited'
                    || narrativePhase === 'timedOut' ? (
                    <div className="mt-6 rounded-2xl border border-dashed border-border px-5 py-8">
                        <div className="flex items-start gap-3">
                            <ExclamationCircleIcon className="mt-0.5 size-5 shrink-0 text-muted-foreground" aria-hidden />
                            <div>
                                <p className="text-sm font-medium text-foreground">
                                    {narrativePhase === 'timedOut'
                                        ? t('document.narrativeTimedOutTitle')
                                        : t('document.narrativeErrorTitle')}
                                </p>
                                <p className="mt-1 text-sm text-muted-foreground">
                                    {narrativePhase === 'rateLimited'
                                        ? t('document.narrativeRateLimitedBody')
                                        : narrativePhase === 'timedOut'
                                            ? t('document.narrativeTimedOutBody')
                                        : t('document.narrativeErrorBody')}
                                </p>
                            </div>
                        </div>
                        {onRetryNarrative ? (
                            <Button className="mt-4" size="sm" variant="outline" onClick={onRetryNarrative}>
                                <ArrowPathIcon />
                                {t('common.retry')}
                            </Button>
                        ) : null}
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
                    {document.widgets.map((widget) => {
                        const basisKey = reportFigureBasisKey(widget.measure);
                        return (
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
                                    {basisKey ? (
                                        <p className="mt-1 text-xs text-muted-foreground">{t(basisKey)}</p>
                                    ) : null}
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
                        );
                    })}
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
                                    {!hasComparisonRows
                                        ? t('document.value')
                                        : hasMixedSemantics
                                        ? t('document.value')
                                        : hasAttainmentRows && !hasPriorRows
                                            ? t('document.actual')
                                            : t('document.currentPeriod')}
                                </th>
                                {hasComparisonRows ? (
                                    <th className="px-4 py-3 text-right font-medium">
                                        {hasMixedSemantics
                                            ? t('document.comparisonValue')
                                            : hasAttainmentRows
                                            ? hasPriorRows ? t('document.priorOrTarget') : t('document.target')
                                            : t('document.priorPeriod')}
                                    </th>
                                ) : null}
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
                                    {hasComparisonRows ? (
                                        <td className="px-4 py-3 text-right tabular-nums text-muted-foreground">
                                            {formatReportValue(row.priorValue, row.unit, locale)}
                                        </td>
                                    ) : null}
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
