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

/** Plotted warmth series: climbs, cools, then recovers higher than it started. */
const CLIMB =
    "M 8 900 L 34 848 L 56 862 L 88 800 L 112 816 L 148 748 " +
    "L 174 786 L 212 742 L 236 758 L 274 660";

/** The same stroke, continuing as the mountain. */
const RIDGE =
    " C 420 648, 606 620, 742 566" +
    " C 892 506, 1030 412, 1136 296" +
    " C 1206 246, 1276 186, 1330 152" +
    " L 1358 142 L 1382 152 L 1408 144" +
    " C 1452 186, 1512 262, 1580 356" +
    " C 1680 496, 1790 590, 1900 648";

const SPINE = CLIMB + RIDGE;

/** Snow line across the upper flanks, faded out toward the treeline. */
const SNOW =
    "M 1252 254 L 1266 274 L 1278 250 L 1292 268 L 1306 246 " +
    "L 1319 264 L 1332 242 L 1346 262 L 1359 240 L 1373 260 " +
    "L 1387 246 L 1402 272 L 1416 254 L 1432 278";

/** Fractions along the stroke where a reading is marked. */
const MARKS = [0.06, 0.13, 0.2, 0.27, 0.46, 0.72, 0.93] as const;

const MARK_CLASS = "fill-background stroke-brand motion-reduce:opacity-100!";

type Point = { x: number; y: number };

function SpineMark({ point, at, progress }: { point: Point; at: number; progress: MotionValue<number> }) {
    const opacity = useTransform(progress, [Math.max(at - 0.05, 0), at], [0, 1]);
    return (
        <motion.circle
            cx={point.x}
            cy={point.y}
            r={5}
            className={MARK_CLASS}
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
    const pathLength = useTransform(eased, [0, 0.92], [0.14, 1]);

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
            className="pointer-events-none fixed inset-0 z-0 overflow-hidden opacity-80 max-md:inset-y-auto max-md:bottom-0 max-md:h-[46vh]"
            aria-hidden="true"
        >
            <svg viewBox="0 0 1440 900" fill="none" preserveAspectRatio="xMidYMax slice" className="size-full">
                <defs>
                    <linearGradient id="fuji-snow-fade" x1="0" y1="0" x2="0" y2="1">
                        <stop offset="0.2" stopColor="white" stopOpacity="0.95" />
                        <stop offset="0.85" stopColor="white" stopOpacity="0" />
                    </linearGradient>
                    <mask id="fuji-snow-mask">
                        <rect width="1440" height="900" fill="url(#fuji-snow-fade)" />
                    </mask>
                </defs>

                <path
                    ref={measureRef}
                    d={SPINE}
                    className="stroke-foreground/25"
                    strokeWidth={1.6}
                    strokeLinejoin="round"
                    strokeLinecap="round"
                    vectorEffect="non-scaling-stroke"
                />

                <g mask="url(#fuji-snow-mask)">
                    <path
                        d={SNOW}
                        className="stroke-foreground/20"
                        strokeWidth={1.1}
                        strokeLinejoin="round"
                        vectorEffect="non-scaling-stroke"
                    />
                </g>

                <motion.path
                    d={SPINE}
                    className="stroke-brand motion-reduce:[stroke-dasharray:none]!"
                    strokeWidth={3.5}
                    strokeLinejoin="round"
                    strokeLinecap="round"
                    vectorEffect="non-scaling-stroke"
                    style={{ pathLength }}
                />

                {marks.map((point, i) => (
                    <SpineMark key={MARKS[i]} point={point} at={MARKS[i]} progress={eased} />
                ))}
            </svg>
        </div>
    );
}
