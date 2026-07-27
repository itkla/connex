'use client';

import type { ComponentType, SVGProps } from 'react';
import Link from 'next/link';
import { useLocale, useTranslations } from 'next-intl';
import { motion, useReducedMotion } from 'motion/react';
import {
    ArrowTrendingDownIcon,
    BuildingOffice2Icon,
    ChatBubbleLeftRightIcon,
    ClockIcon,
    UsersIcon,
} from '@heroicons/react/24/outline';
import { Loader2Icon } from 'lucide-react';

import { Button } from '@/components/ui/button';
import { useActions } from '@/app/hooks/useActions';
import type { ActivationEvidence, ActivationInsight } from '@/app/lib/activation';
import { easeOut, instant } from '@/app/lib/motion';
import DealRiskPill from '@/app/components/records/deals/DealRiskPill';
import TemperaturePill from '@/app/components/records/TemperaturePill';
import { riskFactorIcon, useRiskText } from '@/app/components/records/deals/dealRisk';
import { formatShortDate, riskContainerClasses, riskTextClass } from '@/app/lib/utils';
import { cn } from '@/lib/utils';

const FOLLOW_UP_ACTION = 'create.task';

type IconComponent = ComponentType<SVGProps<SVGSVGElement>>;

const EVIDENCE_ICONS: Record<Exclude<ActivationEvidence['kind'], 'riskFactor'>, IconComponent> = {
    lastTouch: ClockIcon,
    touchCount: ChatBubbleLeftRightIcon,
    goesCold: ArrowTrendingDownIcon,
    mutualConnections: UsersIcon,
    sharedCompany: BuildingOffice2Icon,
};

function evidenceKey(evidence: ActivationEvidence, index: number): string {
    return evidence.kind === 'riskFactor' ? `${evidence.kind}-${evidence.factor.code}-${index}` : `${evidence.kind}-${index}`;
}

/**
 * The first genuine relationship signal a workspace can support, shown with every recorded fact
 * behind it. The evidence list is the point of the card: the claim in the header is only ever as
 * strong as the rows underneath it, which are read straight from stored interactions.
 */
export default function FirstInsightCard({ insight }: { insight: ActivationInsight }) {
    const t = useTranslations('DashboardActivation');
    const locale = useLocale();
    const reduce = useReducedMotion() ?? false;
    const { factorText } = useRiskText();
    const { run, pendingIds, getAction } = useActions();

    const evidenceText = (evidence: ActivationEvidence): string => {
        switch (evidence.kind) {
            case 'riskFactor':
                return factorText(evidence.factor);
            case 'lastTouch':
                return t('evidence.lastTouch', { date: formatShortDate(evidence.at, locale) });
            case 'touchCount':
                return t('evidence.touchCount', { count: evidence.count });
            case 'goesCold':
                return t('evidence.goesCold', { date: formatShortDate(evidence.at, locale) });
            case 'mutualConnections':
                return t('evidence.mutualConnections', { count: evidence.count });
            case 'sharedCompany':
                return t('evidence.sharedCompany', { company: evidence.company });
        }
    };

    const followUpAvailable = insight.record != null && getAction(FOLLOW_UP_ACTION) != null;
    const followUpPending = pendingIds.has(FOLLOW_UP_ACTION);

    return (
        <div className="flex h-full flex-col overflow-hidden rounded-2xl border border-border bg-card">
            <div className="flex items-center justify-between gap-3 border-b border-border px-5 py-3.5">
                <h3 className="min-w-0 text-sm font-medium text-foreground">
                    {t(`insight.${insight.kind}.headline`)}
                </h3>
                {insight.risk ? <DealRiskPill risk={insight.risk} /> : null}
                {insight.temperature ? <TemperaturePill temp={insight.temperature} /> : null}
            </div>

            <div className="flex flex-1 flex-col gap-4 px-5 py-4">
                <Link href={insight.href} className="min-w-0 transition-opacity hover:opacity-80">
                    <p className="truncate text-base font-medium text-foreground">{insight.title}</p>
                    {insight.subtitle ? (
                        <p className="mt-0.5 truncate text-xs text-muted-foreground">{insight.subtitle}</p>
                    ) : null}
                </Link>

                <div className="min-w-0">
                    <h4 className="text-xs font-medium tracking-[0.12em] text-muted-foreground uppercase">
                        {t('evidenceTitle')}
                    </h4>
                    <ul className="mt-2 grid gap-2">
                        {insight.evidence.map((evidence, index) => {
                            const Icon = evidence.kind === 'riskFactor'
                                ? riskFactorIcon(evidence.factor.code)
                                : EVIDENCE_ICONS[evidence.kind];
                            const severity = evidence.kind === 'riskFactor' ? evidence.factor.severity : null;
                            return (
                                <motion.li
                                    key={evidenceKey(evidence, index)}
                                    initial={reduce ? false : { opacity: 0, y: 6 }}
                                    animate={{ opacity: 1, y: 0 }}
                                    transition={
                                        reduce
                                            ? instant
                                            : { duration: 0.22, delay: index * 0.04, ease: easeOut }
                                    }
                                    className={cn(
                                        'flex items-center gap-3 rounded-lg border px-3.5 py-2.5',
                                        severity
                                            ? riskContainerClasses(severity)
                                            : 'border-border bg-muted/40',
                                    )}
                                >
                                    <Icon
                                        className={cn(
                                            'size-4 shrink-0',
                                            severity ? riskTextClass(severity) : 'text-muted-foreground',
                                        )}
                                        aria-hidden
                                    />
                                    <p className="min-w-0 flex-1 text-sm text-foreground">{evidenceText(evidence)}</p>
                                </motion.li>
                            );
                        })}
                    </ul>
                </div>

                <div className="mt-auto flex flex-wrap items-center gap-2 pt-1">
                    {followUpAvailable ? (
                        <Button
                            type="button"
                            size="sm"
                            variant="brand"
                            disabled={followUpPending}
                            onClick={() => {
                                void run(FOLLOW_UP_ACTION, { source: 'empty-state', record: insight.record });
                            }}
                        >
                            {followUpPending ? <Loader2Icon className="size-3.5 animate-spin" aria-hidden /> : null}
                            {t('followUp')}
                        </Button>
                    ) : null}
                    <Button asChild size="sm" variant="outline">
                        <Link href={insight.href}>{t(`insight.${insight.kind}.open`)}</Link>
                    </Button>
                </div>
            </div>
        </div>
    );
}
