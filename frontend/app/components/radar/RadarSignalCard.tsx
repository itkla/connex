'use client';

import type { ReactNode } from 'react';
import Link from 'next/link';
import { useLocale, useTranslations } from 'next-intl';
import {
    ArrowTopRightOnSquareIcon,
    BellSnoozeIcon,
    BookmarkIcon,
    CheckCircleIcon,
    ChevronDownIcon,
    ClipboardDocumentCheckIcon,
    EllipsisHorizontalIcon,
    ExclamationTriangleIcon,
    UserPlusIcon,
    XMarkIcon,
} from '@heroicons/react/24/outline';

import type { RadarEvidence, RadarSignal } from '@/app/lib/types';
import type { PermissionCheck } from '@/app/lib/permissionState';
import { usePermissionCheck, usePermissionsRefresh } from '@/app/hooks/usePermissions';
import RadarSnoozeDialog from '@/app/components/radar/RadarSnoozeDialog';
import { RadarMark } from '@/app/components/radar/RadarVocabulary';
import {
    radarDecayFacts,
    radarMarkTone,
    radarPathBridges,
    radarRiskFacts,
} from '@/app/components/radar/radarHorizon';
import {
    radarRecordHref,
    radarReferenceLinks,
    radarSignalNames,
    radarSubjectHref,
} from '@/app/components/radar/radarReferences';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { IconButton } from '@/components/ui/icon-button';
import {
    Collapsible,
    CollapsibleContent,
} from '@/components/ui/collapsible';
import {
    DropdownMenu,
    DropdownMenuContent,
    DropdownMenuItem,
    DropdownMenuSeparator,
    DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
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
    /**
     * Opens the record the signal is about. Omit it on a surface that already *is* that record,
     * which also removes the action rather than offering a link back to where the user stands.
     */
    onOpenContext?: () => void;
};

/** Evidence parameters that are provenance rather than product: raw ids and model bookkeeping. */
const INTERNAL_PARAMETERS = new Set(['bridgePersonId', 'personId', 'modelVersion']);

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

