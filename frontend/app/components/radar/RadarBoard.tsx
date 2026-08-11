'use client';

import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { useSearchParams } from 'next/navigation';
import { useTranslations } from 'next-intl';
import { AdjustmentsHorizontalIcon, MagnifyingGlassIcon, SignalIcon } from '@heroicons/react/24/outline';

import RadarSignalCard from '@/app/components/radar/RadarSignalCard';
import { FAMILY_DOTS } from '@/app/components/radar/radarFamilyAccent';
import {
    RADAR_FIELD_SURFACE,
    RADAR_FORCED_COLORS_AFFORDANCE,
    RADAR_PRESSABLE_SURFACE,
} from '@/app/components/radar/radarControlSurface';
import SectionUnavailable from '@/app/components/SectionUnavailable';
import { EmptyState } from '@/app/components/EmptyState';
import { useActions } from '@/app/hooks/useActions';
import { useOwnedUrlParams } from '@/app/hooks/useOwnedUrlParams';
import {
    dismissRadarSignal,
    followRadarSignal,
    getRadar,
    getRadarContext,
    snoozeRadarSignal,
} from '@/app/lib/api';
import {
    RADAR_FAMILIES,
    classifyRadarSurface,
    createRadarTaskSignalStore,
    filterRadarSignals,
    groupRadarSignalsByBand,
    isRadarFamilyFilter,
    isRadarStateFilter,
    radarEvidenceRefreshDelay,
    radarFamilyCounts,
    releaseActiveRadarTask,
    replaceRadarSignal,
    unavailableRadarFamilies,
    type ActiveRadarTask,
    type RadarFreshnessStatus,
    type RadarFamilyFilter,
    type RadarStateFilter,
} from '@/app/lib/radar';
import { toastError, toastSuccess } from '@/app/lib/toast';
import type { RadarPayload, RadarSignal } from '@/app/lib/types';
import type { TaskDraft } from '@/app/lib/actions/types';
import { cn } from '@/lib/utils';
import { Input } from '@/components/ui/input';
import {
    Select,
    SelectContent,
    SelectItem,
    SelectTrigger,
    SelectValue,
} from '@/components/ui/select';

function nextFocusId(signals: readonly RadarSignal[], id: number): number | null {
    const index = signals.findIndex((signal) => signal.id === id);
    if (index < 0) return null;
    return signals[index + 1]?.id ?? signals[index - 1]?.id ?? null;
}

function warmPathBridge(signal: RadarSignal): { id: number; label: string } | undefined {
    for (const evidence of signal.evidence) {
        if (evidence.type !== 'warm_path') continue;
        const bridgePersonId = evidence.parameters.bridgePersonId;
        if (typeof bridgePersonId !== 'number' || !Number.isInteger(bridgePersonId)) continue;
        const bridgeName = evidence.parameters.bridgeName;
        return {
            id: bridgePersonId,
            label: typeof bridgeName === 'string' && bridgeName.trim().length > 0
                ? bridgeName.trim()
                : `#${bridgePersonId}`,
        };
    }
    return undefined;
}

function nextRadarRefreshDelay(payload: RadarPayload, requestDurationMs: number): number | null {
    const delays = payload.items
        .filter((signal) => !signal.stale && signal.taskId == null)
        .map((signal) => radarEvidenceRefreshDelay(
            signal.evidenceAsOf,
            payload.asOf,
            requestDurationMs,
        ))
        .filter(Number.isFinite);
    return delays.length === 0 ? null : Math.min(...delays);
}

type RadarRefreshSession = { active: boolean };

