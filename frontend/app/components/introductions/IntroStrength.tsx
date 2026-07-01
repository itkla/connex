'use client';

import { useTranslations } from 'next-intl';

import { cn } from '@/lib/utils';

export type StrengthTier = 'strong' | 'good' | 'fair';

const BAR_HEIGHTS = ['h-1.5', 'h-2.5', 'h-3.5', 'h-4'] as const;

/**
 * Maps a suggestion's 0-100 rank score to a match tier and bar count. Thresholds are tuned to the
 * backend scorer, where a pair without a shared employer caps around 70, so "strong" must remain
 * reachable on mutual-connection and warmth strength alone.
 */
export function tierFor(score: number): { tier: StrengthTier; bars: number } {
    if (score >= 55) return { tier: 'strong', bars: 4 };
    if (score >= 30) return { tier: 'good', bars: 3 };
    return { tier: 'fair', bars: 2 };
}

/**
 * Signal-style strength indicator derived from a suggestion's 0-100 rank score. Four bars of rising
 * height fill to the match tier, making the backend's ranking legible without implying false numeric
 * precision. Renders the tier word inline when {@code showLabel} is set.
 */
export default function IntroStrength({
    score,
    showLabel = false,
    className,
}: {
    score: number;
    showLabel?: boolean;
    className?: string;
}) {
    const t = useTranslations('Introductions');
    const { tier, bars } = tierFor(score);
    const label = t(
        tier === 'strong' ? 'strengthStrong' : tier === 'good' ? 'strengthGood' : 'strengthFair',
    );

    return (
        <span className={cn('inline-flex items-center gap-2', className)}>
            <span
                className="flex items-end gap-0.5"
                role="img"
                aria-label={t('strengthAria', { level: label })}
            >
                {BAR_HEIGHTS.map((height, index) => (
                    <span
                        key={height}
                        aria-hidden
                        className={cn(
                            'w-1 rounded-full transition-colors',
                            height,
                            index < bars ? 'bg-brand' : 'bg-border',
                        )}
                    />
                ))}
            </span>
            {showLabel ? (
                <span className="text-xs font-medium text-muted-foreground">{label}</span>
            ) : null}
        </span>
    );
}