/**
 * One ranked signal: what it is, why Radar raised it, and the single thing to do about it.
 *
 * The subject is a real anchor rather than a button that navigates, so middle-click, new-tab, and
 * the browser's own link affordances all work. Exactly one action is promoted — the follow-up the
 * family calls for — while the dispositions that only change what Radar shows sit behind one menu,
 * and the full evidence stays one click away behind "Why".
 */
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
    const pageTimestamp = parseMysqlDateTime(pageAsOf);
    const stale = signal.stale;
    const freshness = freshnessLabels(signal.evidenceAsOf, pageTimestamp, locale);
    const bridges = radarPathBridges(signal);
    const hasWarmPathBridge = !warmPath || bridges.length > 0;
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
    const names = radarSignalNames(signal);
    const subjectHref = radarSubjectHref(signal.subject);
    const detailId = `radar-detail-${signal.id}`;

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
        severity: t('evidence.parameterSeverity'),
        bridgeName: t('evidence.parameterBridgeName'),
        reachType: t('evidence.parameterReachType'),
        evidenceType: t('evidence.parameterEvidenceType'),
        evidenceCompany: t('evidence.parameterEvidenceCompany'),
        overlapStartYear: t('evidence.parameterOverlapStartYear'),
        overlapEndYear: t('evidence.parameterOverlapEndYear'),
        pathScore: t('evidence.parameterPathScore'),
        daysOverdue: t('evidence.parameterDaysOverdue'),
        daysUntilClose: t('evidence.parameterDaysUntilClose'),
        role: t('evidence.parameterRole'),
    };
    const enumValuesByKey: Record<string, Record<string, string>> = {
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

    const decayReading = (): ReactNode => {
        const facts = radarDecayFacts(signal);
        if (!facts) return null;
        const trendLabel = facts.trend === null
            ? null
            : t(`reading.trend${facts.trend === 'rising' ? 'Rising' : facts.trend === 'steady' ? 'Steady' : 'Cooling'}`);
        const lead = facts.band && trendLabel
            ? t('reading.warmth', { band: t(`value.${facts.band}`), trend: trendLabel })
            : facts.band
                ? t('reading.warmthBand', { band: t(`value.${facts.band}`) })
                : trendLabel
                    ? t('reading.warmthTrend', { trend: trendLabel })
                    : null;
        const tail = facts.band === 'cold'
            ? t('reading.wentCold')
            : facts.daysUntilCold !== null && facts.daysUntilCold >= 0
                ? t('reading.goesColdIn', { days: facts.daysUntilCold })
                : facts.daysSinceTouch !== null
                    ? t('reading.sinceContact', { days: facts.daysSinceTouch })
                    : null;
        return [lead, tail].filter((part) => part !== null).join(' ');
    };

    const riskReading = (): ReactNode => {
        const facts = radarRiskFacts(signal);
        const first = facts[0];
        if (!first) return null;
        const lead = first.code === 'close_overdue' && first.daysOverdue !== null
            ? t('reading.closeOverdue', { days: first.daysOverdue })
            : first.code === 'closing_soon_quiet' && first.daysUntilClose !== null
                ? t('reading.closingSoonQuiet', { days: first.daysUntilClose })
                : first.code === 'stalled' && first.daysSinceTouch !== null
                    ? t('reading.stalled', { days: first.daysSinceTouch })
                    : first.code === 'stakeholder_cold'
                        ? t('reading.stakeholderCold', {
                            band: t(`value.${first.band ?? 'cold'}`),
                        })
                        : first.code === 'no_stakeholders'
                            ? t('reading.noStakeholders')
                            : evidenceTypes[first.code] ?? t('evidence.unknownType');
        const more = facts.length - 1;
        return more > 0 ? `${lead} ${t('reading.moreReasons', { count: more })}` : lead;
    };

    const pathReading = (): ReactNode => {
        const first = bridges[0];
        if (!first) return null;
        const href = radarRecordHref('person', first.bridgePersonId);
        const bridgeChunk = (chunks: ReactNode) => (
            href === null ? <>{chunks}</> : (
                <Link href={href} className="font-medium text-foreground underline-offset-2 hover:underline">
                    {chunks}
                </Link>
            )
        );
        const others = bridges.length - 1;
        return others > 0
            ? t.rich('reading.pathShared', {
                name: first.bridgeName,
                count: others,
                bridge: bridgeChunk,
            })
            : t.rich('reading.path', { name: first.bridgeName, bridge: bridgeChunk });
    };

    const reading = signal.family === 'relationship_decay'
        ? decayReading()
        : signal.family === 'deal_risk'
            ? riskReading()
            : pathReading();

    const actionLabel = warmPath ? t('actions.askIntro') : t('actions.followUp');
    const actionNamedLabel = signal.taskId != null
        ? t(warmPath ? 'actions.introAskedNamed' : 'actions.followUpDoneNamed', { subject: signal.subject.label })
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
                        : t(warmPath ? 'actions.askIntroNamed' : 'actions.followUpNamed', { subject: signal.subject.label });

    const evidenceParameters = (evidence: RadarEvidence): [string, unknown][] => (
        Object.entries(evidence.parameters).filter(([key]) => !INTERNAL_PARAMETERS.has(key))
    );

    return (
        <li className="border-b border-border/60 last:border-b-0">
            <Collapsible open={expanded} onOpenChange={onExpandedChange}>
                <article
                    id={`radar-signal-${signal.id}`}
                    tabIndex={-1}
                    aria-busy={busy}
                    className="scroll-mt-24 px-3 py-3 outline-none transition-colors duration-(--motion-micro) focus-visible:ring-3 focus-visible:ring-inset focus-visible:ring-ring/50 hover:bg-muted/40 motion-reduce:transition-none sm:px-4"
                >
                    <div className="flex flex-col gap-3 lg:flex-row lg:items-start lg:gap-4">
                        <div className="flex min-w-0 flex-1 items-start gap-3">
                            <RadarMark
                                tone={radarMarkTone(signal)}
                                family={signal.family}
                                className="mt-1.5"
                            />
                            <div className="min-w-0 flex-1">
                                <div className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1">
                                    <h3 className="min-w-0 text-[0.9375rem] leading-tight font-semibold">
                                        {subjectHref === null ? (
                                            <span className="truncate text-foreground">{signal.subject.label}</span>
                                        ) : (
                                            <Link
                                                href={subjectHref}
                                                className="truncate text-foreground underline-offset-2 outline-none hover:underline focus-visible:underline"
                                            >
                                                {signal.subject.label}
                                            </Link>
                                        )}
                                    </h3>
                                    <span className="text-xs text-muted-foreground">
                                        {t(`subject.${signal.subject.type}`)}
                                    </span>
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
                                <p className="mt-1 text-sm text-foreground/80">{reading}</p>
                            </div>
                        </div>

                        <div className="flex shrink-0 items-center gap-1 lg:justify-end">
                            <Button
                                type="button"
                                variant="secondary"
                                size="toolbar"
                                onClick={onCreateTask}
                                disabled={taskDisabled}
                                aria-label={actionNamedLabel}
                                title={signal.taskId != null
                                    ? t(warmPath ? 'actions.introAsked' : 'actions.followUpDone')
                                    : taskBlockingExplanation ?? (stale
                                        ? t('actions.taskStale')
                                        : !hasWarmPathBridge
                                            ? t('actions.taskPathUnavailable')
                                            : undefined)}
                            >
                                {signal.taskId != null
                                    ? <CheckCircleIcon aria-hidden />
                                    : warmPath
                                        ? <UserPlusIcon aria-hidden />
                                        : <ClipboardDocumentCheckIcon aria-hidden />}
                                {signal.taskId != null
                                    ? t(warmPath ? 'actions.introAsked' : 'actions.followUpDone')
                                    : actionLabel}
                            </Button>

                            <Button
                                type="button"
                                variant="ghost"
                                size="toolbar"
                                onClick={() => onExpandedChange(!expanded)}
                                aria-expanded={expanded}
                                aria-controls={detailId}
                                aria-label={t(expanded ? 'detail.hideNamed' : 'detail.showNamed', { subject: signal.subject.label })}
                            >
                                {t('detail.why')}
                                <ChevronDownIcon
                                    data-icon="inline-end"
                                    aria-hidden
                                    className={cn(
                                        'transition-transform duration-(--motion-micro) motion-reduce:transition-none',
                                        expanded && 'rotate-180',
                                    )}
                                />
                            </Button>

                            <DropdownMenu>
                                <DropdownMenuTrigger asChild>
                                    <IconButton
                                        label={t('actions.moreNamed', { subject: signal.subject.label })}
                                        variant="ghost"
                                        disabled={busy}
                                    >
                                        <EllipsisHorizontalIcon aria-hidden />
                                    </IconButton>
                                </DropdownMenuTrigger>
                                <DropdownMenuContent align="end" className="w-56">
                                    {onOpenContext ? (
                                        <>
                                            <DropdownMenuItem onClick={onOpenContext}>
                                                <ArrowTopRightOnSquareIcon aria-hidden />
                                                {t('actions.openContext')}
                                            </DropdownMenuItem>
                                            <DropdownMenuSeparator />
                                        </>
                                    ) : null}
                                    <DropdownMenuItem onClick={onFollow} disabled={followed}>
                                        {followed ? <CheckCircleIcon aria-hidden /> : <BookmarkIcon aria-hidden />}
                                        {followed ? t('actions.followed') : t('actions.follow')}
                                    </DropdownMenuItem>
                                    <DropdownMenuItem onClick={() => onSnoozeOpenChange(true)}>
                                        <BellSnoozeIcon aria-hidden />
                                        {t('actions.snooze')}
                                    </DropdownMenuItem>
                                    <DropdownMenuItem onClick={onDismiss} disabled={dismissed}>
                                        <XMarkIcon aria-hidden />
                                        {t('actions.dismiss')}
                                    </DropdownMenuItem>
                                </DropdownMenuContent>
                            </DropdownMenu>
                        </div>
                    </div>

                    {signal.taskId == null && taskBlockingExplanation ? (
                        <div className="mt-2 flex flex-wrap items-center gap-2 pl-6 text-xs text-muted-foreground" role="status">
                            <span>{taskBlockingExplanation}</span>
                            {taskActionPermission === 'unavailable' ? (
                                <Button
                                    type="button"
                                    variant="link"
                                    size="inline"
                                    onClick={() => void refreshPermissions()}
                                    disabled={busy}
                                >
                                    {t('actions.retryPermissionCheck')}
                                </Button>
                            ) : freshnessStatus === 'unavailable' ? (
                                <Button
                                    type="button"
                                    variant="link"
                                    size="inline"
                                    onClick={onRefreshEvidence}
                                    disabled={busy}
                                >
                                    {t('actions.retryEvidenceRefresh')}
                                </Button>
                            ) : null}
                        </div>
                    ) : null}

                    <CollapsibleContent
                        id={detailId}
                        className="overflow-hidden duration-(--motion-standard) ease-calm data-[state=closed]:animate-collapsible-up data-[state=closed]:duration-(--motion-micro) data-[state=open]:animate-collapsible-down motion-reduce:animate-none!"
                    >
                        <div className="mt-4 space-y-4 pl-6">
                            <ul className="space-y-3">
                                {signal.evidence.map((evidence, index) => {
                                    const links = radarReferenceLinks(evidence.references, names);
                                    const parameters = evidenceParameters(evidence);
                                    return (
                                        <li key={`${evidence.type}:${index}`} className="text-sm">
                                            <p className="font-medium text-foreground">
                                                {evidenceTypes[evidence.type] ?? t('evidence.unknownType')}
                                            </p>
                                            {parameters.length > 0 ? (
                                                <dl className="mt-1 grid grid-cols-[auto_1fr] gap-x-3 gap-y-0.5 text-xs">
                                                    {parameters.map(([key, value]) => (
                                                        <div key={key} className="col-span-2 grid grid-cols-subgrid">
                                                            <dt className="text-muted-foreground">
                                                                {parameterKeys[key] ?? t('evidence.unknownParameter')}
                                                            </dt>
                                                            <dd className="text-foreground/80">{formatValue(key, value)}</dd>
                                                        </div>
                                                    ))}
                                                </dl>
                                            ) : null}
                                            {links.length > 0 ? (
                                                <p className="mt-1.5 flex flex-wrap items-center gap-x-2 gap-y-1 text-xs text-muted-foreground">
                                                    <span>{t('evidence.sources')}</span>
                                                    {links.map((link) => (
                                                        <Link
                                                            key={link.href}
                                                            href={link.href}
                                                            className="font-medium text-foreground underline-offset-2 hover:underline"
                                                        >
                                                            {link.label}
                                                        </Link>
                                                    ))}
                                                </p>
                                            ) : null}
                                        </li>
                                    );
                                })}
                            </ul>
                            <p className="text-xs text-muted-foreground">
                                {t('freshness.heading')}{' '}
                                {freshness ? (
                                    <time dateTime={signal.evidenceAsOf} title={freshness.absolute}>
                                        {freshness.relative}{t('evidence.separator')}{freshness.absolute}
                                    </time>
                                ) : (
                                    <span>{t('freshness.unavailable')}</span>
                                )}
                            </p>
                        </div>
                    </CollapsibleContent>
                </article>
            </Collapsible>
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
