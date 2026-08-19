'use client';

import type { ReactNode } from 'react';
import Link from 'next/link';
import { useTranslations } from 'next-intl';
import { ChevronDownIcon } from '@heroicons/react/24/outline';
import {
    ArrowTrendingDownIcon,
    ArrowTrendingUpIcon,
    MinusIcon,
} from '@heroicons/react/16/solid';

import { RadarMark, RadarSignalChip } from '@/app/components/radar/RadarVocabulary';
import { RADAR_MARK_FILL } from '@/app/components/radar/radarFamilyAccent';
import {
    radarConnectors,
    radarDecaySummary,
    radarRiskReasons,
} from '@/app/components/radar/radarHorizon';
import { radarRecordHref } from '@/app/components/radar/radarReferences';
import type { RadarFamily, RadarSignal, TemperatureBand, TemperatureTrend } from '@/app/lib/types';
import {
    Collapsible,
    CollapsibleContent,
    CollapsibleTrigger,
} from '@/components/ui/collapsible';
import { cn } from '@/lib/utils';

const WARMTH_ORDER: readonly TemperatureBand[] = ['cold', 'cool', 'warm', 'hot'];

const TREND_ICONS = {
    cooling: ArrowTrendingDownIcon,
    steady: MinusIcon,
    rising: ArrowTrendingUpIcon,
} satisfies Record<TemperatureTrend, typeof MinusIcon>;

const TREND_TONE = {
    cooling: 'text-warmth-cold',
    steady: 'text-muted-foreground',
    rising: 'text-warmth-hot',
} satisfies Record<TemperatureTrend, string>;

const FAMILY_TONE = {
    relationship_decay: 'cool',
    deal_risk: 'high',
    warm_path: 'path',
} as const;

/**
 * How cold the flagged relationships have gone, and which way they are moving.
 *
 * The scale is ordered coldest-first because that is the end that costs something, and each band's
 * width is its share of the group, so the shape of the bar is the shape of the problem.
 */
function DecaySummary({ signals }: { signals: readonly RadarSignal[] }) {
    const t = useTranslations('Radar');
    const tBand = useTranslations('Temperature');
    const summary = radarDecaySummary(signals);
    if (summary.total === 0) return null;

    return (
        <div className="space-y-2">
            <div className="flex gap-1.5">
                {WARMTH_ORDER.map((band) => {
                    const count = summary.bands[band];
                    if (count === 0) return null;
                    return (
                        <div key={band} className="min-w-0" style={{ flexGrow: count }}>
                            <div className={cn('h-1.5 rounded-full', RADAR_MARK_FILL[band])} />
                            <p className="mt-1.5 truncate text-xs text-muted-foreground">
                                <span className="font-semibold tabular-nums text-foreground">{count}</span>
                                {' '}
                                {tBand(band)}
                            </p>
                        </div>
                    );
                })}
            </div>
            <p className="flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted-foreground">
                {(Object.keys(TREND_ICONS) as TemperatureTrend[]).map((trend) => {
                    const count = summary.trends[trend];
                    if (count === 0) return null;
                    const Icon = TREND_ICONS[trend];
                    return (
                        <span key={trend} className="inline-flex items-center gap-1">
                            <Icon className={cn('size-3.5', TREND_TONE[trend])} aria-hidden />
                            <span className="tabular-nums text-foreground">{count}</span>
                            {t(`decay.trend${trend === 'rising' ? 'Rising' : trend === 'steady' ? 'Steady' : 'Cooling'}`)}
                        </span>
                    );
                })}
            </p>
        </div>
    );
}

/**
 * Why the flagged deals are flagged. A count of deals says how much is wrong; this says what, so
 * the group can be read without opening a single deal.
 */
function RiskSummary({ signals }: { signals: readonly RadarSignal[] }) {
    const t = useTranslations('Radar');
    const reasons = radarRiskReasons(signals);
    const reasonNames: Record<string, string> = {
        close_overdue: t('reasonShort.close_overdue'),
        closing_soon_quiet: t('reasonShort.closing_soon_quiet'),
        stalled: t('reasonShort.stalled'),
        stakeholder_cold: t('reasonShort.stakeholder_cold'),
        no_stakeholders: t('reasonShort.no_stakeholders'),
    };
    if (reasons.length === 0) return null;

    return (
        <ul className="flex flex-wrap gap-1.5">
            {reasons.map((reason) => (
                <li key={reason.code}>
                    <RadarSignalChip
                        tone={reason.severity}
                        label={reasonNames[reason.code] ?? t('reasonShort.unknown')}
                        count={reason.count}
                    />
                </li>
            ))}
        </ul>
    );
}

