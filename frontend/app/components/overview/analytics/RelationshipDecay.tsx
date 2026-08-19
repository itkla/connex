'use client';

import Link from 'next/link';
import { useTranslations } from 'next-intl';

import { type WarmthDecayCounts } from '@/app/lib/types';
import { DECAY_BUCKETS, type DecayBucketKey } from '@/app/components/overview/analytics/relationshipMetrics';
import { radarFamilyHref } from '@/app/components/radar/radarLinks';
import { warmthHorizonContactsHref } from '@/app/components/records/warmthFilters';

const BUCKET_COLOR: Record<DecayBucketKey, string> = {
    soon: 'var(--warmth-cool)',
    mid: 'color-mix(in oklch, var(--warmth-cool) 55%, var(--warmth-cold))',
    later: 'var(--warmth-cold)',
};

/**
 * Restates the server's disjoint decay buckets as the cumulative horizons the contacts browser can
 * actually filter to, so each horizon's count is exactly the size of the list its link opens.
 *
 * @param counts - the server-computed per-bucket counts
 */
function cumulativeHorizons(
    counts: WarmthDecayCounts,
): { key: DecayBucketKey; maxDays: number; count: number }[] {
    return DECAY_BUCKETS.map((bucket, index) => ({
        key: bucket.key,
        maxDays: bucket.maxDays,
        count: DECAY_BUCKETS
            .slice(0, index + 1)
            .reduce((sum, within) => sum + counts[within.key], 0),
    }));
}

/**
 * Relationship-decay horizon bars read from the server-computed {@link WarmthDecayCounts}
 * (contacts predicted to go cold within each horizon: {@code soon}/{@code mid}/{@code later}).
 *
 * The bars report *cumulative* horizons — "within 30", "within 60", "within 90" — rather than the
 * disjoint 0–30/31–60/61–90 ranges the server counts in. That is what makes each bar a working
 * link: the contacts browser filters by `goesColdWithinDays`, an upper bound, so a disjoint bar
 * would show one count and land the user on a strictly larger list. Summing the server's buckets is
 * exact arithmetic on its own figures, so every number here is both the bar's value and the size of
 * the list it opens. The total keeps its own destination — Radar's cooling family — because Radar is
 * where a cooling relationship is worked, not merely listed.
 */
export default function RelationshipDecay({ decay }: { decay: WarmthDecayCounts }) {
    const t = useTranslations('AnalyticsDecay');

    const counts = decay;
    const total = DECAY_BUCKETS.reduce((sum, bucket) => sum + counts[bucket.key], 0);

    if (total === 0) {
        return (
            <div className="flex h-56 items-center justify-center text-center text-sm text-muted-foreground">
                {t('empty')}
            </div>
        );
    }

    const href = radarFamilyHref('relationship_decay');
    const horizons = cumulativeHorizons(counts);

    return (
        <div className="flex h-full flex-col">
            <div className="mb-5">
                <div className="text-3xl leading-none text-foreground tabular-nums">{total}</div>
                <p className="mt-1.5 text-sm text-muted-foreground">{t('summary')}</p>
                <Link
                    href={href}
                    className="mt-2 inline-block text-sm text-brand underline-offset-4 transition-colors hover:text-brand-hover hover:underline"
                >
                    {t('openCoolingInRadar')}
                </Link>
            </div>
            <ul className="flex flex-col gap-4">
                {horizons.map((horizon) => {
                    const label = t(`bucket_${horizon.key}`);
                    const width = Math.max(horizon.count > 0 ? 6 : 0, (horizon.count / total) * 100);
                    return (
                        <li key={horizon.key}>
                            <Link
                                href={warmthHorizonContactsHref(horizon.maxDays)}
                                aria-label={t('bucketDrillThrough', { horizon: label, count: horizon.count })}
                                className="group/horizon block rounded-md outline-none focus-visible:ring-3 focus-visible:ring-ring/50"
                            >
                                <div className="mb-1 flex items-baseline justify-between gap-3 text-sm">
                                    <span className="text-foreground group-hover/horizon:underline group-hover/horizon:underline-offset-4">
                                        {label}
                                    </span>
                                    <span className="tabular-nums text-muted-foreground">{horizon.count}</span>
                                </div>
                                <div className="h-2 w-full overflow-hidden rounded-full bg-muted">
                                    <div
                                        className="h-full rounded-full transition-[width] duration-500 ease-out group-hover/horizon:brightness-95 motion-reduce:transition-none"
                                        style={{ width: `${width}%`, backgroundColor: BUCKET_COLOR[horizon.key] }}
                                    />
                                </div>
                            </Link>
                        </li>
                    );
                })}
            </ul>
        </div>
    );
}
