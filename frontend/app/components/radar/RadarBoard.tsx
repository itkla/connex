'use client';

import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { useSearchParams } from 'next/navigation';
import { useTranslations } from 'next-intl';
import { AdjustmentsHorizontalIcon, MagnifyingGlassIcon, SignalIcon } from '@heroicons/react/24/outline';

import RadarSignalCard from '@/app/components/radar/RadarSignalCard';
import RadarHorizon from '@/app/components/radar/RadarHorizon';
import RadarFamilyLayer from '@/app/components/radar/RadarFamilyLayer';
import { radarRecordLabel } from '@/app/components/radar/radarLabels';
import {
    RADAR_FAMILY_FILTER_KEY,
    RADAR_HORIZON_FILTER_KEY,
    RADAR_QUERY_FILTER_KEY,
    RADAR_STATE_FILTER_KEY,
    radarOwnedUrlParams,
} from '@/app/components/radar/radarLinks';
import {
    isRadarHorizonBand,
    radarHorizonPlacement,
    radarPathBridges,
    type RadarHorizonBand,
} from '@/app/components/radar/radarHorizon';
import {
    RADAR_FIELD_SURFACE,
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
    RADAR_SIGNAL_FAMILIES,
    classifyRadarSurface,
    createRadarTaskSignalStore,
    filterRadarSignals,
    isRadarFamilyFilter,
    isRadarStateFilter,
    radarEvidenceRefreshDelay,
    releaseActiveRadarTask,
    replaceRadarSignal,
    unavailableRadarFamilies,
    type ActiveRadarTask,
    type RadarFreshnessStatus,
    type RadarFamilyFilter,
    type RadarStateFilter,
} from '@/app/lib/radar';
import { toastError, toastSuccess } from '@/app/lib/toast';
import type { RadarFamily, RadarPayload, RadarSignal } from '@/app/lib/types';
import type { TaskDraft } from '@/app/lib/actions/types';
import { cn } from '@/lib/utils';
import { Input } from '@/components/ui/input';
import { SegmentedControl } from '@/components/ui/segmented-control';
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
    const bridge = radarPathBridges(signal)[0];
    return bridge === undefined
        ? undefined
        : { id: bridge.bridgePersonId, label: bridge.bridgeName };
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

/**
 * Radar: the whole flagged portfolio placed on one horizon, then opened family by family.
 *
 * The page reads outside in — where attention is bleeding, what each group of signals looks like as
 * a whole, then the individual signals and their evidence — so the first thing a user sees is a
 * question only Radar can answer rather than the first row of a list. Every level below the horizon
 * is a refinement of the same set, and the filters that survive in the URL keep a view shareable.
 */
