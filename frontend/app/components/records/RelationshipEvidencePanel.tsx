'use client';

import {
    BoltIcon,
    CheckCircleIcon,
    ClockIcon,
    DocumentTextIcon,
    ExclamationTriangleIcon,
} from '@heroicons/react/24/outline';
import Link from 'next/link';
import { useLocale, useTranslations } from 'next-intl';
import { useMemo } from 'react';

import WarmthPill from '@/app/components/records/WarmthPill';
import ProviderCaptureEvidence from '@/app/components/activity/ProviderCaptureEvidence';
import type {
    RelationshipEvidence,
    RelationshipEvidenceContributor,
    RelationshipEvidenceSourceType,
} from '@/app/lib/types';
import { formatDateTime } from '@/app/lib/utils';
import { cn } from '@/lib/utils';

const SOURCE_ICONS = {
    activity: BoltIcon,
    note: DocumentTextIcon,
    task: CheckCircleIcon,
} satisfies Record<RelationshipEvidenceSourceType, typeof BoltIcon>;

const SOURCE_PATHS: Record<RelationshipEvidenceSourceType, string> = {
    activity: '/activity/activities',
    note: '/activity/notes',
    task: '/activity/tasks',
};
const ACTIVITY_TYPE_KEYS: Record<string, 'activityKind.call' | 'activityKind.email' | 'activityKind.meeting' | 'activityKind.note' | 'activityKind.other'> = {
    call: 'activityKind.call',
    email: 'activityKind.email',
    meeting: 'activityKind.meeting',
    note: 'activityKind.note',
    other: 'activityKind.other',
};
const ATTRIBUTION_KEYS = {
    direct_person_touches: 'attribution.directPerson',
    present_day_person_company_or_deal_company: 'attribution.presentDayCompany',
    touch_time_employer_or_present_day_deal_company: 'attribution.touchTimeCompany',
} as const;

function contributorLabel(
    contributor: RelationshipEvidenceContributor,
    t: ReturnType<typeof useTranslations>,
): string {
    if (contributor.sourceType === 'activity') {
        const normalized = contributor.interactionType?.toLowerCase() ?? 'other';
        const typeKey = ACTIVITY_TYPE_KEYS[normalized];
        const type = typeKey ? t(typeKey) : contributor.interactionType ?? t('activityKind.other');
        return t('activityType', { type });
    }
    return t(`sourceType.${contributor.sourceType}`);
}

/**
 * Explains a canonical contact or company warmth score with bounded source metadata and coverage.
 * Use {@code variant="dialog"} to flush the same chrome into a modal shell (close-button gutter,
 * no outer card border).
 */
