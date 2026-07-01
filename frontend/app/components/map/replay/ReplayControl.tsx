'use client';

import { motion, useReducedMotion } from 'motion/react';
import { PauseIcon, PlayIcon } from '@heroicons/react/24/solid';
import { ArrowPathIcon, ChevronDoubleLeftIcon, ClockIcon } from '@heroicons/react/24/outline';
import { useLocale, useTranslations } from 'next-intl';

import ReplayTimeline from '@/app/components/map/replay/ReplayTimeline';
import { cn } from '@/lib/utils';
import type { ReplayFrame } from '@/app/lib/types';

const WEEK_OPTIONS = [13, 26, 52] as const;

export type ReplayPhase = 'idle' | 'loading' | 'active';

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
 * The single time-travel control, anchored bottom-left over the map. It loads historical data only on
 * intent: an idle "Time travel" pill shows a spinner while fetching, then morph-expands into the
 * playback bar — play/pause, the timeline of avg-warmth bars, the current date, a range picker, and a
 * collapse (chevron) that returns to the live map.
 */
export default function ReplayControl({
    phase,
    frames,
    frameIndex,
    playing,
    weeks,
    onEnter,
    onExit,
    onToggle,
    onSeek,
    onWeeksChange,
}: {
    phase: ReplayPhase;
    frames: ReplayFrame[];
    frameIndex: number;
    playing: boolean;
    weeks: number;
    onEnter: () => void;
    onExit: () => void;
    onToggle: () => void;
    onSeek: (index: number) => void;
    onWeeksChange: (weeks: number) => void;
}) {
    const t = useTranslations('Replay');
    const locale = useLocale();
    const reduce = useReducedMotion();
    const active = phase === 'active';
    const loading = phase === 'loading';
    const currentDate = active && frames[frameIndex] ? formatAsOf(frames[frameIndex].asOf, locale) : '';

    return (
        <motion.div
            layout
            transition={reduce ? { duration: 0 } : { type: 'spring', stiffness: 380, damping: 36 }}
            className="pointer-events-auto flex items-center gap-2 rounded-full border border-border bg-card/95 p-1 shadow-xl backdrop-blur"
        >
            {!active ? (
                <button
                    type="button"
                    onClick={onEnter}
                    disabled={loading}
                    className="flex h-8 items-center gap-1.5 rounded-full px-3 text-xs font-medium text-foreground transition-transform duration-150 ease-out hover:bg-muted active:scale-95 disabled:opacity-80"
                >
                    {loading ? <ArrowPathIcon className="size-4 animate-spin" /> : <ClockIcon className="size-4" />}
                    {loading ? t('building') : t('enter')}
                </button>
            ) : (
                <>
                    <button
                        type="button"
                        onClick={onToggle}
                        aria-label={playing ? t('pause') : t('play')}
                        className="flex size-8 shrink-0 items-center justify-center rounded-full bg-foreground text-background transition-transform duration-150 ease-out hover:opacity-90 active:scale-95"
                    >
                        {playing ? <PauseIcon className="size-4" /> : <PlayIcon className="size-4 translate-x-px" />}
                    </button>

                    <ReplayTimeline
                        frames={frames}
                        frameIndex={frameIndex}
                        onSeek={onSeek}
                        reduce={!!reduce}
                        ariaLabel={t('title')}
                        ariaValueText={currentDate}
                    />

                    <span className="w-24 shrink-0 whitespace-nowrap text-center tabular-nums text-xs font-medium text-foreground">
                        {currentDate}
                    </span>

                    <div
                        role="group"
                        aria-label={t('range')}
                        className="hidden shrink-0 items-center rounded-full bg-muted p-0.5 ring-1 ring-border sm:inline-flex"
                    >
                        {WEEK_OPTIONS.map((option) => {
                            const on = option === weeks;
                            return (
                                <button
                                    key={option}
                                    type="button"
                                    onClick={() => onWeeksChange(option)}
                                    aria-pressed={on}
                                    className={cn(
                                        'relative rounded-full px-2 py-0.5 text-[11px] font-medium tabular-nums transition-colors duration-150',
                                        on ? 'text-foreground' : 'text-muted-foreground hover:text-foreground',
                                    )}
                                >
                                    {on && (
                                        <motion.span
                                            layoutId="replay-range-thumb"
                                            className="absolute inset-0 rounded-full bg-background shadow-sm ring-1 ring-border"
                                            transition={reduce ? { duration: 0 } : { type: 'spring', stiffness: 420, damping: 34 }}
                                        />
                                    )}
                                    <span className="relative z-10">{option}w</span>
                                </button>
                            );
                        })}
                    </div>

                    <button
                        type="button"
                        onClick={onExit}
                        aria-label={t('exit')}
                        className="flex size-8 shrink-0 items-center justify-center rounded-full text-muted-foreground transition-transform duration-150 ease-out hover:bg-muted hover:text-foreground active:scale-95"
                    >
                        <ChevronDoubleLeftIcon className="size-4" />
                    </button>
                </>
            )}
        </motion.div>
    );
}
