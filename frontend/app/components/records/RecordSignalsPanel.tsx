'use client';

import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import Link from 'next/link';
import { useTranslations } from 'next-intl';

import RadarSignalCard from '@/app/components/radar/RadarSignalCard';
import { radarPathBridges } from '@/app/components/radar/radarHorizon';
import { radarRecordLabel } from '@/app/components/radar/radarLabels';
import { radarSubjectHref } from '@/app/components/radar/radarLinks';
import SectionHeader from '@/app/components/dashboard/SectionHeader';
import { useActions } from '@/app/hooks/useActions';
import {
    dismissRadarSignal,
    followRadarSignal,
    getRadarForSubject,
    snoozeRadarSignal,
} from '@/app/lib/api';
import {
    createRadarTaskSignalStore,
    releaseActiveRadarTask,
    replaceRadarSignal,
    type ActiveRadarTask,
    type RadarFreshnessStatus,
} from '@/app/lib/radar';
import { toastError, toastSuccess } from '@/app/lib/toast';
import type { RadarSignal, RadarSubjectType } from '@/app/lib/types';
import type { TaskDraft } from '@/app/lib/actions/types';

/** The record a Signals block belongs to, in the shape Radar names its subjects. */
export type RecordSignalsSubject = {
    type: RadarSubjectType;
    id: number;
    label: string;
};

/** The bridge contact named by a warm-path signal, when it carries one. */
function warmPathBridge(signal: RadarSignal): { id: number; label: string } | undefined {
    const bridge = radarPathBridges(signal)[0];
    return bridge === undefined
        ? undefined
        : { id: bridge.bridgePersonId, label: bridge.bridgeName };
}

/**
 * The record's own view of Radar: the signals Radar currently holds about this record, each with
 * the same follow, snooze, dismiss, and create-task actions the triage board offers, so a decision
 * taken here is the same decision Radar shows. The evaluation switches a record owns are absorbed
 * as this block's last rows, because what the record contributes to Radar belongs beside what
 * Radar says about it. A read that fails says so rather than rendering as "no signals".
 *
 * The feed is narrowed to this subject in the database via `subjectType`/`subjectId`, so the record
 * reads only the signals it displays rather than paying for the whole workspace's feed and filtering
 * the remainder away in the client.
 */
