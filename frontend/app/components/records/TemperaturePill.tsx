'use client';

import { useLocale, useTranslations } from 'next-intl';

import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import { cn } from '@/lib/utils';
import { formatRelativeTime, warmthDotClass, warmthSurfaceClasses } from '@/app/lib/utils';
import type { RelationshipTemperature } from '@/app/lib/types';

/**
 * Compact warmth indicator: a band-coloured dot and label on a tinted surface, with a tooltip
 * giving the numeric score and time since the last interaction. Renders an em dash when the
 * temperature has not loaded yet. Shared by the records tables and the dashboard cooling feed.
 */
export default function TemperaturePill({ temp }: { temp?: RelationshipTemperature | null }) {
    const t = useTranslations('Temperature');
    const locale = useLocale();

    if (!temp) return <span className="text-sm text-muted-foreground">—</span>;

    const lastTouch = temp.lastTouchAt ? formatRelativeTime(temp.lastTouchAt, locale) : t('never');

    return (
        <Tooltip>
            <TooltipTrigger asChild>
                <span
                    className={cn(
                        'inline-flex items-center gap-1.5 whitespace-nowrap rounded-full px-2 py-0.5 text-xs font-medium ring-1 ring-inset select-none',
                        warmthSurfaceClasses(temp.band),
                    )}
                >
                    <span className={cn('size-2 shrink-0 rounded-full', warmthDotClass(temp.band))} />
                    {t(temp.band)}
                </span>
            </TooltipTrigger>
            <TooltipContent>{t('tooltip', { score: temp.score, lastTouch })}</TooltipContent>
        </Tooltip>
    );
}