/**
 * The people who can open the most doors.
 *
 * This is the one true cluster in Radar's data: intro-path evidence names its connector, so a
 * person recurring across several paths is a fact. Each connector is a link to that contact, which
 * is usually where the conversation actually starts.
 */
function ConnectorSummary({ signals }: { signals: readonly RadarSignal[] }) {
    const t = useTranslations('Radar');
    const connectors = radarConnectors(signals);
    if (connectors.length === 0) return null;

    return (
        <div className="space-y-1.5">
            <p className="text-xs text-muted-foreground">{t('path.connectorsHeading')}</p>
            <ul className="flex flex-wrap gap-1.5">
                {connectors.map((connector) => {
                    const href = radarRecordHref('person', connector.personId);
                    const inner = (
                        <>
                            <RadarMark tone="path" />
                            {connector.name}
                            <span className="tabular-nums text-muted-foreground">
                                {t('path.opens', { count: connector.reach })}
                            </span>
                        </>
                    );
                    const chip = 'inline-flex items-center gap-1.5 whitespace-nowrap rounded-full bg-muted px-2 py-0.5 text-xs font-medium text-foreground ring-1 ring-border ring-inset';
                    return (
                        <li key={connector.personId}>
                            {href === null ? (
                                <span className={chip}>{inner}</span>
                            ) : (
                                <Link
                                    href={href}
                                    className={cn(chip, 'transition-colors duration-(--motion-micro) hover:bg-foreground/10 motion-reduce:transition-none')}
                                >
                                    {inner}
                                </Link>
                            )}
                        </li>
                    );
                })}
            </ul>
        </div>
    );
}

type RadarFamilyLayerProps = {
    family: RadarFamily;
    signals: readonly RadarSignal[];
    unavailable: boolean;
    open: boolean;
    onOpenChange: (open: boolean) => void;
    children: ReactNode;
};

/**
 * One signal family as a layer: what this group looks like as a whole, and the signals inside it.
 *
 * Every layer keeps its summary visible whether it is open or closed, because the summary is the
 * answer to the family's own question and the rows are only the detail behind it. Each family draws
 * its summary in its own shape — a warmth scale, a tally of reasons, a set of people — so the three
 * groups never collapse into the same card repeated three times.
 */
export default function RadarFamilyLayer({
    family,
    signals,
    unavailable,
    open,
    onOpenChange,
    children,
}: RadarFamilyLayerProps) {
    const t = useTranslations('Radar');
    const headingId = `radar-layer-${family}`;
    const contentId = `radar-layer-content-${family}`;

    return (
        <Collapsible open={open} onOpenChange={onOpenChange}>
            <section aria-labelledby={headingId} className="rounded-2xl bg-card ring-1 ring-border ring-inset">
                <div className="space-y-3 px-4 pt-4 pb-3">
                    <h2 id={headingId} className="flex">
                        <CollapsibleTrigger
                            aria-controls={contentId}
                            className="group/layer flex w-full items-center gap-2 rounded-lg text-left text-sm font-semibold text-foreground outline-none focus-visible:ring-3 focus-visible:ring-ring/50"
                        >
                            <RadarMark tone={FAMILY_TONE[family]} family={family} />
                            {t(`family.${family}`)}
                            <span className="font-normal tabular-nums text-muted-foreground">{signals.length}</span>
                            <ChevronDownIcon
                                aria-hidden
                                className={cn(
                                    'ml-auto size-4 text-muted-foreground transition-transform duration-(--motion-micro) motion-reduce:transition-none',
                                    open && 'rotate-180',
                                )}
                            />
                        </CollapsibleTrigger>
                    </h2>

                    {unavailable ? (
                        <p className="text-xs text-warning-foreground" role="status">{t('layer.unavailable')}</p>
                    ) : null}

                    {signals.length === 0 ? (
                        <p className="text-xs text-muted-foreground">
                            {t(unavailable ? 'layer.emptyUnavailable' : 'layer.empty')}
                        </p>
                    ) : family === 'relationship_decay' ? (
                        <DecaySummary signals={signals} />
                    ) : family === 'deal_risk' ? (
                        <RiskSummary signals={signals} />
                    ) : (
                        <ConnectorSummary signals={signals} />
                    )}
                </div>

                <CollapsibleContent
                    id={contentId}
                    className="overflow-hidden duration-(--motion-standard) ease-calm data-closed:animate-collapsible-up data-closed:duration-(--motion-micro) data-open:animate-collapsible-down motion-reduce:animate-none!"
                >
                    {signals.length > 0 ? (
                        <div className="border-t border-border">{children}</div>
                    ) : null}
                </CollapsibleContent>
            </section>
        </Collapsible>
    );
}
