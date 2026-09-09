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
 * 1. The camera pans down and left in two planes: the distant foothills travel
 *    at roughly half the rate of Fuji itself, so the scene has depth rather than
 *    sliding as one flat sheet. The summit starts above the viewport, and the
 *    visible curve reads as a rising line with no falloff until the peak arrives.
 * 2. Cloud banks drift across the flanks at differing rates, clipped to the
 *    mountain so they never smudge the open sky behind the copy. They are kept
 *    well below the summit: a clear peak above the cloud line is the whole point.
 * 3. The green line draws itself and deliberately stops short of the summit. A
 *    pulsing head rides its growing tip, so the stroke never ends in a bare cap.
 *    The whole scene eases back over the closing sections so the line never
 *    competes with the FAQ controls or the final call to action. Under reduced
 *    motion the stroke is pinned to that same stop via `--spine-stop` and the
 *    head is hidden, because CSS cannot freeze the SVG geometry `motion` drives.
 *
 * Reduced motion is pinned in CSS rather than in JavaScript: `motion-reduce:`
 * rules park the camera, hold the clouds, still the pulse, and force the stroke
 * fully drawn. A CSS rule beats both the presentation attribute and the inline
 * style that `motion` writes, so the static state holds even before hydration and
 * even if the script never runs. `useReducedMotion()` is deliberately not used
 * here because it reads `null` on a server-rendered first paint.
 */

/**
 * Warmth series into Fuji's flank. This is the approved silhouette, shifted +90x
 * and +60y inside a 1440x1200 space of which only the lower 900 is ever visible,
 * so the apex clears the crop line and the right shoulder falls beyond the right
 * edge. At rest the visible curve is a rising line with no descent on either side.
 */
const SPINE =
    "M -26 1150" +
    " C 117 1140, 221 1108, 307 1056" +
    " C 364 1023, 406 986, 447 963" +
    " C 502 933, 551 945, 601 968" +
    " C 653 994, 710 997, 767 978" +
    " C 835 956, 887 926, 944 882" +
    " C 1079 786, 1235 666, 1373 536" +
    " C 1469 448, 1547 354, 1628 289" +
    " C 1659 263, 1690 250, 1724 250" +
    " C 1758 250, 1810 282, 1862 336" +
    " C 1976 456, 2132 617, 2418 812";

/** Everything under the stroke. Runs past the viewport so the pan never reveals an edge. */
const MASS = `${SPINE} L 2600 1700 L -400 1700 Z`;

/** Distant foothills, washed out by aerial perspective. */
const FAR_RIDGE =
    "M -78 1054" +
    " C 221 1041, 429 971, 611 916" +
    " C 728 881, 832 874, 944 906" +
    " C 1105 950, 1248 1012, 1443 1046" +
    " C 1625 1077, 1794 1085, 1950 1088" +
    " L 2600 1700 L -400 1700 Z";

/** Fractions along the stroke where a reading is marked. */
const MARKS = [0.12, 0.24, 0.4, 0.58] as const;

/** Cloud banks: position in path space, scale, and how far each drifts across the scroll. */
const CLOUDS = [
    { x: 1660, y: 486, scale: 1, drift: 210, flip: false, opacity: "opacity-95 dark:opacity-30" },
    { x: 1380, y: 640, scale: 1.35, drift: -300, flip: true, opacity: "opacity-90 dark:opacity-24" },
    { x: 1680, y: 806, scale: 1.75, drift: 150, flip: false, opacity: "opacity-75 dark:opacity-16" },
] as const;

type Point = { x: number; y: number };

function CloudBank({ cloud, progress }: { cloud: (typeof CLOUDS)[number]; progress: MotionValue<number> }) {
    const x = useTransform(progress, [0, 1], [0, cloud.drift]);
    return (
        <motion.g style={{ x }} className="motion-reduce:transform-none!">
            <g
                transform={`translate(${cloud.x} ${cloud.y}) scale(${cloud.flip ? -cloud.scale : cloud.scale} ${cloud.scale})`}
                className={`[fill:var(--color-background)] dark:[fill:var(--color-foreground)] ${cloud.opacity}`}
                filter="url(#fuji-cloud-blur)"
            >
                <ellipse cx="-96" cy="14" rx="58" ry="30" />
                <ellipse cx="-18" cy="-8" rx="76" ry="46" />
                <ellipse cx="70" cy="6" rx="60" ry="34" />
                <ellipse cx="132" cy="20" rx="44" ry="22" />
                <ellipse cx="6" cy="28" rx="150" ry="22" />
            </g>
        </motion.g>
    );
}

function SpineHead({
    head,
    progress,
}: {
    head: { stops: number[]; xs: number[]; ys: number[] };
    progress: MotionValue<number>;
}) {
    const cx = useTransform(progress, head.stops, head.xs);
    const cy = useTransform(progress, head.stops, head.ys);
    return (
        <g className="motion-reduce:hidden">
            <motion.circle
                r={16}
                style={{ cx, cy }}
                className="fill-brand opacity-30 animate-pulse motion-reduce:animate-none!"
            />
            <motion.circle
                r={6}
                style={{ cx, cy }}
                className="fill-brand stroke-background"
                strokeWidth={2.5}
                vectorEffect="non-scaling-stroke"
            />
        </g>
    );
}

function SpineMark({ point, at, progress }: { point: Point; at: number; progress: MotionValue<number> }) {
    const opacity = useTransform(progress, [Math.max(at - 0.05, 0), at], [0, 1]);
    const scale = useTransform(progress, [Math.max(at - 0.05, 0), at], [0.6, 1]);
    return (
        <motion.circle
            cx={point.x}
            cy={point.y}
            r={4.5}
            className="fill-background stroke-brand [transform-box:fill-box] [transform-origin:center] motion-reduce:opacity-100! motion-reduce:transform-none!"
            strokeWidth={2.5}
            vectorEffect="non-scaling-stroke"
            style={{ opacity, scale }}
        />
    );
}

