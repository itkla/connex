'use client';

import { useCallback, useEffect, useRef, useState } from 'react';

/** Playback cadence at 1× speed, in milliseconds per frame. */
const BASE_FRAME_MS = 850;

export type ReplayClock = {
    frameIndex: number;
    playing: boolean;
    play: () => void;
    pause: () => void;
    toggle: () => void;
    seek: (index: number) => void;
    restart: () => void;
};

/**
 * Drives replay playback: a paused-by-default clock that advances {@link ReplayClock.frameIndex}
 * through {@code frameCount} frames at {@code speed}×, pausing when it reaches the last frame. A
 * manual {@link ReplayClock.seek} pauses playback; {@link ReplayClock.play} restarts from the
 * beginning if already at the end. The requestAnimationFrame loop is torn down on unmount and
 * whenever playback stops.
 */
export function useReplayClock(frameCount: number, speed: number): ReplayClock {
    const [rawIndex, setRawIndex] = useState(0);
    const [playing, setPlaying] = useState(false);
    const rafRef = useRef(0);
    const frameRef = useRef(0);

    const maxIndex = Math.max(0, frameCount - 1);
    const frameIndex = Math.min(rawIndex, maxIndex);

    useEffect(() => {
        frameRef.current = frameIndex;
    }, [frameIndex]);

    useEffect(() => {
        if (!playing || frameCount <= 1) return;
        let last = 0;
        let acc = 0;
        const tick = (now: number) => {
            if (last === 0) last = now;
            acc += (now - last) * speed;
            last = now;
            if (acc >= BASE_FRAME_MS) {
                const advance = Math.floor(acc / BASE_FRAME_MS);
                acc -= advance * BASE_FRAME_MS;
                const next = Math.min(maxIndex, frameRef.current + advance);
                frameRef.current = next;
                setRawIndex(next);
                if (next >= maxIndex) {
                    setPlaying(false);
                    return;
                }
            }
            rafRef.current = requestAnimationFrame(tick);
        };
        rafRef.current = requestAnimationFrame(tick);
        return () => cancelAnimationFrame(rafRef.current);
    }, [playing, frameCount, speed, maxIndex]);

    const play = useCallback(() => {
        setRawIndex((i) => (i >= maxIndex ? 0 : i));
        setPlaying(true);
    }, [maxIndex]);
    const pause = useCallback(() => setPlaying(false), []);
    const toggle = useCallback(() => (playing ? setPlaying(false) : play()), [playing, play]);
    const seek = useCallback(
        (index: number) => {
            setPlaying(false);
            setRawIndex(Math.max(0, Math.min(maxIndex, index)));
        },
        [maxIndex],
    );
    const restart = useCallback(() => {
        setPlaying(false);
        setRawIndex(0);
    }, []);

    return { frameIndex, playing, play, pause, toggle, seek, restart };
}
