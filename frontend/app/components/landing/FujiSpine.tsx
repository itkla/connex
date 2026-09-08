"use client";

import { useEffect, useRef, useState } from "react";
import { motion, useScroll, useSpring, useTransform } from "motion/react";
import type { MotionValue } from "motion/react";
import { springSmooth } from "@/app/lib/motion";

/**
 * The landing page's single continuous stroke. It begins as a plotted warmth
 * series climbing out of the bottom-left, dips where a relationship cools,
 * recovers, and then keeps going as Mount Fuji's flank.
 *
 * The mountain is drawn with Fuji's actual signature rather than a generic dome:
 * concave flanks that flare almost flat into the base and steepen toward the top,
 * and a narrow, subtly flattened summit. Every join is C1-continuous, so there is
 * no kink anywhere along the curve.
 *
 * Three things are driven by scroll progress:
 *
 * 1. The camera pans down, so the summit starts above the viewport and the
 *    visible curve reads as a rising line with no falloff. The peak arrives as
 *    the reader descends the page.
 * 2. Cloud banks drift across the flanks at differing rates, clipped to the
 *    mountain so they never smudge the open sky behind the copy. They are kept
 *    well below the summit: a clear peak above the cloud line is the whole point.
 * 3. The green line draws itself, and deliberately stops short of the summit.
 *    Its head pulses there rather than completing the climb.
 *
 * Reduced motion is pinned in CSS rather than in JavaScript: `motion-reduce:`
 * rules park the camera, hold the clouds, still the pulse, and force the stroke
 * fully drawn. A CSS rule beats both the presentation attribute and the inline
 * style that `motion` writes, so the static state holds even before hydration and
 * even if the script never runs. `useReducedMotion()` is deliberately not used
 * here because it reads `null` on a server-rendered first paint.
 */

/**
 * Warmth series into Fuji's flank, drawn in a 1440x1200 space of which only the
 * lower 900 is ever visible. The apex sits at (1428, 244), above the crop line
 * and hard against the right edge, so at rest the visible curve is a rising line
 * with no descent on either side.
 */
const SPINE =
    "M -320 1156" +
    " C 40 1140, 280 1104, 430 1050" +
    " C 470 1036, 508 1018, 546 1000" +
    " C 588 980, 630 986, 668 1010" +
    " C 706 1034, 748 1042, 796 1028" +
    " C 862 1008, 926 966, 986 906" +
    " C 1056 836, 1118 754, 1172 664" +
    " C 1224 578, 1272 480, 1310 392" +
    " C 1338 328, 1364 276, 1386 258" +
    " C 1398 248, 1412 244, 1428 244" +
    " C 1444 244, 1458 250, 1470 262" +
    " C 1492 284, 1518 336, 1546 400" +
    " C 1600 522, 1690 690, 1820 900";

/** Everything under the stroke. Runs past the viewport so the pan never reveals an edge. */
const MASS = `${SPINE} L 1980 1560 L -320 1560 Z`;

/** Distant foothills, washed out by aerial perspective. */
const FAR_RIDGE =
    "M -320 1104" +
    " C 40 1092, 300 1040, 470 996" +
    " C 580 968, 686 962, 790 988" +
    " C 930 1024, 1060 1074, 1230 1100" +
    " C 1390 1124, 1600 1130, 1980 1132" +
    " L 1980 1560 L -320 1560 Z";

/** Fractions along the stroke where a reading is marked. */
const MARKS = [0.12, 0.24, 0.4, 0.58] as const;

/** Cloud banks: position in path space, scale, and how far each drifts across the scroll. */
const CLOUDS = [
    { x: 1210, y: 508, scale: 1, drift: 240, opacity: "opacity-95 dark:opacity-25" },
    { x: 940, y: 660, scale: 1.45, drift: -340, opacity: "opacity-90 dark:opacity-20" },
    { x: 1220, y: 812, scale: 1.9, drift: 180, opacity: "opacity-70 dark:opacity-15" },
] as const;

type Point = { x: number; y: number };