export default function RelationshipEvidencePanel({
    evidence,
    className,
    variant = 'page',
}: {
    evidence?: RelationshipEvidence | null;
    className?: string;
    variant?: 'page' | 'dialog';
}) {
    const t = useTranslations('RelationshipEvidence');
    const locale = useLocale();
    const number = useMemo(
        () => new Intl.NumberFormat(locale, { maximumFractionDigits: 2 }),
        [locale],
    );
    const isDialog = variant === 'dialog';

    if (!evidence) {
        return (
            <section
                aria-label={t('title')}
                className={cn(
                    isDialog
                        ? 'bg-card p-5'
                        : 'mt-6 rounded-2xl border border-border bg-card p-5',
                    className,
                )}
            >
                <h2 className="text-xs font-medium uppercase tracking-[0.12em] text-muted-foreground">
                    {t('title')}
                </h2>
                <p className="mt-3 text-sm text-muted-foreground">{t('unavailable')}</p>
            </section>
        );
    }

    const { temperature, totals, coverage } = evidence;
    const hasHistory = totals.contributorCount > 0 && Boolean(temperature.lastTouchAt);

    return (
        <section
            aria-label={t('title')}
            className={cn(
                isDialog
                    ? 'overflow-hidden bg-card'
                    : 'mt-6 overflow-hidden rounded-2xl border border-border bg-card',
                className,
            )}
        >
            <div
                className={cn(
                    'flex flex-wrap items-start justify-between gap-3 border-b border-border px-5 py-4',
                    isDialog && 'pr-14',
                )}
            >
                <div>
                    <h2 className="text-xs font-medium uppercase tracking-[0.12em] text-muted-foreground">
                        {t('title')}
                    </h2>
                    <p className="mt-1 text-sm text-foreground">{t('subtitle')}</p>
                </div>
                <WarmthPill temp={temperature} />
            </div>

            {!hasHistory ? (
                <div className="px-5 py-6">
                    <p className="text-sm text-muted-foreground">{t('noHistory')}</p>
                </div>
            ) : (
                <>
                    <dl className="grid gap-px bg-border sm:grid-cols-3">
                        <div className="bg-card px-5 py-4">
                            <dt className="text-xs text-muted-foreground">{t('lastTouch')}</dt>
                            <dd className="mt-1 text-sm font-medium text-foreground">
                                {formatDateTime(temperature.lastTouchAt ?? undefined, locale)}
                            </dd>
                        </div>
                        <div className="bg-card px-5 py-4">
                            <dt className="text-xs text-muted-foreground">{t('sourceCount')}</dt>
                            <dd className="mt-1 text-sm font-medium text-foreground">
                                {t('sourceCountValue', { count: totals.contributorCount })}
                            </dd>
                        </div>
                        <div className="bg-card px-5 py-4">
                            <dt className="text-xs text-muted-foreground">{t('decay')}</dt>
                            <dd className="mt-1 text-sm font-medium text-foreground">
                                {temperature.goesColdAt
                                    ? t('goesColdAt', {
                                        date: formatDateTime(temperature.goesColdAt, locale),
                                    })
                                    : t('noDecayDate')}
                            </dd>
                        </div>
                    </dl>

                    <div className="px-5 py-4">
                        <h3 className="text-xs font-medium text-muted-foreground">{t('sources')}</h3>
                        <ul className="mt-2 grid gap-2">
                            {evidence.contributors.map((contributor) => {
                                const Icon = SOURCE_ICONS[contributor.sourceType];
                                return (
                                    <li key={`${contributor.sourceType}-${contributor.sourceId}`}>
                                        <Link
                                            href={`${SOURCE_PATHS[contributor.sourceType]}/${contributor.sourceId}`}
                                            className="flex items-center gap-3 rounded-lg border border-border px-3 py-2.5 transition-colors hover:bg-muted/60"
                                        >
                                            <Icon className="size-4 shrink-0 text-muted-foreground" aria-hidden />
                                            <div className="min-w-0 flex-1">
                                                <p className="truncate text-sm font-medium text-foreground">
                                                    {contributorLabel(contributor, t)}
                                                </p>
                                                <p className="text-xs text-muted-foreground">
                                                    {formatDateTime(contributor.occurredAt, locale)}
                                                </p>
                                                {contributor.captureEvidence ? (
                                                    <ProviderCaptureEvidence
                                                        evidence={contributor.captureEvidence}
                                                        compact
                                                    />
                                                ) : null}
                                            </div>
                                            <span className="shrink-0 text-xs tabular-nums text-muted-foreground">
                                                {number.format(contributor.decayedContribution)}
                                            </span>
                                        </Link>
                                    </li>
                                );
                            })}
                        </ul>
                        {totals.omittedCount > 0 ? (
                            <p className="mt-2 text-xs text-muted-foreground">
                                {t('omitted', { count: totals.omittedCount })}
                            </p>
                        ) : null}
                    </div>
                </>
            )}

            <div className="border-t border-border bg-muted/30 px-5 py-3">
                <p className="flex items-start gap-2 text-xs text-muted-foreground">
                    <DocumentTextIcon className="mt-0.5 size-3.5 shrink-0" aria-hidden />
                    {t(ATTRIBUTION_KEYS[evidence.attributionRule])}
                </p>
                {coverage.limitedEvidence ? (
                    <p className="mt-1 flex items-start gap-2 text-xs text-muted-foreground">
                        <ExclamationTriangleIcon className="mt-0.5 size-3.5 shrink-0" aria-hidden />
                        {t('limited', {
                            minimum: coverage.minimumContributorsForConfidence,
                        })}
                    </p>
                ) : null}
                {coverage.callerPrivateNotesExcluded > 0 ? (
                    <p className="mt-1 flex items-start gap-2 text-xs text-muted-foreground">
                        <DocumentTextIcon className="mt-0.5 size-3.5 shrink-0" aria-hidden />
                        {t('privateNotesExcluded', {
                            count: coverage.callerPrivateNotesExcluded,
                        })}
                    </p>
                ) : null}
                <p className="mt-1 flex items-start gap-2 text-xs text-muted-foreground">
                    <ClockIcon className="mt-0.5 size-3.5 shrink-0" aria-hidden />
                    {t('asOf', {
                        date: formatDateTime(evidence.asOf, locale),
                        version: temperature.modelVersion ?? t('unknownModel'),
                    })}
                </p>
            </div>
        </section>
    );
}
