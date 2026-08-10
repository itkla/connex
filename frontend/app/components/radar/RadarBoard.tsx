'use client';

import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { useSearchParams } from 'next/navigation';
import { useTranslations } from 'next-intl';
import { AdjustmentsHorizontalIcon, MagnifyingGlassIcon, SignalIcon } from '@heroicons/react/24/outline';

import RadarSignalCard from '@/app/components/radar/RadarSignalCard';
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
    classifyRadarSurface,
    createRadarTaskSignalStore,
    filterRadarSignals,
    isRadarFamilyFilter,
    isRadarStateFilter,
    radarEvidenceRefreshDelay,
    replaceRadarSignal,
    unavailableRadarFamilies,
    type RadarFreshnessStatus,
    type RadarFamilyFilter,
    type RadarStateFilter,
    type RadarTaskSignalStore,
} from '@/app/lib/radar';
import { toastError, toastSuccess } from '@/app/lib/toast';
import type { RadarPayload, RadarSignal } from '@/app/lib/types';
import type { TaskDraft } from '@/app/lib/actions/types';
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
    const [announcement, setAnnouncement] = useState('');
    const [freshnessStatus, setFreshnessStatus] = useState<RadarFreshnessStatus>('checking');
    const { run } = useActions();
    const refreshTimerRef = useRef<number | null>(null);
    const refreshRadarRef = useRef<() => void>(() => undefined);
    const radarTaskDraftsRef = useRef(new Map<number, TaskDraft>());
    const activeRadarTaskRef = useRef<{
        signalId: number;
        signalState: RadarTaskSignalStore;
    } | null>(null);

    const requestRadar = useCallback(async () => {
        while (true) {
            const startedAt = performance.now();
            const refreshed = await getRadar();
            const requestDurationMs = performance.now() - startedAt;
            const nextRefreshDelay = nextRadarRefreshDelay(refreshed, requestDurationMs);
            if (nextRefreshDelay !== 0) {
                return { refreshed, nextRefreshDelay };
            }
        }
    }, []);

    const applyRadarRefresh = useCallback((result: {
        refreshed: RadarPayload;
        nextRefreshDelay: number | null;
    }) => {
        if (refreshTimerRef.current !== null) {
            window.clearTimeout(refreshTimerRef.current);
            refreshTimerRef.current = null;
        }
        if (result.nextRefreshDelay !== null) {
            refreshTimerRef.current = window.setTimeout(() => {
                refreshTimerRef.current = null;
                refreshRadarRef.current();
            }, result.nextRefreshDelay);
        }
        setPayload(result.refreshed);
        setFreshnessStatus('current');
    }, []);

    const markRadarUnavailable = useCallback(() => {
        setFreshnessStatus('unavailable');
    }, []);

    const refreshRadar = useCallback(() => {
        if (refreshTimerRef.current !== null) {
            window.clearTimeout(refreshTimerRef.current);
            refreshTimerRef.current = null;
        }
        setFreshnessStatus('checking');
        void requestRadar().then(applyRadarRefresh, markRadarUnavailable);
    }, [applyRadarRefresh, markRadarUnavailable, requestRadar]);

    useLayoutEffect(() => {
        refreshRadarRef.current = refreshRadar;
    }, [refreshRadar]);

    useEffect(() => {
        let active = true;
        void requestRadar().then(
            (result) => {
                if (active) applyRadarRefresh(result);
            },
            () => {
                if (active) markRadarUnavailable();
            },
        );
        return () => {
            active = false;
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
                    if (activeRadarTaskRef.current?.signalState === signalState) {
                        activeRadarTaskRef.current = null;
                    }
                    const message = t('feedback.taskCreated', { subject: signal.subject.label });
                    if (signal.family === 'warm_path') resolveWarmPathSignal(updated, message);
                    else updateSignal(updated, message, undefined, false);
                },
            },
        });
        if (result.status !== 'completed') {
            if (activeRadarTaskRef.current?.signalState === signalState) {
                activeRadarTaskRef.current = null;
            }
            const message = t('feedback.actionFailed', { subject: signal.subject.label });
            setAnnouncement(message);
            if (result.status !== 'failed') toastError(message);
        }
    };

    return (
        <div className="space-y-8">
            <p className="sr-only" aria-live="polite" aria-atomic="true">{announcement}</p>

            <section aria-label={t('filters.label')} className="flex flex-col gap-3 rounded-2xl border border-border bg-card p-4 sm:flex-row sm:items-center">
                <div className="relative min-w-0 flex-1">
                    <MagnifyingGlassIcon className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" aria-hidden />
                    <Input
                        value={query}
                        onChange={(event) => setQuery(event.target.value)}
                        placeholder={t('filters.searchPlaceholder')}
                        aria-label={t('filters.searchLabel')}
                        className="pl-9"
                    />
                </div>
                <div className="grid grid-cols-2 gap-2 sm:flex">
                    <Select value={family} onValueChange={(value) => {
                        if (isRadarFamilyFilter(value)) setFamily(value);
                    }}>
                        <SelectTrigger id="radar-family-filter" data-action-focus-fallback className="min-h-11 w-full sm:w-auto" aria-label={t('filters.familyLabel')}>
                            <AdjustmentsHorizontalIcon aria-hidden />
                            <SelectValue />
                        </SelectTrigger>
                        <SelectContent align="end">
                            <SelectItem value="all">{t('filters.allFamilies')}</SelectItem>
                            <SelectItem value="relationship_decay">{t('family.relationship_decay')}</SelectItem>
                            <SelectItem value="deal_risk">{t('family.deal_risk')}</SelectItem>
                            <SelectItem value="warm_path">{t('family.warm_path')}</SelectItem>
                        </SelectContent>
                    </Select>
                    <Select value={state} onValueChange={(value) => {
                        if (isRadarStateFilter(value)) setState(value);
                    }}>
                        <SelectTrigger className="min-h-11 w-full sm:w-auto" aria-label={t('filters.stateLabel')}>
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

            {visibleSignals.length > 0 ? (
                <ol className="space-y-4" aria-label={t('listLabel')}>
                    {visibleSignals.map((signal) => (
                        <RadarSignalCard
                            key={`${signal.id}:${payload.asOf}`}
                            signal={signal}
                            pageAsOf={payload.asOf}
                            freshnessStatus={freshnessStatus}
                            busy={busyId !== null}
                            snoozeOpen={snoozeId === signal.id}
                            onSnoozeOpenChange={(open) => setSnoozeId(open ? signal.id : null)}
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
            ) : null}
        </div>
    );
}
