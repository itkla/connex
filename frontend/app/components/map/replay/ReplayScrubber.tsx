'use client';

import { Slider } from '@base-ui/react';
import { motion, useReducedMotion } from 'motion/react';
import { PauseIcon, PlayIcon } from '@heroicons/react/24/solid';
import { ArrowPathIcon } from '@heroicons/react/24/outline';
import { useLocale, useTranslations } from 'next-intl';
import { cn } from '@/lib/utils';

const SPEEDS = [0.5, 1, 2] as const;

function formatAsOf(date: string, locale: string): string {
    const parsed = new Date(`${date}T00:00:00Z`);
    if (Number.isNaN(parsed.getTime())) return date;
    return new Intl.DateTimeFormat(locale, {
        year: 'numeric',
        month: 'short',
        day: 'numeric',
        timeZone: 'UTC',
    }).format(parsed);
}

/**
 * The replay transport bar: play/pause, restart, a draggable timeline, the current frame's date, and
 * a speed selector. Rendered as a floating panel over the bottom of the map. Playback defaults to
 * paused (managed by the caller); a manual scrub pauses playback.
 */
export default function ReplayScrubber({
    dates,
    frameIndex,
    playing,
    speed,
    onToggle,
    onSeek,
    onRestart,
    onSpeedChange,
}: {
    dates: string[];
    frameIndex: number;
    playing: boolean;
    speed: number;
    onToggle: () => void;
    onSeek: (index: number) => void;
    onRestart: () => void;
    onSpeedChange: (speed: number) => void;
}) {
    const t = useTranslations('Replay');
    const locale = useLocale();
    const reduce = useReducedMotion();
    const max = Math.max(0, dates.length - 1);
    const currentDate = dates[frameIndex] ? formatAsOf(dates[frameIndex], locale) : '';

    return (
        <div className="pointer-events-auto flex w-[min(680px,calc(100vw-2rem))] items-center gap-3 rounded-2xl border border-border bg-card/95 px-3 py-2.5 shadow-xl backdrop-blur">
            <button
                type="button"
                onClick={onToggle}
                aria-label={playing ? t('pause') : t('play')}
                className="flex size-9 shrink-0 items-center justify-center rounded-full bg-foreground text-background transition-transform duration-150 ease-out hover:opacity-90 active:scale-95"
            >
                {playing ? <PauseIcon className="size-4" /> : <PlayIcon className="size-4 translate-x-px" />}
            </button>
            <button
                type="button"
                onClick={onRestart}
                aria-label={t('restart')}
                className="flex size-8 shrink-0 items-center justify-center rounded-full text-muted-foreground transition-transform duration-150 ease-out hover:bg-muted hover:text-foreground active:scale-95"
            >
                <ArrowPathIcon className="size-4" />
            </button>

            <Slider.Root
                value={frameIndex}
                min={0}
                max={max}
                step={1}
                onValueChange={(value) => onSeek(Array.isArray(value) ? value[0] : value)}
                aria-label={t('title')}
                className="min-w-0 flex-1"
            >
                <Slider.Control className="flex h-5 w-full touch-none items-center">
                    <Slider.Track className="relative h-1 w-full rounded-full bg-muted">
                        <Slider.Indicator className="rounded-full bg-brand" />
                        <Slider.Thumb className="size-3.5 rounded-full border-2 border-brand bg-background shadow-sm outline-none ring-brand/30 focus-visible:ring-2" />
                    </Slider.Track>
                </Slider.Control>
            </Slider.Root>

            <span
                className="shrink-0 tabular-nums text-xs font-medium text-foreground"
                aria-live="polite"
            >
                {t('asOf', { date: currentDate })}
            </span>

            <div
                role="group"
                aria-label={t('speed')}
                className="hidden shrink-0 items-center rounded-full bg-muted p-0.5 ring-1 ring-border sm:inline-flex"
            >
                {SPEEDS.map((option) => {
                    const active = option === speed;
                    return (
                        <button
                            key={option}
                            type="button"
                            onClick={() => onSpeedChange(option)}
                            aria-pressed={active}
                            className={cn(
                                'relative rounded-full px-2.5 py-1 text-[11px] font-medium transition-colors duration-150',
                                active ? 'text-foreground' : 'text-muted-foreground hover:text-foreground',
                            )}
                        >
                            {active && (
                                <motion.span
                                    layoutId="replay-speed-thumb"
                                    className="absolute inset-0 rounded-full bg-background shadow-sm ring-1 ring-border"
                                    transition={reduce ? { duration: 0 } : { type: 'spring', stiffness: 420, damping: 34 }}
                                />
                            )}
                            <span className="relative z-10">{option}×</span>
                        </button>
                    );
                })}
            </div>
        </div>
    );
}
