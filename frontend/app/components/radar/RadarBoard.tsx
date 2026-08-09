'use client';

import { useMemo, useState } from 'react';
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
    getRadarContext,
    snoozeRadarSignal,
} from '@/app/lib/api';
import {
    classifyRadarSurface,
    filterRadarSignals,
    isRadarFamilyFilter,
    isRadarStateFilter,
    replaceRadarSignal,
    unavailableRadarFamilies,
    type RadarFamilyFilter,
    type RadarStateFilter,
} from '@/app/lib/radar';
import { toastError, toastSuccess } from '@/app/lib/toast';
import type { RadarPayload, RadarSignal } from '@/app/lib/types';
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

function warmPathBridgeId(signal: RadarSignal): number | undefined {
    for (const evidence of signal.evidence) {
        if (evidence.type !== 'warm_path') continue;
        const bridgePersonId = evidence.parameters.bridgePersonId;
        if (typeof bridgePersonId === 'number' && Number.isInteger(bridgePersonId)) return bridgePersonId;
    }
    return undefined;
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
    const { run } = useActions();

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
        const result = await run('create.task', {
            source: 'menu',
            record: {
                type: signal.subject.type,
                id: signal.subject.id,
                label: signal.subject.label,
            },
            radarTask: {
                signalId: signal.id,
                version: signal.version,
                description: t('task.defaultDescription', { subject: signal.subject.label }),
                mode: signal.family === 'warm_path' ? 'warm_path' : 'standard',
                bridgePersonId: signal.family === 'warm_path' ? warmPathBridgeId(signal) : undefined,
                onCreated: (updated) => {
                    const message = t('feedback.taskCreated', { subject: signal.subject.label });
                    if (signal.family === 'warm_path') resolveWarmPathSignal(updated, message);
                    else updateSignal(updated, message, undefined, false);
                },
            },
        });
        if (result.status !== 'completed') {
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
                            key={signal.id}
                            signal={signal}
                            pageAsOf={payload.asOf}
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
                            onOpenContext={() => void openContext(signal)}
                        />
                    ))}
                </ol>
            ) : null}
        </div>
    );
}