export default function FujiSpine() {
    const measureRef = useRef<SVGPathElement>(null);
    const [marks, setMarks] = useState<Point[]>([]);
    const [headPath, setHeadPath] = useState<{ stops: number[]; xs: number[]; ys: number[] } | null>(null);
    const [stopAt, setStopAt] = useState(0.84);

    const { scrollYProgress } = useScroll();
    const eased = useSpring(scrollYProgress, springSmooth);

    const pathLength = useTransform(eased, [0, 0.9], [0.24, stopAt]);
    const panY = useTransform(eased, [0, 0.7], [0, 240]);
    const panX = useTransform(eased, [0, 0.7], [0, -560]);
    const farPanY = useTransform(eased, [0, 0.7], [0, 108]);
    const farPanX = useTransform(eased, [0, 0.7], [0, -250]);
    const cloudReveal = useTransform(eased, [0, 0.28], [0.5, 1]);
    const spineFade = useTransform(eased, [0.74, 0.96], [1, 0.6]);

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

        const SAMPLES = 24;
        const stops: number[] = [];
        const xs: number[] = [];
        const ys: number[] = [];
        for (let i = 0; i <= SAMPLES; i++) {
            const scrollAt = (i / SAMPLES) * 0.9;
            const drawn = 0.24 + (scrollAt / 0.9) * (stop - 0.24);
            const { x, y } = path.getPointAtLength(total * drawn);
            stops.push(scrollAt);
            xs.push(x);
            ys.push(y);
        }
        setHeadPath({ stops, xs, ys });
        setMarks(
            MARKS.map((fraction) => {
                const { x, y } = path.getPointAtLength(total * fraction);
                return { x, y };
            })
        );
    }, []);

    return (
        <motion.div
            style={{ opacity: spineFade }}
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

                    <linearGradient id="fuji-ridge" gradientUnits="userSpaceOnUse" x1="0" y1="220" x2="0" y2="1200">
                        <stop offset="0" className="[stop-color:var(--color-foreground)]" stopOpacity="0.38" />
                        <stop offset="0.45" className="[stop-color:var(--color-foreground)]" stopOpacity="0.24" />
                        <stop offset="1" className="[stop-color:var(--color-foreground)]" stopOpacity="0.05" />
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

                    <mask id="fuji-haze-mask" maskUnits="userSpaceOnUse" x="-400" y="0" width="3000" height="1740">
                        <rect x="-400" y="0" width="3000" height="1740" fill="url(#fuji-haze)" />
                    </mask>

                    <filter id="fuji-bloom" x="-8%" y="-8%" width="116%" height="116%">
                        <feGaussianBlur stdDeviation="7" />
                    </filter>

                    <clipPath id="fuji-mass-clip">
                        <path d={MASS} />
                    </clipPath>

                    <filter id="fuji-cloud-blur" x="-40%" y="-140%" width="180%" height="380%">
                        <feGaussianBlur stdDeviation="5" />
                    </filter>
                </defs>

                <g mask="url(#fuji-haze-mask)">
                    <motion.g style={{ x: farPanX, y: farPanY }} className="motion-reduce:transform-none!">
                        <path d={FAR_RIDGE} fill="url(#fuji-far)" />
                    </motion.g>
                    <motion.g style={{ x: panX, y: panY }} className="motion-reduce:transform-none!">
                        <path d={MASS} fill="url(#fuji-mass)" />
                    </motion.g>
                </g>

                <motion.g style={{ x: panX, y: panY }} className="motion-reduce:transform-none!">
                    <path
                        ref={measureRef}
                        d={SPINE}
                        stroke="url(#fuji-ridge)"
                        strokeWidth={1.5}
                        strokeLinejoin="round"
                        strokeLinecap="round"
                        vectorEffect="non-scaling-stroke"
                    />

                    <motion.g
                        clipPath="url(#fuji-mass-clip)"
                        style={{ opacity: cloudReveal }}
                        className="motion-reduce:opacity-100!"
                    >
                        {CLOUDS.map((cloud) => (
                            <CloudBank key={`${cloud.x}-${cloud.y}`} cloud={cloud} progress={eased} />
                        ))}
                    </motion.g>

                    <motion.path
                        d={SPINE}
                        style={{ pathLength, ["--spine-stop" as string]: stopAt }}
                        stroke="url(#fuji-line)"
                        strokeWidth={9}
                        strokeLinejoin="round"
                        strokeLinecap="round"
                        filter="url(#fuji-bloom)"
                        className="opacity-25 dark:opacity-55 motion-reduce:[stroke-dasharray:var(--spine-stop)_1]!"
                    />

                    <motion.path
                        d={SPINE}
                        style={{ pathLength, ["--spine-stop" as string]: stopAt }}
                        stroke="url(#fuji-line)"
                        strokeWidth={3.25}
                        strokeLinejoin="round"
                        strokeLinecap="round"
                        vectorEffect="non-scaling-stroke"
                        className="motion-reduce:[stroke-dasharray:var(--spine-stop)_1]!"
                    />

                    {marks.map((point, i) => (
                        <SpineMark key={MARKS[i]} point={point} at={MARKS[i]} progress={eased} />
                    ))}

                    {headPath ? <SpineHead head={headPath} progress={eased} /> : null}
                </motion.g>
            </svg>
        </motion.div>
    );
}
