'use client';

import { useEffect, useMemo, useState } from 'react';
import { useTranslations } from 'next-intl';
import { ArrowUturnLeftIcon } from '@heroicons/react/24/outline';

import RelationMap from '@/app/components/map/RelationMap';
import ReplayScrubber from '@/app/components/map/replay/ReplayScrubber';
import { augmentMasterGraph, toComputedFrames } from '@/app/components/map/graph/replay';
import { getMapReplay } from '@/app/lib/api';
import { toastError } from '@/app/lib/toast';
import { useReplayClock } from '@/app/hooks/useReplayClock';
import { cn } from '@/lib/utils';
import type { Graph } from '@/app/components/map/graph/types';
import type { ReplayFrame } from '@/app/lib/types';

const WEEK_OPTIONS = [13, 26, 52] as const;
const DAY_MS = 24 * 60 * 60 * 1000;

function isoDate(ms: number): string {
    return new Date(ms).toISOString().slice(0, 10);
}

/**
 * The replay experience: fetches the frame series for the chosen range, builds the master graph, and
 * drives the map through it with a scrubber. Renders explicit loading, empty, and single-frame states
 * so it stays legible on tenants with little history.
 */
export default function ReplayView({
    baseGraph,
    focusId,
    weeks,
    onExit,
    onWeeksChange,
}: {
    baseGraph: Graph;
    focusId?: string;
    weeks: number;
    onExit: () => void;
    onWeeksChange: (weeks: number) => void;
}) {
    const t = useTranslations('Replay');
    const [frames, setFrames] = useState<ReplayFrame[] | null>(null);
    const [errored, setErrored] = useState(false);
    const [speed, setSpeed] = useState(1);

    useEffect(() => {
        let cancelled = false;
        const now = Date.now();
        getMapReplay({ from: isoDate(now - weeks * 7 * DAY_MS), to: isoDate(now), granularity: 'weekly' })
            .then((data) => {
                if (!cancelled) setFrames(data.frames);
            })
            .catch(() => {
                if (cancelled) return;
                setErrored(true);
                toastError(t('error'));
            });
        return () => {
            cancelled = true;
        };
    }, [weeks, t]);

    const loading = frames === null && !errored;

    const master = useMemo(() => (frames ? augmentMasterGraph(baseGraph, frames) : null), [baseGraph, frames]);
    const computed = useMemo(() => (frames && master ? toComputedFrames(frames, master) : []), [frames, master]);
    const dates = useMemo(() => computed.map((f) => f.date), [computed]);
    const clock = useReplayClock(computed.length, speed);

    const hasMap = !!master && computed.length > 0;
    const canPlay = computed.length > 1;

    return (
        <div className="relative h-full w-full">
            {hasMap ? (
                <RelationMap
                    graph={master}
                    focusId={focusId}
                    replay={{ frames: computed, frameIndex: clock.frameIndex }}
                />
            ) : (
                <div className="flex h-full w-full items-center justify-center">
                    <p className="text-sm text-muted-foreground">{loading ? t('loading') : errored ? t('error') : t('empty')}</p>
                </div>
            )}

            <div className="pointer-events-none absolute left-3 top-3 z-10 flex flex-wrap items-center gap-2">
                <button
                    type="button"
                    onClick={onExit}
                    className="pointer-events-auto flex items-center gap-1.5 rounded-lg border border-border bg-card/90 px-2.5 py-1.5 text-xs font-medium text-foreground shadow-md backdrop-blur transition-transform duration-150 ease-out hover:bg-card active:scale-95"
                >
                    <ArrowUturnLeftIcon className="size-4" />
                    {t('exit')}
                </button>
                <div
                    role="group"
                    aria-label={t('range')}
                    className="pointer-events-auto inline-flex rounded-full bg-card/90 p-0.5 shadow-md ring-1 ring-border backdrop-blur"
                >
                    {WEEK_OPTIONS.map((option) => {
                        const active = option === weeks;
                        return (
                            <button
                                key={option}
                                type="button"
                                onClick={() => onWeeksChange(option)}
                                aria-pressed={active}
                                className={cn(
                                    'rounded-full px-3 py-1 text-xs font-medium transition-colors duration-150',
                                    active
                                        ? 'bg-background text-foreground shadow-sm ring-1 ring-border'
                                        : 'text-muted-foreground hover:text-foreground',
                                )}
                            >
                                {t(option === 13 ? 'range13w' : option === 26 ? 'range26w' : 'range52w')}
                            </button>
                        );
                    })}
                </div>
            </div>

            <div className="pointer-events-none absolute inset-x-0 bottom-4 z-10 flex justify-center px-4">
                {canPlay ? (
                    <ReplayScrubber
                        dates={dates}
                        frameIndex={clock.frameIndex}
                        playing={clock.playing}
                        speed={speed}
                        onToggle={clock.toggle}
                        onSeek={clock.seek}
                        onRestart={clock.restart}
                        onSpeedChange={setSpeed}
                    />
                ) : loading ? (
                    <div className="pointer-events-auto rounded-2xl border border-border bg-card/95 px-4 py-3 text-sm text-muted-foreground shadow-xl backdrop-blur">
                        {t('loading')}
                    </div>
                ) : !errored && frames ? (
                    <div className="pointer-events-auto rounded-2xl border border-border bg-card/95 px-4 py-3 text-sm text-muted-foreground shadow-xl backdrop-blur">
                        {computed.length <= 1 ? t('singleFrame') : t('empty')}
                    </div>
                ) : null}
            </div>
        </div>
    );
}
