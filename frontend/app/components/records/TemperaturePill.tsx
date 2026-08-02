'use client';

import { useLocale, useTranslations } from 'next-intl';

import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import { cn } from '@/lib/utils';
import { useLiveNow } from '@/app/hooks/useNow';
import { formatRelativeTime, warmthDotClass, warmthSurfaceClasses } from '@/app/lib/utils';
import type { RelationshipTemperature } from '@/app/lib/types';

/**
 * Compact warmth indicator: a band-coloured dot and label on a tinted surface, with a tooltip
 * giving the numeric score and time since the last interaction. Renders an em dash when the
 * temperature has not loaded yet, and a neutral "no history" chip when the relationship has no
 * recorded interactions — a score without evidence would be a fabricated judgement. Shared by the
 * records tables and the dashboard cooling feed.
 */
export default function TemperaturePill({ temp }: { temp?: RelationshipTemperature | null }) {
    const t = useTranslations('Temperature');
    const locale = useLocale();
    const now = useLiveNow();

    if (!temp) return <span className="text-sm text-muted-foreground">—</span>;

    if (!temp.lastTouchAt) {
        return (
            <Tooltip>
                <TooltipTrigger asChild>
                    <span className="inline-flex items-center gap-1.5 whitespace-nowrap rounded-full bg-muted px-2 py-0.5 text-xs font-medium text-muted-foreground ring-1 ring-border ring-inset select-none">
                        <span className="size-2 shrink-0 rounded-full bg-muted-foreground/40" />
                        {t('noHistory')}
                    </span>
                </TooltipTrigger>
                <TooltipContent>{t('noHistoryTooltip')}</TooltipContent>
            </Tooltip>
        );
    }

    const lastTouch = formatRelativeTime(temp.lastTouchAt, locale, now);

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
            <TooltipContent>
                <div>{t('tooltip', { score: temp.score, lastTouch })}</div>
                {temp.daysUntilCold != null && temp.goesColdAt ? (
                    <div className="opacity-80">
                        {t('goesCold', { when: formatRelativeTime(temp.goesColdAt, locale, now) })}
                    </div>
                ) : null}
            </TooltipContent>
        </Tooltip>
    );
}
