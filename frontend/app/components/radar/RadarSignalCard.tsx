'use client';

import { useLocale, useTranslations } from 'next-intl';
import {
    ArrowTopRightOnSquareIcon,
    BellSnoozeIcon,
    BookmarkIcon,
    BriefcaseIcon,
    BuildingOffice2Icon,
    CheckCircleIcon,
    ChevronDownIcon,
    ClipboardDocumentCheckIcon,
    ExclamationTriangleIcon,
    UserIcon,
    XMarkIcon,
} from '@heroicons/react/24/outline';

import type { RadarSignal } from '@/app/lib/types';
import type { PermissionCheck } from '@/app/lib/permissionState';
import { usePermissionCheck, usePermissionsRefresh } from '@/app/hooks/usePermissions';
import RadarSnoozeDialog from '@/app/components/radar/RadarSnoozeDialog';
import { FAMILY_DOTS } from '@/app/components/radar/radarFamilyAccent';
import { RADAR_PRESSABLE_SURFACE } from '@/app/components/radar/radarControlSurface';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { parseMysqlDateTime } from '@/app/lib/utils';
import { cn } from '@/lib/utils';

type RadarSignalCardProps = {
    signal: RadarSignal;
    pageAsOf: string;
    freshnessStatus: 'checking' | 'current' | 'unavailable';
    busy: boolean;
    snoozeOpen: boolean;
    onSnoozeOpenChange: (open: boolean) => void;
    expanded: boolean;
    onExpandedChange: (open: boolean) => void;
    onFollow: () => void;
    onSnooze: (until: string) => void;
    onDismiss: () => void;
    onCreateTask: () => void;
    onRefreshEvidence: () => void;
    onOpenContext: () => void;
};

const SUBJECT_ICONS = {
    person: UserIcon,
    company: BuildingOffice2Icon,
    deal: BriefcaseIcon,
} satisfies Record<RadarSignal['subject']['type'], typeof UserIcon>;

const PRIORITY_TEXT = {
    high: 'text-destructive',
    medium: 'text-warning-foreground',
    cooling: 'text-warning-foreground',
    opportunity: 'text-brand-dark',
} satisfies Record<RadarSignal['priority'], string>;

