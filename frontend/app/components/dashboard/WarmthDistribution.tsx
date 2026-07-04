import { useTranslations } from 'next-intl';

import { cn } from '@/lib/utils';
import { warmthDotClass } from '@/app/lib/utils';
import type { RelationshipTemperature, TemperatureBand } from '@/app/lib/types';

const BANDS: readonly TemperatureBand[] = ['hot', 'warm', 'cool', 'cold'];

/** Props for {@link WarmthDistribution}: the contact temperatures to bucket by band. */
export type WarmthDistributionProps = { temps: RelationshipTemperature[] };

/**
 * Dashboard widget: a compact segmented meter showing how contacts are distributed across the
 * warmth bands (hot, warm, cool, cold). A single proportional bar summarises the mix and a legend
 * lists each band's colour, localized label, and count. Purely informational.
 */
export default function WarmthDistribution({ temps }: WarmthDistributionProps) {
    const t = useTranslations('WarmthDistribution');
    const tBand = useTranslations('Temperature');

    const total = temps.length;
    const counts = BANDS.map((band) => ({
        band,
        count: temps.filter((temp) => temp.band === band).length,
    }));

    return (
        <div className="flex h-full flex-col overflow-hidden rounded-2xl border border-border bg-card">
            {total === 0 ? (
                <p className="flex flex-1 items-center justify-center px-4 py-10 text-center text-sm text-muted-foreground">
                    {t('empty')}
                </p>
            ) : (
                <>
                    <div className="px-4 pt-4 pb-3">
                        <div className="flex h-2.5 w-full overflow-hidden rounded-full bg-muted">
                            {counts.map(({ band, count }) =>
                                count > 0 ? (
                                    <div
                                        key={band}
                                        className={cn('h-full', warmthDotClass(band))}
                                        style={{ width: `${(count / total) * 100}%` }}
                                    />
                                ) : null,
                            )}
                        </div>
                    </div>
                    <ul className="flex-1 divide-y divide-border border-t border-border">
                        {counts.map(({ band, count }) => (
                            <li key={band} className="flex items-center gap-3 px-4 py-2.5">
                                <span className={cn('size-2 shrink-0 rounded-full', warmthDotClass(band))} />
                                <span className="min-w-0 flex-1 truncate text-sm text-foreground">
                                    {tBand(band)}
                                </span>
                                <span className="text-sm tabular-nums text-muted-foreground">{count}</span>
                            </li>
                        ))}
                    </ul>
                </>
            )}
        </div>
    );
}
