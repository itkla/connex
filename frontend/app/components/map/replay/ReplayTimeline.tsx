'use client';

import { useCallback, useMemo, useRef } from 'react';
import { motion } from 'motion/react';
import { frameAvgBand } from '@/app/components/map/graph/replay';
import { warmthDotClass } from '@/app/lib/utils';
import { cn } from '@/lib/utils';
import type { ReplayFrame } from '@/app/lib/types';

const EASE_OUT: [number, number, number, number] = [0.23, 1, 0.32, 1];

/**
 * The replay timeline: one bar per frame, coloured by the frame's average warmth so the whole strip
 * reads as the network's temperature over time. Bars fade in sequentially on mount; a playhead marks
 * the current frame and glides as you scrub. Acts as an accessible slider (pointer drag + arrow keys).
 */
export default function ReplayTimeline({
    frames,
    frameIndex,
    onSeek,
    reduce,
    ariaLabel,
    ariaValueText,
}: {
    frames: ReplayFrame[];
    frameIndex: number;
    onSeek: (index: number) => void;
    reduce: boolean;
    ariaLabel: string;
    ariaValueText: string;
}) {
    const bands = useMemo(() => frames.map(frameAvgBand), [frames]);
    const trackRef = useRef<HTMLDivElement>(null);
    const dragging = useRef(false);
    const last = Math.max(0, frames.length - 1);

    const seekFromPointer = useCallback(
        (clientX: number) => {
            const el = trackRef.current;
            if (!el || frames.length === 0) return;
            const rect = el.getBoundingClientRect();
            const ratio = Math.max(0, Math.min(1, (clientX - rect.left) / rect.width));
            onSeek(Math.round(ratio * last));
        },
        [frames.length, last, onSeek],
    );

    const onPointerDown = useCallback(
        (e: React.PointerEvent<HTMLDivElement>) => {
            dragging.current = true;
            e.currentTarget.setPointerCapture(e.pointerId);
            seekFromPointer(e.clientX);
        },
        [seekFromPointer],
    );
    const onPointerMove = useCallback(
        (e: React.PointerEvent<HTMLDivElement>) => {
            if (dragging.current) seekFromPointer(e.clientX);
        },
        [seekFromPointer],
    );
    const onPointerUp = useCallback((e: React.PointerEvent<HTMLDivElement>) => {
        dragging.current = false;
        e.currentTarget.releasePointerCapture(e.pointerId);
    }, []);

    const onKeyDown = useCallback(
        (e: React.KeyboardEvent<HTMLDivElement>) => {
            if (e.key === 'ArrowLeft') {
                e.preventDefault();
                onSeek(Math.max(0, frameIndex - 1));
            } else if (e.key === 'ArrowRight') {
                e.preventDefault();
                onSeek(Math.min(last, frameIndex + 1));
            } else if (e.key === 'Home') {
                e.preventDefault();
                onSeek(0);
            } else if (e.key === 'End') {
                e.preventDefault();
                onSeek(last);
            }
        },
        [frameIndex, last, onSeek],
    );

    return (
        <div
            ref={trackRef}
            role="slider"
            tabIndex={0}
            aria-label={ariaLabel}
            aria-valuemin={0}
            aria-valuemax={last}
            aria-valuenow={frameIndex}
            aria-valuetext={ariaValueText}
            onPointerDown={onPointerDown}
            onPointerMove={onPointerMove}
            onPointerUp={onPointerUp}
            onKeyDown={onKeyDown}
            className="relative flex h-8 min-w-[9rem] flex-1 cursor-pointer touch-none items-stretch rounded-md outline-none focus-visible:ring-2 focus-visible:ring-brand/40"
        >
            {bands.map((band, i) => (
                <span key={i} aria-hidden className="flex flex-1 items-stretch px-px py-1">
                    <motion.span
                        initial={reduce ? false : { opacity: 0, scaleY: 0.4 }}
                        animate={{ opacity: 1, scaleY: 1 }}
                        transition={reduce ? { duration: 0 } : { delay: Math.min(i * 0.012, 0.5), duration: 0.22, ease: EASE_OUT }}
                        className={cn('w-full origin-bottom rounded-[1px]', warmthDotClass(band))}
                    />
                </span>
            ))}
            <span
                aria-hidden
                className="pointer-events-none absolute inset-y-1 w-0.5 -translate-x-1/2 rounded-full bg-foreground shadow-sm transition-[left] duration-200 ease-out"
                style={{ left: `${((frameIndex + 0.5) / Math.max(1, frames.length)) * 100}%` }}
            />
        </div>
    );
}