function CloudBank({ cloud, progress }: { cloud: (typeof CLOUDS)[number]; progress: MotionValue<number> }) {
    const x = useTransform(progress, [0, 1], [0, cloud.drift]);
    return (
        <motion.g style={{ x }} className="motion-reduce:transform-none!">
            <g
                transform={`translate(${cloud.x} ${cloud.y}) scale(${cloud.scale})`}
                className={`[fill:var(--color-background)] dark:[fill:var(--color-foreground)] ${cloud.opacity}`}
                filter="url(#fuji-cloud-blur)"
            >
                <ellipse cx="0" cy="0" rx="150" ry="13" />
                <ellipse cx="-104" cy="6" rx="86" ry="9" />
                <ellipse cx="116" cy="4" rx="98" ry="10" />
                <ellipse cx="26" cy="-11" rx="76" ry="11" />
                <ellipse cx="-48" cy="-6" rx="58" ry="9" />
            </g>
        </motion.g>
    );
}

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
    const [head, setHead] = useState<Point | null>(null);
    const [stopAt, setStopAt] = useState(0.84);

    const { scrollYProgress } = useScroll();
    const eased = useSpring(scrollYProgress, springSmooth);

    const pathLength = useTransform(eased, [0, 0.9], [0.24, stopAt]);
    const panY = useTransform(eased, [0, 0.55], [0, 150]);
    const panX = useTransform(eased, [0, 0.55], [0, -260]);
    const headOpacity = useTransform(eased, [0.78, 0.92], [0, 1]);

    useEffect(() => {
        const path = measureRef.current;
        if (!path) return;
        const total = path.getTotalLength();

        let apexFraction = 0.9;
        let highest = Infinity;
        for (let i = 0; i <= 400; i++) {
            const fraction = i / 400;
            const { y } = path.getPointAtLength(total * fraction);
            if (y < highest) {
                highest = y;
                apexFraction = fraction;
            }
        }

        const stop = Math.max(apexFraction - 0.07, 0.2);
        setStopAt(stop);
        setHead(path.getPointAtLength(total * stop));
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
            <svg viewBox="0 0 1440 1200" fill="none" preserveAspectRatio="xMidYMax slice" className="size-full">
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
                        <stop offset="0.62" stopColor="white" stopOpacity="1" />
                        <stop offset="0.88" stopColor="white" stopOpacity="0.45" />
                        <stop offset="1" stopColor="white" stopOpacity="0" />
                    </linearGradient>

                    <mask id="fuji-haze-mask" maskUnits="userSpaceOnUse" x="-320" y="0" width="2080" height="1600">
                        <rect x="-320" y="0" width="2080" height="1600" fill="url(#fuji-haze)" />
                    </mask>

                    <filter id="fuji-bloom" x="-20%" y="-20%" width="140%" height="140%">
                        <feGaussianBlur stdDeviation="7" />
                    </filter>

                    <clipPath id="fuji-mass-clip">
                        <path d={MASS} />
                    </clipPath>

                    <filter id="fuji-cloud-blur" x="-40%" y="-140%" width="180%" height="380%">
                        <feGaussianBlur stdDeviation="8" />
                    </filter>
                </defs>

                <motion.g style={{ x: panX, y: panY }} className="motion-reduce:transform-none!">
                    <g mask="url(#fuji-haze-mask)">
                        <path d={FAR_RIDGE} fill="url(#fuji-far)" />
                        <path d={MASS} fill="url(#fuji-mass)" />
                    </g>
                </motion.g>

                <motion.g style={{ x: panX, y: panY }} className="motion-reduce:transform-none!">
                    <path
                        ref={measureRef}
                        d={SPINE}
                        className="stroke-foreground/25"
                        strokeWidth={1.5}
                        strokeLinejoin="round"
                        strokeLinecap="round"
                        vectorEffect="non-scaling-stroke"
                    />

                    <g clipPath="url(#fuji-mass-clip)">
                        {CLOUDS.map((cloud) => (
                            <CloudBank key={`${cloud.x}-${cloud.y}`} cloud={cloud} progress={eased} />
                        ))}
                    </g>

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

                    {head ? (
                        <motion.g style={{ opacity: headOpacity }} className="motion-reduce:opacity-100!">
                            <circle
                                cx={head.x}
                                cy={head.y}
                                r={16}
                                className="fill-brand opacity-30 animate-pulse motion-reduce:animate-none!"
                            />
                            <circle
                                cx={head.x}
                                cy={head.y}
                                r={6}
                                className="fill-brand stroke-background"
                                strokeWidth={2.5}
                                vectorEffect="non-scaling-stroke"
                            />
                        </motion.g>
                    ) : null}
                </motion.g>
            </svg>
        </div>
    );
}
