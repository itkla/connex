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
 * foothill ridge behind, a gradient mass for Fuji itself, a filled snow cap, and
 * a mask that dissolves every fill into the page background at the base. Only
 * the strokes stay crisp.
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

/** Warmth series: climbs, cools, recovers higher, then keeps going as the ridge. */
const SPINE =
    "M 10 884" +
    " C 56 868, 96 840, 126 802" +
    " C 152 770, 172 738, 200 710" +
    " C 220 692, 240 696, 252 718" +
    " C 262 738, 272 752, 290 758" +
    " C 314 764, 338 750, 358 726" +
    " C 384 696, 402 670, 424 650" +
    " C 520 632, 640 604, 742 566" +
    " C 892 506, 1030 412, 1136 296" +
    " C 1206 246, 1276 186, 1330 152" +
    " L 1358 142 L 1382 152 L 1408 144" +
    " C 1452 186, 1512 262, 1580 356" +
    " C 1680 496, 1790 590, 1900 648";

/** Everything under the stroke: the mountain mass, and the area under the series. */
const MASS = `${SPINE} L 1900 900 L 10 900 Z`;

/** Distant foothills, sitting behind Fuji and washed out by aerial perspective. */
const FAR_RIDGE =
    "M -60 800" +
    " C 160 792, 300 748, 420 706" +
    " C 500 678, 560 660, 640 672" +
    " C 740 688, 820 736, 940 768" +
    " C 1080 802, 1240 814, 1500 818" +
    " L 1500 900 L -60 900 Z";

/** Snow line, closed upward so the cap fills; clipped to the mountain mass. */
const SNOW_CAP =
    "M 1194 268 L 1214 292 L 1232 258 L 1250 286 L 1268 252" +
    " L 1288 280 L 1306 248 L 1326 274 L 1344 246 L 1364 270" +
    " L 1382 244 L 1402 272 L 1420 250 L 1440 282 L 1458 256" +
    " L 1478 288 L 1496 260 L 1518 292" +
    " L 1518 90 L 1194 90 Z";

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

                    <linearGradient id="fuji-snow" x1="0" y1="0" x2="0" y2="1">
                        <stop
                            offset="0"
                            className="[stop-color:var(--color-background)] dark:[stop-color:var(--color-foreground)]"
                            stopOpacity="0.92"
                        />
                        <stop
                            offset="1"
                            className="[stop-color:var(--color-background)] dark:[stop-color:var(--color-foreground)]"
                            stopOpacity="0.16"
                        />
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

                    <clipPath id="fuji-mass-clip">
                        <path d={MASS} />
                    </clipPath>

                    <filter id="fuji-bloom" x="-20%" y="-20%" width="140%" height="140%">
                        <feGaussianBlur stdDeviation="7" />
                    </filter>
                </defs>

                <g mask="url(#fuji-haze-mask)">
                    <path d={FAR_RIDGE} fill="url(#fuji-far)" />
                    <path d={MASS} fill="url(#fuji-mass)" />
                    <g clipPath="url(#fuji-mass-clip)">
                        <path d={SNOW_CAP} fill="url(#fuji-snow)" />
                    </g>
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