function freshnessLabels(value: string, anchor: number, locale: string): { absolute: string; relative: string } | null {
    const timestamp = parseMysqlDateTime(value);
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

function requiredTaskPermission(
    taskPermission: PermissionCheck,
    personUpdatePermission: PermissionCheck,
    warmPath: boolean,
): PermissionCheck {
    if (!warmPath) return taskPermission;
    if (taskPermission === 'denied' || personUpdatePermission === 'denied') return 'denied';
    if (taskPermission === 'unavailable' || personUpdatePermission === 'unavailable') return 'unavailable';
    return 'granted';
}

/** Dense triage row for one ranked Radar signal, with its full evidence behind a disclosure. */
export default function RadarSignalCard({
    signal,
    pageAsOf,
    freshnessStatus,
    busy,
    snoozeOpen,
    onSnoozeOpenChange,
    expanded,
    onExpandedChange,
    onFollow,
    onSnooze,
    onDismiss,
    onCreateTask,
    onRefreshEvidence,
    onOpenContext,
}: RadarSignalCardProps) {
    const t = useTranslations('Radar');
    const locale = useLocale();
    const taskPermission = usePermissionCheck('TASK_CREATE');
    const personUpdatePermission = usePermissionCheck('PERSON_UPDATE');
    const refreshPermissions = usePermissionsRefresh();
    const warmPath = signal.family === 'warm_path';
    const taskActionPermission = requiredTaskPermission(
        taskPermission, personUpdatePermission, warmPath);
    const canCreateTask = taskActionPermission === 'granted';
    const SubjectIcon = SUBJECT_ICONS[signal.subject.type];
    const pageTimestamp = parseMysqlDateTime(pageAsOf);
    const stale = signal.stale;
    const freshness = freshnessLabels(signal.evidenceAsOf, pageTimestamp, locale);
    const hasWarmPathBridge = !warmPath || signal.evidence.some((evidence) => (
        evidence.type === 'warm_path'
        && typeof evidence.parameters.bridgePersonId === 'number'
    ));
    const taskDisabled = busy
        || !canCreateTask
        || freshnessStatus !== 'current'
        || stale
        || signal.taskId != null
        || !hasWarmPathBridge;
    const taskPermissionExplanation = !canCreateTask
        ? t(taskActionPermission === 'denied'
            ? warmPath
                ? 'actions.warmPathTaskPermissionDenied'
                : 'actions.taskPermissionDenied'
            : warmPath
                ? 'actions.warmPathTaskPermissionUnavailable'
                : 'actions.taskPermissionUnavailable')
        : null;
    const taskFreshnessExplanation = canCreateTask && freshnessStatus !== 'current'
        ? t(freshnessStatus === 'checking'
            ? 'actions.taskEvidenceChecking'
            : 'actions.taskEvidenceUnavailable')
        : null;
    const taskBlockingExplanation = taskPermissionExplanation ?? taskFreshnessExplanation;
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
    const enumValuesByKey: Record<string, Record<string, string>> = {
        priority: {
            cooling: t('value.cooling'),
            high: t('value.high'),
            medium: t('value.medium'),
            low: t('value.low'),
            opportunity: t('value.opportunity'),
        },
        band: {
            hot: t('value.hot'),
            warm: t('value.warm'),
            cool: t('value.cool'),
            cold: t('value.cold'),
        },
        trend: {
            rising: t('value.rising'),
            steady: t('value.steady'),
            cooling: t('value.cooling'),
        },
        severity: {
            high: t('value.high'),
            medium: t('value.medium'),
            low: t('value.low'),
        },
        evidenceType: {
            connection: t('value.connection'),
            colleagues: t('value.colleagues'),
            former_colleagues: t('value.former_colleagues'),
        },
        reachType: {
            reach: t('value.reach'),
            rewarm: t('value.rewarm'),
        },
    };
    const formatValue = (key: string, value: unknown): string => {
        if (typeof value === 'number') return new Intl.NumberFormat(locale).format(value);
        if (typeof value === 'boolean') return value ? t('value.yes') : t('value.no');
        if (typeof value === 'string') {
            const enumValues = enumValuesByKey[key];
            if (enumValues) return enumValues[value] ?? t('value.unavailable');
            if (key === 'lastTouchAt' || key === 'goesColdAt') {
                const timestamp = parseMysqlDateTime(value);
                if (Number.isFinite(timestamp)) return new Intl.DateTimeFormat(locale, { dateStyle: 'medium' }).format(timestamp);
            }
            return value;
        }
        if (Array.isArray(value)) {
            const parts = value.map((entry) => formatValue(key, entry)).filter((entry) => entry !== t('value.unavailable'));
            return parts.length > 0 ? parts.join(t('evidence.separator')) : t('value.unavailable');
        }
        return t('value.unavailable');
    };

    const summaryEvidence = signal.evidence[0];
    const summary = summaryEvidence
        ? [
            evidenceTypes[summaryEvidence.type] ?? t('evidence.unknownType'),
            ...Object.entries(summaryEvidence.parameters).map(([key, value]) => t('evidence.parameter', {
                key: parameterKeys[key] ?? t('evidence.unknownParameter'),
                value: formatValue(key, value),
            })),
        ].join(t('evidence.separator'))
        : t('rank.rule', { rule: rankRules[signal.rank.rule] ?? t('rank.unknownRule') });
    const detailId = `radar-detail-${signal.id}`;

    return (
        <li className="group/signal border-b border-border/60 last:border-b-0">
            <article
                id={`radar-signal-${signal.id}`}
                tabIndex={-1}
                aria-busy={busy}
                className="scroll-mt-24 px-3 py-3 outline-none transition-colors focus-visible:ring-3 focus-visible:ring-inset focus-visible:ring-ring/50 hover:bg-muted/40 sm:px-4"
            >
                <div className="flex flex-col gap-3 lg:flex-row lg:items-center lg:gap-4">
                    <div className="flex min-w-0 flex-1 items-start gap-3">
                        <span className="mt-0.5 flex size-9 shrink-0 items-center justify-center rounded-lg bg-muted text-muted-foreground">
                            <SubjectIcon className="size-4.5" aria-hidden />
                        </span>
                        <div className="min-w-0 flex-1">
                            <div className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1">
                                <h3 className="min-w-0 truncate text-[0.9375rem] leading-tight font-semibold text-foreground">
                                    {signal.subject.label}
                                </h3>
                                {stale ? (
                                    <Badge variant="outline" className="border-warning/40 bg-warning/10 text-warning-foreground">
                                        <ExclamationTriangleIcon aria-hidden />
                                        {t('freshness.stale')}
                                    </Badge>
                                ) : null}
                                {signal.state !== 'active' ? (
                                    <Badge variant="secondary">{t(`state.${signal.state}`)}</Badge>
                                ) : null}
                            </div>
                            <p className="mt-1 flex flex-wrap items-center gap-x-1.5 text-xs text-muted-foreground">
                                <span className={cn('size-1.5 shrink-0 rounded-full', FAMILY_DOTS[signal.family])} aria-hidden />
                                <span>{t(`family.${signal.family}`)}</span>
                                <span aria-hidden>·</span>
                                <span>{t(`subject.${signal.subject.type}`)}</span>
                                <span aria-hidden>·</span>
                                <span className={cn('font-medium', PRIORITY_TEXT[signal.priority])}>
                                    {t(`priority.${signal.priority}`)}
                                </span>
                            </p>
                            <p className="mt-1.5 line-clamp-2 text-sm text-foreground/80 lg:truncate">{summary}</p>
                        </div>
                    </div>

                    <div className="flex shrink-0 flex-col gap-2 lg:flex-row lg:items-center lg:justify-end lg:gap-1">
                        <span className="mr-1 hidden text-xs text-muted-foreground xl:inline">
                            {freshness ? (
                                <time dateTime={signal.evidenceAsOf} title={freshness.absolute}>
                                    {freshness.relative}
                                </time>
                            ) : (
                                t('freshness.unavailable')
                            )}
                        </span>
                        <div className="flex items-center gap-1 lg:contents">
                            <Button
                                type="button"
                                variant="ghost"
                                size="icon"
                                className="size-11 lg:size-9"
                                onClick={onFollow}
                                disabled={busy || followed}
                                aria-label={t('actions.followNamed', { subject: signal.subject.label })}
                                title={followed ? t('actions.followed') : t('actions.follow')}
                            >
                                {followed ? <CheckCircleIcon aria-hidden /> : <BookmarkIcon aria-hidden />}
                            </Button>
                            <Button
                                type="button"
                                variant="ghost"
                                size="icon"
                                className="size-11 lg:size-9"
                                onClick={() => onSnoozeOpenChange(true)}
                                disabled={busy}
                                aria-label={t('actions.snoozeNamed', { subject: signal.subject.label })}
                                title={t('actions.snooze')}
                            >
                                <BellSnoozeIcon aria-hidden />
                            </Button>
                            <Button
                                type="button"
                                variant="ghost"
                                size="icon"
                                className="size-11 lg:size-9"
                                onClick={onDismiss}
                                disabled={busy || dismissed}
                                aria-label={t('actions.dismissNamed', { subject: signal.subject.label })}
                                title={t('actions.dismiss')}
                            >
                                <XMarkIcon aria-hidden />
                            </Button>
                        </div>
                        <div className="grid grid-cols-[1fr_1fr_auto] items-center gap-2 lg:contents">
                            <Button
                                type="button"
                                variant="secondary"
                                size="sm"
                                className={cn('min-h-11 lg:min-h-9', RADAR_PRESSABLE_SURFACE)}
                                onClick={onCreateTask}
                                disabled={taskDisabled}
                                aria-label={signal.taskId != null
                                        ? t('actions.taskCreatedNamed', { subject: signal.subject.label })
                                        : !canCreateTask
                                        ? t(taskActionPermission === 'denied'
                                            ? warmPath
                                                ? 'actions.createWarmPathTaskDeniedNamed'
                                                : 'actions.createTaskDeniedNamed'
                                            : warmPath
                                                ? 'actions.createWarmPathTaskPermissionUnavailableNamed'
                                                : 'actions.createTaskPermissionUnavailableNamed', { subject: signal.subject.label })
                                        : freshnessStatus !== 'current'
                                            ? t(freshnessStatus === 'checking'
                                                ? 'actions.createTaskEvidenceCheckingNamed'
                                                : 'actions.createTaskEvidenceUnavailableNamed', { subject: signal.subject.label })
                                        : stale
                                            ? t('actions.createTaskStaleNamed', { subject: signal.subject.label })
                                            : !hasWarmPathBridge
                                                ? t('actions.createTaskPathUnavailableNamed', { subject: signal.subject.label })
                                                : t('actions.createTaskNamed', { subject: signal.subject.label })}
                                    title={signal.taskId != null
                                        ? t('actions.taskCreatedNamed', { subject: signal.subject.label })
                                        : taskBlockingExplanation ?? (stale
                                            ? t('actions.taskStale')
                                            : !hasWarmPathBridge
                                                ? t('actions.taskPathUnavailable')
                                                : undefined)}
                            >
                                {signal.taskId != null ? <CheckCircleIcon aria-hidden /> : <ClipboardDocumentCheckIcon aria-hidden />}
                                {signal.taskId != null ? t('actions.taskCreated') : t('actions.createTask')}
                            </Button>
                            <Button
                                type="button"
                                size="sm"
                                className="min-h-11 lg:min-h-9"
                                onClick={onOpenContext}
                                disabled={busy}
                                aria-label={t('actions.openContextNamed', { subject: signal.subject.label })}
                            >
                                <ArrowTopRightOnSquareIcon aria-hidden />
                                {t('actions.openContext')}
                            </Button>
                            <Button
                                type="button"
                                variant="ghost"
                                size="icon"
                                className="size-11 lg:size-9"
                                onClick={() => onExpandedChange(!expanded)}
                                aria-expanded={expanded}
                                aria-controls={detailId}
                                aria-label={t(expanded ? 'detail.hideNamed' : 'detail.showNamed', { subject: signal.subject.label })}
                                title={t(expanded ? 'detail.hide' : 'detail.show')}
                            >
                                <ChevronDownIcon className={cn('transition-transform duration-200 motion-reduce:transition-none', expanded && 'rotate-180')} aria-hidden />
                            </Button>
                        </div>
                    </div>
                </div>

                {signal.taskId == null && taskBlockingExplanation ? (
                    <div className="mt-2 flex flex-wrap items-center gap-2 pl-12 text-xs text-muted-foreground" role="status">
                        <span>{taskBlockingExplanation}</span>
                        {taskActionPermission === 'unavailable' ? (
                            <Button
                                type="button"
                                variant="link"
                                size="sm"
                                className="h-auto shrink-0 p-0 text-xs"
                                onClick={() => void refreshPermissions()}
                                disabled={busy}
                            >
                                {t('actions.retryPermissionCheck')}
                            </Button>
                        ) : freshnessStatus === 'unavailable' ? (
                            <Button
                                type="button"
                                variant="link"
                                size="sm"
                                className="h-auto shrink-0 p-0 text-xs"
                                onClick={onRefreshEvidence}
                                disabled={busy}
                            >
                                {t('actions.retryEvidenceRefresh')}
                            </Button>
                        ) : null}
                    </div>
                ) : null}

                <div id={detailId} className={cn('mt-4 gap-5 pl-12 sm:grid-cols-2', expanded ? 'grid' : 'hidden')}>
                    <section aria-labelledby={`radar-evidence-${signal.id}`}>
                        <p id={`radar-evidence-${signal.id}`} className="text-xs font-medium text-muted-foreground">
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

                    <section aria-labelledby={`radar-rank-${signal.id}`}>
                        <p id={`radar-rank-${signal.id}`} className="text-xs font-medium text-muted-foreground">
                            {t('rank.heading', { position: signal.rank.position })}
                        </p>
                        <p className="mt-2 text-sm text-foreground">
                            {t('rank.rule', { rule: rankRules[signal.rank.rule] ?? t('rank.unknownRule') })}
                        </p>
                        <ul className="mt-2 flex flex-wrap gap-1.5">
                            {signal.rank.factors.map((factor) => (
                                <li key={`${factor.key}:${factor.direction}`} className="rounded-md bg-muted px-2 py-0.5 text-xs text-muted-foreground">
                                    {t('rank.factor', {
                                        key: rankKeys[factor.key] ?? t('rank.unknownFactor'),
                                        direction: rankDirections[factor.direction] ?? t('rank.unknownDirection'),
                                        value: formatValue(factor.key, factor.value),
                                    })}
                                </li>
                            ))}
                        </ul>
                        <p className="mt-3 text-xs text-muted-foreground">
                            {t('freshness.heading')}{' '}
                            {freshness ? (
                                <time dateTime={signal.evidenceAsOf} title={freshness.absolute}>
                                    {freshness.relative}{t('evidence.separator')}{freshness.absolute}
                                </time>
                            ) : (
                                <span>{t('freshness.unavailable')}</span>
                            )}
                        </p>
                    </section>
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