export default function RecordSignalsPanel({
    subject,
    evaluation,
    className,
}: {
    subject: RecordSignalsSubject;
    /** The record's engine-evaluation switches, rendered as this block's final rows. */
    evaluation?: ReactNode;
    className?: string;
}) {
    const t = useTranslations('RecordSignals');
    const tRadar = useTranslations('Radar');
    const { run } = useActions();
    const [signals, setSignals] = useState<RadarSignal[]>([]);
    const [freshnessStatus, setFreshnessStatus] = useState<RadarFreshnessStatus>('checking');
    const [busyId, setBusyId] = useState<number | null>(null);
    const [snoozeId, setSnoozeId] = useState<number | null>(null);
    const [expandedIds, setExpandedIds] = useState<ReadonlySet<number>>(() => new Set());
    const [asOf, setAsOf] = useState('');
    const [announcement, setAnnouncement] = useState('');
    const sessionRef = useRef({ active: true });
    const draftsRef = useRef(new Map<number, TaskDraft>());
    const activeTaskRef = useRef<ActiveRadarTask | null>(null);

    const withDisplayLabel = (signal: RadarSignal): RadarSignal => {
        const label = radarRecordLabel(signal.subject.label)
            ?? tRadar(`subject.unnamed.${signal.subject.type}`);
        return label === signal.subject.label
            ? signal
            : { ...signal, subject: { ...signal.subject, label } };
    };

    const load = useCallback((session: { active: boolean }) => {
        getRadarForSubject(subject.type, subject.id).then(
            (payload) => {
                if (!session.active) return;
                setSignals(payload.items);
                setAsOf(payload.asOf);
                setFreshnessStatus('current');
            },
            () => {
                if (!session.active) return;
                setFreshnessStatus('unavailable');
            },
        );
    }, [subject.id, subject.type]);

    const refresh = useCallback(() => {
        setFreshnessStatus('checking');
        load(sessionRef.current);
    }, [load]);

    useEffect(() => {
        const session = { active: true };
        sessionRef.current = session;
        load(session);
        return () => {
            session.active = false;
            activeTaskRef.current?.signalState.refresh(undefined, 'unavailable');
            activeTaskRef.current = null;
        };
    }, [load]);

    useEffect(() => {
        const activeTask = activeTaskRef.current;
        if (activeTask === null) return;
        activeTask.signalState.refresh(
            signals.find((signal) => signal.id === activeTask.signalId),
            freshnessStatus,
        );
    }, [freshnessStatus, signals]);

    const mutate = async (
        signal: RadarSignal,
        request: () => Promise<RadarSignal>,
        successMessage: string,
    ) => {
        if (busyId !== null) return;
        setBusyId(signal.id);
        setAnnouncement('');
        try {
            const updated = await request();
            setSignals((current) => replaceRadarSignal(current, updated));
            setAnnouncement(successMessage);
            toastSuccess(successMessage);
            setSnoozeId(null);
        } catch {
            const message = tRadar('feedback.actionFailed', { subject: signal.subject.label });
            setAnnouncement(message);
            toastError(message);
        } finally {
            setBusyId(null);
        }
    };

    const openTask = async (signal: RadarSignal) => {
        const bridge = signal.family === 'warm_path' ? warmPathBridge(signal) : undefined;
        const initialDescription = bridge
            ? tRadar('task.warmPathDescription', { bridge: bridge.label, subject: signal.subject.label })
            : tRadar('task.defaultDescription', { subject: signal.subject.label });
        const draft = draftsRef.current.get(signal.id) ?? { description: initialDescription };
        const signalState = createRadarTaskSignalStore(signal);
        activeTaskRef.current = { signalId: signal.id, signalState };
        const result = await run('create.task', {
            source: 'menu',
            record: { type: signal.subject.type, id: signal.subject.id, label: signal.subject.label },
            radarTask: {
                signalId: signal.id,
                draft,
                mode: signal.family === 'warm_path' ? 'warm_path' : 'standard',
                bridgePersonId: bridge?.id,
                signalState,
                onRefresh: refresh,
                onDraftChange: (nextDraft) => {
                    draftsRef.current.set(signal.id, nextDraft);
                },
                onDraftClear: () => {
                    draftsRef.current.delete(signal.id);
                },
                onCreated: (updated) => {
                    draftsRef.current.delete(signal.id);
                    activeTaskRef.current = releaseActiveRadarTask(activeTaskRef.current, signalState);
                    const message = tRadar('feedback.taskCreated', { subject: signal.subject.label });
                    setSignals((current) => (
                        signal.family === 'warm_path'
                            ? current.filter((item) => item.id !== signal.id)
                            : replaceRadarSignal(current, updated)
                    ));
                    setAnnouncement(message);
                },
                onClosed: () => {
                    activeTaskRef.current = releaseActiveRadarTask(activeTaskRef.current, signalState);
                },
            },
        });
        if (result.status !== 'completed') {
            activeTaskRef.current = releaseActiveRadarTask(activeTaskRef.current, signalState);
            const message = tRadar('feedback.actionFailed', { subject: signal.subject.label });
            setAnnouncement(message);
            if (result.status !== 'failed') toastError(message);
        }
    };

    const href = useMemo(() => radarSubjectHref(subject.label), [subject.label]);
    const unavailable = freshnessStatus === 'unavailable';
    const loading = freshnessStatus === 'checking' && asOf === '';

    return (
        <section aria-label={t('title')} className={className}>
            <p className="sr-only" aria-live="polite" aria-atomic="true">{announcement}</p>
            <SectionHeader
                title={t('title')}
                action={
                    <Link
                        href={href}
                        className="rounded-full px-2 py-1 text-xs text-brand transition-colors hover:text-brand-hover"
                    >
                        {t('openInRadar')}
                    </Link>
                }
            />
            <div className="overflow-hidden rounded-2xl border border-border bg-card">
                {loading ? (
                    <div className="space-y-2 px-4 py-4" aria-hidden>
                        <div className="h-4 w-40 rounded bg-muted" />
                        <div className="h-3 w-64 rounded bg-muted" />
                    </div>
                ) : unavailable ? (
                    <p className="px-6 py-6 text-sm text-muted-foreground">{t('unavailable')}</p>
                ) : signals.length === 0 ? (
                    <p className="px-6 py-6 text-sm text-muted-foreground">{t('empty')}</p>
                ) : (
                    <ol aria-label={t('title')}>
                        {signals.map((rawSignal) => {
                            const signal = withDisplayLabel(rawSignal);
                            return (
                                <RadarSignalCard
                                    key={`${signal.id}:${asOf}`}
                                    signal={signal}
                                    pageAsOf={asOf}
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
                                        tRadar('feedback.followed', { subject: signal.subject.label }),
                                    )}
                                    onSnooze={(until) => void mutate(
                                        signal,
                                        () => snoozeRadarSignal(signal.id, signal.version, until),
                                        tRadar('feedback.snoozed', { subject: signal.subject.label }),
                                    )}
                                    onDismiss={() => void mutate(
                                        signal,
                                        () => dismissRadarSignal(signal.id, signal.version),
                                        tRadar('feedback.dismissed', { subject: signal.subject.label }),
                                    )}
                                    onCreateTask={() => void openTask(signal)}
                                    onRefreshEvidence={refresh}
                                />
                            );
                        })}
                    </ol>
                )}
                {evaluation ? (
                    <div className="border-t border-border pt-4">
                        <p className="px-6 text-xs font-medium uppercase tracking-[0.12em] text-muted-foreground">
                            {t('evaluationHeading')}
                        </p>
                        {evaluation}
                    </div>
                ) : null}
            </div>
        </section>
    );
}
