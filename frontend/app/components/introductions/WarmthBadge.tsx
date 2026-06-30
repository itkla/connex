'use client';

import { useTranslations } from 'next-intl';

import { cn } from '@/lib/utils';
import { warmthDotClass, warmthSurfaceClasses } from '@/app/lib/utils';
import type { TemperatureBand } from '@/app/lib/types';

/**
 * Compact warmth-band chip (dot + label on a tinted surface), reusing the shared warmth tokens.
 * Unlike {@code TemperaturePill} it needs only the band, since reverse-intro suggestions carry the
 * band rather than a full temperature reading.
 */
export default function WarmthBadge({ band }: { band?: TemperatureBand | null }) {
    const t = useTranslations('Temperature');
    if (!band) return null;
    return (
        <span
            className={cn(
                'inline-flex shrink-0 items-center gap-1.5 rounded-full px-2 py-0.5 text-xs font-medium ring-1 ring-inset select-none',
                warmthSurfaceClasses(band),
            )}
        >
            <span className={cn('size-2 shrink-0 rounded-full', warmthDotClass(band))} />
            {t(band)}
        </span>
    );
}
