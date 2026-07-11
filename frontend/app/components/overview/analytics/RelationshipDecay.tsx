'use client';

import { useTranslations } from 'next-intl';

import { type WarmthDecayCounts } from '@/app/lib/types';
import { DECAY_BUCKETS, type DecayBucketKey } from '@/app/components/overview/analytics/relationshipMetrics';

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

    return (
        <div className="flex h-full flex-col">
            <div className="mb-5">
                <div className="text-3xl leading-none text-foreground tabular-nums">{total}</div>
                <p className="mt-1.5 text-sm text-muted-foreground">{t('summary')}</p>
            </div>
            <ul className="flex flex-col gap-4">
                {DECAY_BUCKETS.map((bucket) => {
                    const count = counts[bucket.key];
                    const width = Math.max(count > 0 ? 6 : 0, (count / max) * 100);
                    return (
                        <li key={bucket.key}>
                            <div className="mb-1 flex items-baseline justify-between gap-3 text-sm">
                                <span className="text-foreground">{t(`bucket_${bucket.key}`)}</span>
                                <span className="tabular-nums text-muted-foreground">{count}</span>
                            </div>
                            <div className="h-2 w-full overflow-hidden rounded-full bg-muted">
                                <div
                                    className="h-full rounded-full transition-[width] duration-500 ease-out motion-reduce:transition-none"
                                    style={{ width: `${width}%`, backgroundColor: BUCKET_COLOR[bucket.key] }}
                                />
                            </div>
                        </li>
                    );
                })}
            </ul>
        </div>
    );
}
