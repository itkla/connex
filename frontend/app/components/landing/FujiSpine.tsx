"use client";

import { useEffect, useRef, useState } from "react";
import { motion, useScroll, useSpring, useTransform } from "motion/react";
import type { MotionValue } from "motion/react";
import { springSmooth } from "@/app/lib/motion";

/**
 * The landing page's single continuous stroke. It begins as a plotted warmth
 * series climbing out of the bottom-left, dips where a relationship cools,
 * recovers higher, and then keeps going as Mount Fuji's ridge before summiting
 * half-bled off the right edge.
 *
 * The mountain is built as an atmospheric scene rather than an outline: a distant
 * foothill ridge behind, and one unbroken gradient mass for Fuji that runs from
 * the base straight through to the summit, dissolved into the page background at
 * the bottom by a mask. Only the strokes stay crisp.
 *
 * Scroll progress drives `pathLength`, so the green line draws itself over the
 * length of the page.
 *
 * Reduced motion is pinned in CSS rather than in JavaScript: `motion-reduce:`
 * rules force the stroke fully drawn and every reading visible. A CSS rule beats
 * both the presentation attribute and the inline style that `motion` writes, so
 * the static state holds even before hydration and even if the script never
 * runs. `useReducedMotion()` is deliberately not used here because it reads
 * `null` on a server-rendered first paint and would silently animate anyway.
 */

/**
 * Warmth series: climbs, cools, recovers higher, then keeps going as the ridge.
 *
 * Every join is C1-continuous and the apex carries a horizontal tangent, so the
 * mountain reads as one abstract curve: no crater notch, no point at the summit,
 * and a shallow swell rather than a spike where the series dips.
 */
const SPINE =
    "M -20 880" +
    " C 90 872, 170 848, 236 808" +
    " C 280 782, 312 754, 344 736" +
    " C 386 713, 424 722, 462 740" +
    " C 502 760, 546 762, 590 748" +
    " C 642 731, 682 708, 726 674" +
    " C 830 600, 950 508, 1056 408" +
    " C 1130 340, 1190 268, 1252 218" +
    " C 1276 198, 1300 188, 1326 188" +
    " C 1352 188, 1392 212, 1432 254" +
    " C 1520 346, 1640 470, 1860 620";

/** Everything under the stroke: the mountain mass, and the area under the series. */
const MASS = `${SPINE} L 1860 900 L -20 900 Z`;

/** Distant foothills, sitting behind Fuji and washed out by aerial perspective. */
const FAR_RIDGE =
    "M -60 806" +
    " C 170 796, 330 742, 470 700" +
    " C 560 673, 640 668, 726 692" +
    " C 850 726, 960 774, 1110 800" +
    " C 1250 824, 1380 830, 1500 832" +
    " L 1500 900 L -60 900 Z";

/** Fractions along the stroke where a reading is marked. */
const MARKS = [0.08, 0.17, 0.29, 0.52, 0.78] as const;

type Point = { x: number; y: number };

function SpineMark({ point, at, progress }: { point: Point; at: number; progress: MotionValue<number> }) {
    const opacity = useTransform(progress, [Math.max(at - 0.05, 0), at], [0, 1]);
    return (
        <motion.circle
            cx={point.x}
            cy={point.y}
            r={4.5}
            className="fill-background stroke-brand motion-reduce:opacity-100!"
            strokeWidth={2.5}
            vectorEffect="non-scaling-stroke"
            style={{ opacity }}
        />
    );
}

export default function FujiSpine() {
    const measureRef = useRef<SVGPathElement>(null);
    const [marks, setMarks] = useState<Point[]>([]);

    const { scrollYProgress } = useScroll();
    const eased = useSpring(scrollYProgress, springSmooth);
    const pathLength = useTransform(eased, [0, 0.92], [0.12, 1]);

    useEffect(() => {
        const path = measureRef.current;
        if (!path) return;
        const total = path.getTotalLength();
        setMarks(
            MARKS.map((fraction) => {
                const { x, y } = path.getPointAtLength(total * fraction);
                return { x, y };
            })
        );
    }, []);

    return (
        <div
            className="pointer-events-none fixed inset-0 z-0 overflow-hidden max-md:inset-y-auto max-md:bottom-0 max-md:h-[46vh]"
            aria-hidden="true"
        >
            <svg viewBox="0 0 1440 900" fill="none" preserveAspectRatio="xMidYMax slice" className="size-full">
                <defs>
                    <linearGradient id="fuji-mass" x1="0" y1="0" x2="0" y2="1">
                        <stop offset="0" className="[stop-color:var(--color-foreground)]" stopOpacity="0.13" />
                        <stop offset="0.55" className="[stop-color:var(--color-foreground)]" stopOpacity="0.07" />
                        <stop offset="1" className="[stop-color:var(--color-foreground)]" stopOpacity="0.02" />
                    </linearGradient>

                    <linearGradient id="fuji-far" x1="0" y1="0" x2="0" y2="1">
                        <stop offset="0" className="[stop-color:var(--color-foreground)]" stopOpacity="0.07" />
                        <stop offset="1" className="[stop-color:var(--color-foreground)]" stopOpacity="0.01" />
                    </linearGradient>

                    <linearGradient id="fuji-line" x1="0" y1="1" x2="1" y2="0">
                        <stop offset="0" className="[stop-color:var(--color-brand-dark)]" />
                        <stop offset="0.55" className="[stop-color:var(--color-brand)]" />
                        <stop offset="1" className="[stop-color:var(--color-brand)]" />
                    </linearGradient>

                    <linearGradient id="fuji-haze" x1="0" y1="0" x2="0" y2="1">
                        <stop offset="0" stopColor="white" stopOpacity="1" />
                        <stop offset="0.62" stopColor="white" stopOpacity="0.85" />
                        <stop offset="1" stopColor="white" stopOpacity="0" />
                    </linearGradient>

                    <mask id="fuji-haze-mask">
                        <rect width="1440" height="900" fill="url(#fuji-haze)" />
                    </mask>

                    <filter id="fuji-bloom" x="-20%" y="-20%" width="140%" height="140%">
                        <feGaussianBlur stdDeviation="7" />
                    </filter>
                </defs>

                <g mask="url(#fuji-haze-mask)">
                    <path d={FAR_RIDGE} fill="url(#fuji-far)" />
                    <path d={MASS} fill="url(#fuji-mass)" />
                </g>

                <path
                    ref={measureRef}
                    d={SPINE}
                    className="stroke-foreground/25"
                    strokeWidth={1.5}
                    strokeLinejoin="round"
                    strokeLinecap="round"
                    vectorEffect="non-scaling-stroke"
                />

                <motion.path
                    d={SPINE}
                    stroke="url(#fuji-line)"
                    strokeWidth={9}
                    strokeLinejoin="round"
                    strokeLinecap="round"
                    filter="url(#fuji-bloom)"
                    className="opacity-25 dark:opacity-55 motion-reduce:[stroke-dasharray:none]!"
                    style={{ pathLength }}
                />

                <motion.path
                    d={SPINE}
                    stroke="url(#fuji-line)"
                    strokeWidth={3.25}
                    strokeLinejoin="round"
                    strokeLinecap="round"
                    vectorEffect="non-scaling-stroke"
                    className="motion-reduce:[stroke-dasharray:none]!"
                    style={{ pathLength }}
                />

                {marks.map((point, i) => (
                    <SpineMark key={MARKS[i]} point={point} at={MARKS[i]} progress={eased} />
                ))}
            </svg>
        </div>
    );
}