/** Stateful Radar work list with shareable filters and failure-aware per-signal actions. */
export default function RadarBoard({ initialPayload }: { initialPayload: RadarPayload }) {
    const t = useTranslations('Radar');
    const searchParams = useSearchParams();
    const initialFamily = searchParams.get('family');
    const initialState = searchParams.get('state');
    const [family, setFamily] = useState<RadarFamilyFilter>(
        isRadarFamilyFilter(initialFamily) ? initialFamily : 'all',
    );
    const [state, setState] = useState<RadarStateFilter>(
        isRadarStateFilter(initialState) ? initialState : 'attention',
    );
    const [query, setQuery] = useState(() => searchParams.get('q') ?? '');
    const [payload, setPayload] = useState(initialPayload);
    const [busyId, setBusyId] = useState<number | null>(null);
    const [snoozeId, setSnoozeId] = useState<number | null>(null);
    const [expandedIds, setExpandedIds] = useState<ReadonlySet<number>>(() => new Set());
    const [announcement, setAnnouncement] = useState('');
    const [freshnessStatus, setFreshnessStatus] = useState<RadarFreshnessStatus>('checking');
    const { run } = useActions();
    const refreshTimerRef = useRef<number | null>(null);
    const refreshRadarRef = useRef<() => void>(() => undefined);
    const refreshSessionRef = useRef<RadarRefreshSession | null>(null);
    const radarTaskDraftsRef = useRef(new Map<number, TaskDraft>());
    const activeRadarTaskRef = useRef<ActiveRadarTask | null>(null);

    const requestRadar = useCallback(async (session: RadarRefreshSession) => {
        while (true) {
            const startedAt = performance.now();
            const refreshed = await getRadar();
            const requestDurationMs = performance.now() - startedAt;
            const nextRefreshDelay = nextRadarRefreshDelay(refreshed, requestDurationMs);
            if (!session.active || nextRefreshDelay !== 0) {
                return { refreshed, nextRefreshDelay };
            }
        }
    }, []);

    const applyRadarRefresh = useCallback((result: {
        refreshed: RadarPayload;
        nextRefreshDelay: number | null;
    }, session: RadarRefreshSession) => {
        if (!session.active || refreshSessionRef.current !== session) return;
        if (refreshTimerRef.current !== null) {
            window.clearTimeout(refreshTimerRef.current);
            refreshTimerRef.current = null;
        }
        if (result.nextRefreshDelay !== null) {
            refreshTimerRef.current = window.setTimeout(() => {
                if (!session.active || refreshSessionRef.current !== session) return;
                refreshTimerRef.current = null;
                refreshRadarRef.current();
            }, result.nextRefreshDelay);
        }
        setPayload(result.refreshed);
        setFreshnessStatus('current');
    }, []);

    const markRadarUnavailable = useCallback((session: RadarRefreshSession) => {
        if (!session.active || refreshSessionRef.current !== session) return;
        setFreshnessStatus('unavailable');
    }, []);

    const refreshRadar = useCallback(() => {
        const session = refreshSessionRef.current;
        if (session === null || !session.active) return;
        if (refreshTimerRef.current !== null) {
            window.clearTimeout(refreshTimerRef.current);
            refreshTimerRef.current = null;
        }
        setFreshnessStatus('checking');
        void requestRadar(session).then(
            (result) => applyRadarRefresh(result, session),
            () => markRadarUnavailable(session),
        );
    }, [applyRadarRefresh, markRadarUnavailable, requestRadar]);

    useLayoutEffect(() => {
        refreshRadarRef.current = refreshRadar;
    }, [refreshRadar]);

    useEffect(() => {
        const session = { active: true };
        refreshSessionRef.current = session;
        void requestRadar(session).then(
            (result) => applyRadarRefresh(result, session),
            () => markRadarUnavailable(session),
        );
        return () => {
            session.active = false;
            if (refreshSessionRef.current === session) {
                refreshSessionRef.current = null;
            }
            if (refreshTimerRef.current !== null) {
                window.clearTimeout(refreshTimerRef.current);
                refreshTimerRef.current = null;
            }
            activeRadarTaskRef.current?.signalState.refresh(undefined, 'unavailable');
            activeRadarTaskRef.current = null;
        };
    }, [applyRadarRefresh, markRadarUnavailable, requestRadar]);

    useEffect(() => {
        const activeRadarTask = activeRadarTaskRef.current;
        if (activeRadarTask === null) return;
        activeRadarTask.signalState.refresh(
            payload.items.find((signal) => signal.id === activeRadarTask.signalId),
            freshnessStatus,
        );
    }, [freshnessStatus, payload.items]);

    useOwnedUrlParams({
        family: family === 'all' ? undefined : family,
        state: state === 'attention' ? undefined : state,
        q: query.trim() || undefined,
    });

    const visibleSignals = useMemo(
        () => filterRadarSignals(payload.items, { family, state, query }),
        [family, payload.items, query, state],
    );
    const surface = classifyRadarSurface(payload, visibleSignals);
    const unavailableFamilies = unavailableRadarFamilies(payload.families);
    const familyCounts = useMemo(
        () => radarFamilyCounts(payload.items, { state, query }),
        [payload.items, query, state],
    );
    const bands = useMemo(() => groupRadarSignalsByBand(visibleSignals), [visibleSignals]);

    const restoreListFocus = (id: number) => {
        const target = nextFocusId(visibleSignals, id);
        requestAnimationFrame(() => {
            const element = target == null
                ? document.getElementById('radar-family-filter')
                : document.getElementById(`radar-signal-${target}`);
            element?.focus();
        });
    };

    const updateSignal = (signal: RadarSignal, message: string, restoreFrom?: number, showToast = true) => {
        setPayload((current) => ({ ...current, items: replaceRadarSignal(current.items, signal) }));
        setAnnouncement(message);
        if (showToast) toastSuccess(message);
        if (restoreFrom !== undefined) restoreListFocus(restoreFrom);
    };

    const resolveWarmPathSignal = (signal: RadarSignal, message: string) => {
        setPayload((current) => ({
            ...current,
            items: current.items.filter((item) => item.id !== signal.id),
        }));
        setAnnouncement(message);
    };

    const mutate = async (
        signal: RadarSignal,
        request: () => Promise<RadarSignal>,
        successMessage: string,
        restoreFocus: boolean,
    ) => {
        if (busyId !== null) return;
        setBusyId(signal.id);
        setAnnouncement('');
        try {
            const updated = await request();
            updateSignal(updated, successMessage, restoreFocus ? signal.id : undefined);
            setSnoozeId(null);
        } catch {
            const message = t('feedback.actionFailed', { subject: signal.subject.label });
            setAnnouncement(message);
            toastError(message);
        } finally {
            setBusyId(null);
        }
    };

    const openContext = async (signal: RadarSignal) => {
        if (busyId !== null) return;
        setBusyId(signal.id);
        try {
            const context = await getRadarContext(signal.id);
            const result = await run('record.open', {
                source: 'menu',
                record: { type: context.type, id: context.id, label: context.label },
            });
            if (result.status !== 'completed') throw new Error('Radar context action did not complete');
        } catch {
            const message = t('feedback.contextFailed', { subject: signal.subject.label });
            setAnnouncement(message);
            toastError(message);
        } finally {
            setBusyId(null);
        }
    };

    const openTask = async (signal: RadarSignal) => {
        const bridge = signal.family === 'warm_path' ? warmPathBridge(signal) : undefined;
        const initialDescription = bridge
            ? t('task.warmPathDescription', {
                bridge: bridge.label,
                subject: signal.subject.label,
            })
            : t('task.defaultDescription', { subject: signal.subject.label });
        const draft = radarTaskDraftsRef.current.get(signal.id) ?? { description: initialDescription };
        const signalState = createRadarTaskSignalStore(signal);
        activeRadarTaskRef.current = { signalId: signal.id, signalState };
        const result = await run('create.task', {
            source: 'menu',
            record: {
                type: signal.subject.type,
                id: signal.subject.id,
                label: signal.subject.label,
            },
            radarTask: {
                signalId: signal.id,
                draft,
                mode: signal.family === 'warm_path' ? 'warm_path' : 'standard',
                bridgePersonId: bridge?.id,
                signalState,
                onRefresh: refreshRadar,
                onDraftChange: (nextDraft) => {
                    radarTaskDraftsRef.current.set(signal.id, nextDraft);
                },
                onDraftClear: () => {
                    radarTaskDraftsRef.current.delete(signal.id);
                },
                onCreated: (updated) => {
                    radarTaskDraftsRef.current.delete(signal.id);
                    activeRadarTaskRef.current = releaseActiveRadarTask(
                        activeRadarTaskRef.current,
                        signalState,
                    );
                    const message = t('feedback.taskCreated', { subject: signal.subject.label });
                    if (signal.family === 'warm_path') resolveWarmPathSignal(updated, message);
                    else updateSignal(updated, message, undefined, false);
                },
                onClosed: () => {
                    activeRadarTaskRef.current = releaseActiveRadarTask(
                        activeRadarTaskRef.current,
                        signalState,
                    );
                },
            },
        });
        if (result.status !== 'completed') {
            activeRadarTaskRef.current = releaseActiveRadarTask(
                activeRadarTaskRef.current,
                signalState,
            );
            const message = t('feedback.actionFailed', { subject: signal.subject.label });
            setAnnouncement(message);
            if (result.status !== 'failed') toastError(message);
        }
    };

    return (
        <div className="space-y-8">
            <p className="sr-only" aria-live="polite" aria-atomic="true">{announcement}</p>

            <section aria-label={t('filters.label')} className="space-y-4">
                <p className="text-sm text-muted-foreground">
                    {t(state === 'attention' ? 'summary.headline' : 'summary.headlineFiltered', {
                        count: visibleSignals.length,
                    })}
                </p>

                <div className="flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
                    <div className="-mx-1 flex flex-nowrap items-center gap-1.5 overflow-x-auto px-1 pb-1 lg:flex-wrap lg:overflow-visible lg:pb-0" role="group" aria-label={t('filters.familyLabel')}>
                        {RADAR_FAMILIES.map((value) => (
                            <button
                                key={value}
                                type="button"
                                id={value === 'all' ? 'radar-family-filter' : undefined}
                                data-action-focus-fallback={value === 'all' ? '' : undefined}
                                onClick={() => setFamily(value)}
                                aria-pressed={family === value}
                                className={cn(
                                    'inline-flex min-h-9 shrink-0 items-center gap-2 rounded-full px-3 text-sm whitespace-nowrap transition-colors outline-none focus-visible:ring-3 focus-visible:ring-ring/50',
                                    RADAR_FORCED_COLORS_AFFORDANCE,
                                    family === value
                                        ? 'bg-foreground text-background'
                                        : `${RADAR_PRESSABLE_SURFACE} text-muted-foreground hover:text-foreground`,
                                )}
                            >
                                {value === 'all' ? null : (
                                    <span className={cn('size-1.5 rounded-full', FAMILY_DOTS[value])} aria-hidden />
                                )}
                                {value === 'all' ? t('filters.allFamilies') : t(`family.${value}`)}
                                <span className={cn('tabular-nums', family === value ? 'text-background/70' : 'text-muted-foreground/70')}>
                                    {familyCounts[value]}
                                </span>
                            </button>
                        ))}
                    </div>

                    <div className="flex gap-2">
                        <div className="relative min-w-0 flex-1 lg:w-64 lg:flex-none">
                            <MagnifyingGlassIcon className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" aria-hidden />
                            <Input
                                value={query}
                                onChange={(event) => setQuery(event.target.value)}
                                placeholder={t('filters.searchPlaceholder')}
                                aria-label={t('filters.searchLabel')}
                                className={cn('rounded-full pl-9', RADAR_FIELD_SURFACE)}
                            />
                        </div>
                        <Select value={state} onValueChange={(value) => {
                            if (isRadarStateFilter(value)) setState(value);
                        }}>
                            <SelectTrigger className={cn('min-h-11 shrink-0 rounded-full lg:min-h-9', RADAR_PRESSABLE_SURFACE)} aria-label={t('filters.stateLabel')}>
                                <SelectValue />
                            </SelectTrigger>
                            <SelectContent align="end">
                                <SelectItem value="attention">{t('filters.attention')}</SelectItem>
                                <SelectItem value="active">{t('state.active')}</SelectItem>
                                <SelectItem value="followed">{t('state.followed')}</SelectItem>
                                <SelectItem value="snoozed">{t('state.snoozed')}</SelectItem>
                                <SelectItem value="dismissed">{t('state.dismissed')}</SelectItem>
                                <SelectItem value="all">{t('filters.allStates')}</SelectItem>
                            </SelectContent>
                        </Select>
                    </div>
                </div>
            </section>

            {unavailableFamilies.length > 0 ? (
                <SectionUnavailable
                    title={surface === 'unavailable' ? t('unavailable.title') : t('partial.title')}
                    body={t(surface === 'unavailable' ? 'unavailable.body' : 'partial.body', {
                        families: unavailableFamilies.map((value) => t(`family.${value}`)).join(t('evidence.separator')),
                    })}
                />
            ) : null}

            {surface === 'empty' ? (
                <EmptyState
                    icon={SignalIcon}
                    title={t('empty.title')}
                    body={t('empty.body')}
                    tone="brand"
                />
            ) : null}

            {surface === 'no_results' || (surface === 'partial' && payload.items.length > 0 && visibleSignals.length === 0) ? (
                <EmptyState
                    icon={AdjustmentsHorizontalIcon}
                    title={t('noResults.title')}
                    body={t('noResults.body')}
                    tone="muted"
                />
            ) : null}

            {bands.map((group) => (
                <section key={group.band} aria-labelledby={`radar-band-${group.band}`} className="space-y-2">
                    <div className="flex items-baseline gap-3">
                        <h2 id={`radar-band-${group.band}`} className="text-sm font-semibold text-foreground">
                            {t(`band.${group.band}`)}
                        </h2>
                        <span className="text-xs tabular-nums text-muted-foreground">{group.signals.length}</span>
                        <span className="h-px min-w-0 flex-1 bg-border" aria-hidden />
                    </div>
                    <ol className="rounded-2xl border border-border bg-card" aria-label={t(`band.${group.band}`)}>
                        {group.signals.map((signal) => (
                            <RadarSignalCard
                                key={`${signal.id}:${payload.asOf}`}
                                signal={signal}
                                pageAsOf={payload.asOf}
                                freshnessStatus={freshnessStatus}
                                busy={busyId !== null}
                                snoozeOpen={snoozeId === signal.id}
                                onSnoozeOpenChange={(open) => setSnoozeId(open ? signal.id : null)}
                                expanded={expandedIds.has(signal.id)}
                                onExpandedChange={(open) => setExpandedIds((current) => {
                                    const next = new Set(current);
                                    if (open) next.add(signal.id);
                                    else next.delete(signal.id);
                                    return next;
                                })}
                                onFollow={() => void mutate(
                                    signal,
                                    () => followRadarSignal(signal.id, signal.version),
                                    t('feedback.followed', { subject: signal.subject.label }),
                                    false,
                                )}
                                onSnooze={(until) => void mutate(
                                    signal,
                                    () => snoozeRadarSignal(signal.id, signal.version, until),
                                    t('feedback.snoozed', { subject: signal.subject.label }),
                                    true,
                                )}
                                onDismiss={() => void mutate(
                                    signal,
                                    () => dismissRadarSignal(signal.id, signal.version),
                                    t('feedback.dismissed', { subject: signal.subject.label }),
                                    true,
                                )}
                                onCreateTask={() => void openTask(signal)}
                                onRefreshEvidence={() => void refreshRadar()}
                                onOpenContext={() => void openContext(signal)}
                            />
                        ))}
                    </ol>
                </section>
            ))}
        </div>
    );
}