export default function RadarBoard({ initialPayload }: { initialPayload: RadarPayload }) {
    const t = useTranslations('Radar');
    const searchParams = useSearchParams();
    const initialFamily = searchParams.get(RADAR_FAMILY_FILTER_KEY);
    const initialState = searchParams.get(RADAR_STATE_FILTER_KEY);
    const initialWhen = searchParams.get(RADAR_HORIZON_FILTER_KEY);
    const [family, setFamily] = useState<RadarFamilyFilter>(
        isRadarFamilyFilter(initialFamily) ? initialFamily : 'all',
    );
    const [state, setState] = useState<RadarStateFilter>(
        isRadarStateFilter(initialState) ? initialState : 'attention',
    );
    const [query, setQuery] = useState(() => (
        radarRecordLabel(searchParams.get(RADAR_QUERY_FILTER_KEY)) ?? ''
    ));
    const [horizonBand, setHorizonBand] = useState<RadarHorizonBand | null>(
        isRadarHorizonBand(initialWhen) ? initialWhen : null,
    );
    const [payload, setPayload] = useState(initialPayload);
    const [busyId, setBusyId] = useState<number | null>(null);
    const [snoozeId, setSnoozeId] = useState<number | null>(null);
    const [expandedIds, setExpandedIds] = useState<ReadonlySet<number>>(() => new Set());
    const [closedFamilies, setClosedFamilies] = useState<ReadonlySet<RadarFamily>>(() => new Set());
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

    useOwnedUrlParams(radarOwnedUrlParams({ family, state, query, horizon: horizonBand }));

    const displaySignals = useMemo(() => payload.items.map((signal) => {
        const label = radarRecordLabel(signal.subject.label)
            ?? t(`subject.unnamed.${signal.subject.type}`);
        return label === signal.subject.label
            ? signal
            : { ...signal, subject: { ...signal.subject, label } };
    }), [payload.items, t]);
    const matchedSignals = useMemo(
        () => filterRadarSignals(displaySignals, { family: 'all', state, query }),
        [displaySignals, query, state],
    );
    const visibleSignals = useMemo(
        () => matchedSignals.filter((signal) => (
            (family === 'all' || signal.family === family)
            && (horizonBand === null || radarHorizonPlacement(signal).band === horizonBand)
        )),
        [family, horizonBand, matchedSignals],
    );
    const horizonSignals = useMemo(
        () => matchedSignals.filter((signal) => family === 'all' || signal.family === family),
        [family, matchedSignals],
    );
    const surface = classifyRadarSurface(payload, visibleSignals);
    const unavailableFamilies = unavailableRadarFamilies(payload.families);
    const unavailableLookup = new Set<RadarFamily>(unavailableFamilies);
    const shownFamilies = family === 'all' ? RADAR_SIGNAL_FAMILIES : [family];
    const filtered = family !== 'all' || state !== 'attention' || query.trim().length > 0;

    const restoreListFocus = (id: number) => {
        const target = nextFocusId(visibleSignals, id);
        requestAnimationFrame(() => {
            const element = target == null
                ? document.getElementById('radar-filter-search')
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
                record: {
                    type: context.type,
                    id: context.id,
                    label: radarRecordLabel(context.label) ?? signal.subject.label,
                },
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
        <div className="space-y-6">
            <p className="sr-only" aria-live="polite" aria-atomic="true">{announcement}</p>

            {surface === 'unavailable' ? (
                <SectionUnavailable
                    title={t('unavailable.title')}
                    body={t('unavailable.body', {
                        families: unavailableFamilies.map((value) => t(`family.${value}`)).join(t('evidence.separator')),
                    })}
                />
            ) : unavailableFamilies.length > 0 ? (
                <SectionUnavailable
                    title={t('partial.title')}
                    body={t('partial.body', {
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

            {surface !== 'empty' && surface !== 'unavailable' ? (
                <>
                    <RadarHorizon
                        signals={horizonSignals}
                        band={horizonBand}
                        onBandChange={setHorizonBand}
                        filtered={filtered}
                    />

                    <section aria-label={t('filters.label')} className="flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
                        <SegmentedControl
                            ariaLabel={t('filters.familyLabel')}
                            value={family}
                            onChange={setFamily}
                            options={[
                                { value: 'all', label: t('filters.allFamilies') },
                                ...RADAR_SIGNAL_FAMILIES.map((value) => ({
                                    value,
                                    label: t(`family.${value}`),
                                })),
                            ]}
                        />

                        <div className="flex gap-2">
                            <div className="relative min-w-0 flex-1 lg:w-64 lg:flex-none">
                                <MagnifyingGlassIcon className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" aria-hidden />
                                <Input
                                    id="radar-filter-search"
                                    data-action-focus-fallback=""
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
                    </section>

                    {surface === 'no_results' ? (
                        <EmptyState
                            icon={AdjustmentsHorizontalIcon}
                            title={t('noResults.title')}
                            body={t('noResults.body')}
                            tone="muted"
                        />
                    ) : (
                        <p className="text-sm text-muted-foreground">
                            {t(filtered || horizonBand !== null ? 'summary.headlineFiltered' : 'summary.headline', {
                                count: visibleSignals.length,
                            })}
                        </p>
                    )}

                    <div className="space-y-4">
                        {(surface === 'no_results' ? [] : shownFamilies).map((value) => {
                            const familySignals = visibleSignals.filter((signal) => signal.family === value);
                            return (
                                <RadarFamilyLayer
                                    key={value}
                                    family={value}
                                    signals={familySignals}
                                    unavailable={unavailableLookup.has(value)}
                                    open={!closedFamilies.has(value)}
                                    onOpenChange={(open) => setClosedFamilies((current) => {
                                        const next = new Set(current);
                                        if (open) next.delete(value);
                                        else next.add(value);
                                        return next;
                                    })}
                                >
                                    <ol aria-label={t(`family.${value}`)}>
                                        {familySignals.map((signal) => (
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
                                </RadarFamilyLayer>
                            );
                        })}
                    </div>
                </>
            ) : null}
        </div>
    );
}
