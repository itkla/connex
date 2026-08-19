'use client';

import Link from 'next/link';
import { useTranslations } from 'next-intl';

import { type WarmthDecayCounts } from '@/app/lib/types';
import { DECAY_BUCKETS, type DecayBucketKey } from '@/app/components/overview/analytics/relationshipMetrics';
import { radarFamilyHref } from '@/app/components/radar/radarLinks';

const BUCKET_COLOR: Record<DecayBucketKey, string> = {
    soon: 'var(--warmth-cool)',
    mid: 'color-mix(in oklch, var(--warmth-cool) 55%, var(--warmth-cold))',
    later: 'var(--warmth-cold)',
};

/**
 * Relationship-decay horizon bars read from the server-computed {@link WarmthDecayCounts}
 * (contacts predicted to go cold within each horizon: {@code soon}/{@code mid}/{@code later}).
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

    const max = Math.max(...DECAY_BUCKETS.map((bucket) => counts[bucket.key]), 1);
    const href = radarFamilyHref('relationship_decay');

    return (
        <div className="flex h-full flex-col">
            <div className="mb-5">
                <div className="text-3xl leading-none text-foreground tabular-nums">{total}</div>
                <Link
                    href={href}
                    className="mt-1.5 inline-block text-sm text-muted-foreground underline-offset-4 transition-colors hover:text-foreground hover:underline"
                >
                    {t('summary')}
                </Link>
            </div>
            <ul className="flex flex-col gap-4">
                {DECAY_BUCKETS.map((bucket) => {
                    const count = counts[bucket.key];
                    const width = Math.max(count > 0 ? 6 : 0, (count / max) * 100);
                    return (
                        <li key={bucket.key}>
                            <Link
                                href={href}
                                aria-label={t('bucketDrillThrough', {
                                    bucket: t(`bucket_${bucket.key}`),
                                    count,
                                })}
                                className="group block rounded-md outline-none focus-visible:ring-3 focus-visible:ring-ring/50"
                            >
                                <div className="mb-1 flex items-baseline justify-between gap-3 text-sm">
                                    <span className="text-foreground group-hover:underline group-hover:underline-offset-4">
                                        {t(`bucket_${bucket.key}`)}
                                    </span>
                                    <span className="tabular-nums text-muted-foreground">{count}</span>
                                </div>
                                <div className="h-2 w-full overflow-hidden rounded-full bg-muted">
                                    <div
                                        className="h-full rounded-full transition-[width] duration-500 ease-out group-hover:brightness-95 motion-reduce:transition-none"
                                        style={{ width: `${width}%`, backgroundColor: BUCKET_COLOR[bucket.key] }}
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
