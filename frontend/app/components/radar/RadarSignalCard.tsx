'use client';

import { useLocale, useTranslations } from 'next-intl';
import {
    ArrowTopRightOnSquareIcon,
    BellSnoozeIcon,
    BookmarkIcon,
    BriefcaseIcon,
    BuildingOffice2Icon,
    CheckCircleIcon,
    ClipboardDocumentCheckIcon,
    ExclamationTriangleIcon,
    UserIcon,
    XMarkIcon,
} from '@heroicons/react/24/outline';

import type { RadarSignal } from '@/app/lib/types';
import { usePermissionCheck } from '@/app/hooks/usePermissions';
import RadarSnoozeDialog from '@/app/components/radar/RadarSnoozeDialog';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { parseMysqlDateTime } from '@/app/lib/utils';
import { cn } from '@/lib/utils';

type RadarSignalCardProps = {
    signal: RadarSignal;
    pageAsOf: string;
    busy: boolean;
    snoozeOpen: boolean;
    onSnoozeOpenChange: (open: boolean) => void;
    onFollow: () => void;
    onSnooze: (until: string) => void;
    onDismiss: () => void;
    onCreateTask: () => void;
    onOpenContext: () => void;
};

const SUBJECT_ICONS = {
    person: UserIcon,
    company: BuildingOffice2Icon,
    deal: BriefcaseIcon,
} satisfies Record<RadarSignal['subject']['type'], typeof UserIcon>;

function freshnessLabels(value: string, asOf: string, locale: string): { absolute: string; relative: string } | null {
    const timestamp = parseMysqlDateTime(value);
    const anchor = parseMysqlDateTime(asOf);
    if (!Number.isFinite(timestamp) || !Number.isFinite(anchor)) return null;
    const difference = timestamp - anchor;
    const absolute = new Intl.DateTimeFormat(locale, {
        dateStyle: 'medium',
        timeStyle: 'short',
    }).format(timestamp);
    const minutes = Math.round(difference / 60_000);
    const hours = Math.round(difference / 3_600_000);
    const days = Math.round(difference / 86_400_000);
    const formatter = new Intl.RelativeTimeFormat(locale, { numeric: 'auto' });
    const relative = Math.abs(minutes) < 60
        ? formatter.format(minutes, 'minute')
        : Math.abs(hours) < 24
            ? formatter.format(hours, 'hour')
            : formatter.format(days, 'day');
    return { absolute, relative };
}

/** Responsive, evidence-first presentation for one canonical ranked Radar signal. */
export default function RadarSignalCard({
    signal,
    pageAsOf,
    busy,
    snoozeOpen,
    onSnoozeOpenChange,
    onFollow,
    onSnooze,
    onDismiss,
    onCreateTask,
    onOpenContext,
}: RadarSignalCardProps) {
    const t = useTranslations('Radar');
    const locale = useLocale();
    const taskPermission = usePermissionCheck('TASK_CREATE');
    const canCreateTask = taskPermission === 'granted';
    const SubjectIcon = SUBJECT_ICONS[signal.subject.type];
    const freshness = freshnessLabels(signal.evidenceAsOf, pageAsOf, locale);
    const hasWarmPathBridge = signal.family !== 'warm_path' || signal.evidence.some((evidence) => (
        evidence.type === 'warm_path'
        && typeof evidence.parameters.bridgePersonId === 'number'
    ));
    const taskDisabled = busy || !canCreateTask || signal.stale || signal.taskId != null || !hasWarmPathBridge;
    const followed = signal.state === 'followed';
    const dismissed = signal.state === 'dismissed';
    const rankRules: Record<string, string> = {
        priority_then_source_strength_then_subject: t('rank.rulePriority'),
    };
    const rankKeys: Record<string, string> = {
        priority: t('rank.keyPriority'),
        daysUntilCold: t('rank.keyDaysUntilCold'),
        daysSinceTouch: t('rank.keyDaysSinceTouch'),
        riskScore: t('rank.keyRiskScore'),
        pathScore: t('rank.keyPathScore'),
        subject: t('rank.keySubject'),
    };
    const rankDirections: Record<string, string> = {
        ascending: t('rank.directionAscending'),
        descending: t('rank.directionDescending'),
    };
    const evidenceTypes: Record<string, string> = {
        relationship_temperature: t('evidence.typeRelationshipTemperature'),
        close_overdue: t('evidence.typeCloseOverdue'),
        closing_soon_quiet: t('evidence.typeClosingSoonQuiet'),
        stalled: t('evidence.typeStalled'),
        stakeholder_cold: t('evidence.typeStakeholderCold'),
        no_stakeholders: t('evidence.typeNoStakeholders'),
        warm_path: t('evidence.typeWarmPath'),
    };
    const parameterKeys: Record<string, string> = {
        band: t('evidence.parameterBand'),
        trend: t('evidence.parameterTrend'),
        score: t('evidence.parameterScore'),
        lastTouchAt: t('evidence.parameterLastTouchAt'),
        daysSinceTouch: t('evidence.parameterDaysSinceTouch'),
        goesColdAt: t('evidence.parameterGoesColdAt'),
        daysUntilCold: t('evidence.parameterDaysUntilCold'),
        touchCount: t('evidence.parameterTouchCount'),
        modelVersion: t('evidence.parameterModelVersion'),
        severity: t('evidence.parameterSeverity'),
        bridgePersonId: t('evidence.parameterBridgePersonId'),
        bridgeName: t('evidence.parameterBridgeName'),
        reachType: t('evidence.parameterReachType'),
        evidenceType: t('evidence.parameterEvidenceType'),
        evidenceCompany: t('evidence.parameterEvidenceCompany'),
        overlapStartYear: t('evidence.parameterOverlapStartYear'),
        overlapEndYear: t('evidence.parameterOverlapEndYear'),
        pathScore: t('evidence.parameterPathScore'),
        daysOverdue: t('evidence.parameterDaysOverdue'),
        daysUntilClose: t('evidence.parameterDaysUntilClose'),
        personId: t('evidence.parameterPersonId'),
        role: t('evidence.parameterRole'),
    };
    const referenceTypes: Record<string, string> = {
        person: t('evidence.referencePerson'),
        company: t('evidence.referenceCompany'),
        deal: t('evidence.referenceDeal'),
        person_edge: t('evidence.referencePersonEdge'),
    };
    const enumValues: Record<string, string> = {
        hot: t('value.hot'),
        warm: t('value.warm'),
        cool: t('value.cool'),
        cold: t('value.cold'),
        rising: t('value.rising'),
        steady: t('value.steady'),
        cooling: t('value.cooling'),
        high: t('value.high'),
        medium: t('value.medium'),
        low: t('value.low'),
        opportunity: t('value.opportunity'),
        connection: t('value.connection'),
        colleagues: t('value.colleagues'),
        former_colleagues: t('value.former_colleagues'),
        reach: t('value.reach'),
        rewarm: t('value.rewarm'),
    };
    const formatValue = (key: string, value: unknown): string => {
        if (typeof value === 'number') return new Intl.NumberFormat(locale).format(value);
        if (typeof value === 'boolean') return value ? t('value.yes') : t('value.no');
        if (typeof value === 'string') {
            if (enumValues[value]) return enumValues[value];
            if (key === 'lastTouchAt' || key === 'goesColdAt') {
                const timestamp = parseMysqlDateTime(value);
                if (Number.isFinite(timestamp)) return new Intl.DateTimeFormat(locale, { dateStyle: 'medium' }).format(timestamp);
            }
            return value.includes('_') ? t('value.unavailable') : value;
        }
        if (Array.isArray(value)) {
            const parts = value.map((entry) => formatValue(key, entry)).filter((entry) => entry !== t('value.unavailable'));
            return parts.length > 0 ? parts.join(t('evidence.separator')) : t('value.unavailable');
        }
        return t('value.unavailable');
    };

    return (
        <li>
            <article
                id={`radar-signal-${signal.id}`}
                tabIndex={-1}
                aria-busy={busy}
                className="group rounded-2xl border border-border bg-card p-5 outline-none focus-visible:ring-3 focus-visible:ring-ring/50 sm:p-6"
            >
                <div className="flex flex-col gap-5 xl:grid xl:grid-cols-[minmax(14rem,0.8fr)_minmax(22rem,1.5fr)_minmax(17rem,1fr)] xl:items-start xl:gap-8">
                    <div className="min-w-0">
                        <div className="flex items-start gap-3">
                            <span className="flex size-10 shrink-0 items-center justify-center rounded-xl bg-muted text-muted-foreground">
                                <SubjectIcon className="size-5" aria-hidden />
                            </span>
                            <div className="min-w-0">
                                <p className="text-xs font-medium text-muted-foreground">
                                    {t(`family.${signal.family}`)}
                                </p>
                                <h2 className="truncate text-base font-semibold text-foreground">{signal.subject.label}</h2>
                                <p className="mt-1 text-xs text-muted-foreground">
                                    {t(`subject.${signal.subject.type}`)}
                                </p>
                            </div>
                        </div>
                        <div className="mt-4 flex flex-wrap gap-2">
                            <Badge
                                variant="outline"
                                className={cn(
                                    signal.priority === 'high' && 'border-destructive/30 bg-destructive/10 text-destructive',
                                    (signal.priority === 'medium' || signal.priority === 'cooling') && 'border-warning/40 bg-warning/10 text-warning-foreground',
                                    signal.priority === 'opportunity' && 'border-brand/30 bg-brand-light text-brand-dark',
                                )}
                            >
                                {t(`priority.${signal.priority}`)}
                            </Badge>
                            <Badge variant="secondary">{t(`state.${signal.state}`)}</Badge>
                            {signal.stale ? (
                                <Badge variant="outline" className="border-warning/40 bg-warning/10 text-warning-foreground">
                                    <ExclamationTriangleIcon aria-hidden />
                                    {t('freshness.stale')}
                                </Badge>
                            ) : null}
                        </div>
                    </div>

                    <div className="min-w-0 space-y-5">
                        <section aria-labelledby={`radar-rank-${signal.id}`}>
                            <p id={`radar-rank-${signal.id}`} className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">
                                {t('rank.heading', { position: signal.rank.position })}
                            </p>
                            <p className="mt-1.5 text-sm text-foreground">
                                {t('rank.rule', { rule: rankRules[signal.rank.rule] ?? t('rank.unknownRule') })}
                            </p>
                            <ul className="mt-2 flex flex-wrap gap-2">
                                {signal.rank.factors.map((factor) => (
                                    <li key={`${factor.key}:${factor.direction}`} className="rounded-lg bg-muted px-2.5 py-1 text-xs text-muted-foreground">
                                        {t('rank.factor', {
                                            key: rankKeys[factor.key] ?? t('rank.unknownFactor'),
                                            direction: rankDirections[factor.direction] ?? t('rank.unknownDirection'),
                                            value: formatValue(factor.key, factor.value),
                                        })}
                                    </li>
                                ))}
                            </ul>
                        </section>

                        <section aria-labelledby={`radar-evidence-${signal.id}`}>
                            <p id={`radar-evidence-${signal.id}`} className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">
                                {t('evidence.heading')}
                            </p>
                            <ul className="mt-2 space-y-2">
                                {signal.evidence.map((evidence, index) => (
                                    <li key={`${evidence.type}:${index}`} className="text-sm text-foreground">
                                        <p className="font-medium">{evidenceTypes[evidence.type] ?? t('evidence.unknownType')}</p>
                                        {Object.entries(evidence.parameters).length > 0 ? (
                                            <p className="mt-0.5 text-xs text-muted-foreground">
                                                {Object.entries(evidence.parameters)
                                                    .map(([key, value]) => t('evidence.parameter', {
                                                        key: parameterKeys[key] ?? t('evidence.unknownParameter'),
                                                        value: formatValue(key, value),
                                                    }))
                                                    .join(t('evidence.separator'))}
                                            </p>
                                        ) : null}
                                        {evidence.references.length > 0 ? (
                                            <p className="mt-0.5 text-xs text-muted-foreground">
                                                {t('evidence.references', {
                                                    references: evidence.references
                                                        .map((reference) => `${referenceTypes[reference.type] ?? t('evidence.unknownReference')} #${reference.id}`)
                                                        .join(', '),
                                                })}
                                            </p>
                                        ) : null}
                                    </li>
                                ))}
                            </ul>
                        </section>
                    </div>

                    <div className="flex min-w-0 flex-col gap-4 xl:items-end">
                        <div className="text-sm text-muted-foreground xl:text-right">
                            <p className="font-medium text-foreground">{t('freshness.heading')}</p>
                            {freshness ? (
                                <time dateTime={signal.evidenceAsOf} title={freshness.absolute}>
                                    {freshness.relative}
                                    <span className="block text-xs">{freshness.absolute}</span>
                                </time>
                            ) : (
                                <span>{t('freshness.unavailable')}</span>
                            )}
                        </div>

                        <div className="grid grid-cols-2 gap-2 sm:flex sm:flex-wrap sm:justify-end" aria-label={t('actions.label', { subject: signal.subject.label })}>
                            <Button
                                type="button"
                                variant="outline"
                                className="min-h-11"
                                onClick={onFollow}
                                disabled={busy || followed}
                                aria-label={t('actions.followNamed', { subject: signal.subject.label })}
                            >
                                {followed ? <CheckCircleIcon aria-hidden /> : <BookmarkIcon aria-hidden />}
                                {followed ? t('actions.followed') : t('actions.follow')}
                            </Button>
                            <Button
                                type="button"
                                variant="outline"
                                className="min-h-11"
                                onClick={() => onSnoozeOpenChange(true)}
                                disabled={busy}
                                aria-label={t('actions.snoozeNamed', { subject: signal.subject.label })}
                            >
                                <BellSnoozeIcon aria-hidden />
                                {t('actions.snooze')}
                            </Button>
                            <Button
                                type="button"
                                variant="outline"
                                className="min-h-11"
                                onClick={onDismiss}
                                disabled={busy || dismissed}
                                aria-label={t('actions.dismissNamed', { subject: signal.subject.label })}
                            >
                                <XMarkIcon aria-hidden />
                                {t('actions.dismiss')}
                            </Button>
                            <Button
                                type="button"
                                variant="outline"
                                className="min-h-11"
                                onClick={onCreateTask}
                                disabled={taskDisabled}
                                aria-label={signal.taskId != null
                                    ? t('actions.taskCreatedNamed', { subject: signal.subject.label })
                                    : !canCreateTask
                                    ? t(taskPermission === 'denied'
                                        ? 'actions.createTaskDeniedNamed'
                                        : 'actions.createTaskPermissionUnavailableNamed', { subject: signal.subject.label })
                                    : signal.stale
                                        ? t('actions.createTaskStaleNamed', { subject: signal.subject.label })
                                        : !hasWarmPathBridge
                                            ? t('actions.createTaskPathUnavailableNamed', { subject: signal.subject.label })
                                            : t('actions.createTaskNamed', { subject: signal.subject.label })}
                                title={signal.taskId != null
                                    ? t('actions.taskCreatedNamed', { subject: signal.subject.label })
                                    : !canCreateTask
                                    ? t(taskPermission === 'denied'
                                        ? 'actions.taskPermissionDenied'
                                        : 'actions.taskPermissionUnavailable')
                                    : signal.stale
                                        ? t('actions.taskStale')
                                        : !hasWarmPathBridge
                                            ? t('actions.taskPathUnavailable')
                                        : undefined}
                            >
                                {signal.taskId != null ? <CheckCircleIcon aria-hidden /> : <ClipboardDocumentCheckIcon aria-hidden />}
                                {signal.taskId != null ? t('actions.taskCreated') : t('actions.createTask')}
                            </Button>
                            <Button
                                type="button"
                                className="col-span-2 min-h-11 sm:col-span-1"
                                onClick={onOpenContext}
                                disabled={busy}
                                aria-label={t('actions.openContextNamed', { subject: signal.subject.label })}
                            >
                                <ArrowTopRightOnSquareIcon aria-hidden />
                                {t('actions.openContext')}
                            </Button>
                        </div>
                    </div>
                </div>
            </article>
            {snoozeOpen ? (
                <RadarSnoozeDialog
                    signal={signal}
                    open
                    busy={busy}
                    onOpenChange={onSnoozeOpenChange}
                    onSnooze={onSnooze}
                />
            ) : null}
        </li>
    );
}
