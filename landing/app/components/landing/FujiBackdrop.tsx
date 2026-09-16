import { FujiMotion } from "./FujiMotion";
import styles from "./fuji.module.css";

type Puff = readonly [cx: number, r: number];

type CloudPlacement = { puffs: readonly Puff[]; x: number; y: number; scale: number };

const CLOUD_SHADE_BAND = 9;
const ASCENT_START = { x: 826.375, y: 565.25 };

/** Returns a deterministic pseudo-random sequence so the sky renders identically everywhere. */
function seededRandom(seed: number) {
    let state = seed;
    return () => {
        state = (state + 0x6d2b79f5) | 0;
        let mixed = Math.imul(state ^ (state >>> 15), 1 | state);
        mixed = (mixed + Math.imul(mixed ^ (mixed >>> 7), 61 | mixed)) ^ mixed;
        return ((mixed ^ (mixed >>> 14)) >>> 0) / 4294967296;
    };
}

/** Spreads twinkles across four offsets so neighbouring stars never pulse in unison. */
function twinklePhase(value: number) {
    if (value < 0.25) return styles.twinkleEarly;
    if (value < 0.5) return styles.twinkleMid;
    if (value < 0.75) return styles.twinkleLate;
    return styles.twinkleLast;
}

/** Picks a value in `[min, max)` from a seeded sequence. */
function between(random: () => number, min: number, max: number) {
    return min + random() * (max - min);
}

/** Shapes a cloud with two or three overlapping bumps of varied size and spacing. */
function cloudPuffs(random: () => number): Puff[] {
    const peak = between(random, 48, 70);
    const count = random() < 0.3 ? 2 : 3;
    const peakIndex = Math.floor(random() * count);
    const radii = Array.from({ length: count }, (_, index) => index === peakIndex ? peak : peak * between(random, 0.45, 0.82));
    const puffs: Puff[] = [];
    radii.forEach((r) => {
        const previous = puffs.at(-1);
        const cx = previous ? previous[0] + (previous[1] + r) * between(random, 0.6, 0.78) : r;
        puffs.push([Math.round(cx), Math.round(r)]);
    });
    return puffs;
}

/** Scatters a band of distinct clouds across one 1600-unit drift loop. */
function cloudBand(seed: number, count: number, band: { top: number; bottom: number; minScale: number; maxScale: number }): CloudPlacement[] {
    const random = seededRandom(seed);
    return Array.from({ length: count }, (_, index) => ({
        puffs: cloudPuffs(random),
        x: Math.round((index * 1600) / count + between(random, 0, 1600 / count - 320)),
        y: Math.round(between(random, band.top, band.bottom)),
        scale: between(random, band.minScale, band.maxScale),
    }));
}

const BACK_CLOUDS = cloudBand(31, 4, { top: 540, bottom: 590, minScale: 0.7, maxScale: 0.95 });
const FRONT_CLOUDS = cloudBand(83, 3, { top: 660, bottom: 770, minScale: 0.9, maxScale: 1.2 });

const starRandom = seededRandom(1707);
const STARS = Array.from({ length: 96 }, () => ({ x: Math.round(starRandom() * 1600), y: Math.round(24 + starRandom() * 520), r: 0.7 + starRandom() * 1.3, phase: twinklePhase(starRandom()) }))
    .filter(({ x, y }) => y < 300 + Math.abs(x - 1150) * 0.7);

/** A stylized cumulus of stacked puffs on a flat base, with a shaded underside band. */
function BubblyCloud({ puffs, x, y, scale }: { puffs: readonly Puff[]; x: number; y: number; scale: number }) {
    const centers = puffs.map(([cx]) => cx);
    const start = Math.min(...centers);
    const width = Math.max(...centers) - start;
    const baseHeight = Math.min(...puffs.map(([, r]) => r)) * 1.6;
    const inset = CLOUD_SHADE_BAND / 2;
    return (
        <g transform={`translate(${x} ${y}) scale(${scale})`}>
            <g className={styles.cloudShade}>
                <rect x={start} y={-baseHeight} width={width} height={baseHeight} rx={baseHeight / 2} />
                {puffs.map(([cx, r]) => <circle key={cx} cx={cx} cy={-r} r={r} />)}
            </g>
            <g className={styles.cloudBody}>
                <rect x={start} y={-baseHeight} width={width} height={baseHeight - CLOUD_SHADE_BAND} rx={(baseHeight - CLOUD_SHADE_BAND) / 2} />
                {puffs.map(([cx, r]) => <circle key={cx} cx={cx} cy={-r - inset} r={r - inset} />)}
            </g>
        </g>
    );
}

/** Repeats a cloud band at each loop offset so the drift wraps seamlessly. */
function CloudLoop({ clouds, className }: { clouds: readonly CloudPlacement[]; className: string }) {
    return (
        <g className={`${styles.cloudDrift} ${className}`}>
            {[-1600, 0, 1600].map((offset) => <g key={offset} transform={`translate(${offset} 0)`}>
                {clouds.map((cloud) => <BubblyCloud key={`${cloud.x}-${cloud.y}`} {...cloud} />)}
            </g>)}
        </g>
    );
}

/** A streak that crosses the night sky once per cycle, then waits out of sight. */
function ShootingStar({ x, y, className }: { x: number; y: number; className: string }) {
    return (
        <g transform={`translate(${x} ${y})`}>
            <g className={className}>
                <line x1="0" y1="0" x2="150" y2="-75" stroke="url(#fuji-meteor-tail)" strokeWidth="1.5" strokeLinecap="round" />
                <circle className={styles.starTone} r="1.8" />
            </g>
        </g>
    );
}

/** A fine Fuji skyline before a daytime sun or a starry night sky, with bubbly clouds drifting behind and in front of it. */
export function FujiBackdrop({ pauseLabel, resumeLabel }: { pauseLabel: string; resumeLabel: string }) {
    // Split the existing left ridge at its midpoint so the ascent follows the exact silhouette.
    const ascentCurve = "C895.75 513.5 965 455.5 1039 393";
    const leftSlope = `M160 846 C342 816 481 767 613 700 C687.5 662.5 757 617 ${ASCENT_START.x} ${ASCENT_START.y} ${ascentCurve}`;
    const skyline = `${leftSlope} L1094 352 L1107 350 L1112 346 L1121 348 L1134 344 L1149 347 L1161 345 L1173 348 L1184 342 L1194 344 L1202 350 C1289 418 1368 485 1462 547 C1607 643 1747 718 1940 777`;
    const silhouette = `${skyline} L1940 930 H160 Z`;
    return (
        <FujiMotion pauseLabel={pauseLabel} resumeLabel={resumeLabel}>
            <div className={styles.scenery}>
                <svg className={styles.landscape} viewBox="0 0 1600 900" fill="none" preserveAspectRatio="xMidYMax slice" aria-hidden="true" focusable="false">
                    <defs>
                        <path data-fuji-ascent-route d={`M${ASCENT_START.x} ${ASCENT_START.y} ${ascentCurve}`} />
                        <linearGradient id="fuji-ascent-fade">
                            <stop stopColor="white" stopOpacity="0" />
                            <stop offset="1" stopColor="white" />
                        </linearGradient>
                        <mask id="fuji-ascent-trail" maskUnits="userSpaceOnUse" x="0" y="0" width="1600" height="900">
                            <rect data-fuji-ascent-fade x="-220" width="220" height="900" fill="url(#fuji-ascent-fade)" transform={`translate(${ASCENT_START.x} 0)`} />
                        </mask>
                        <linearGradient id="fuji-ink" x1="1150" y1="342" x2="1150" y2="846" gradientUnits="userSpaceOnUse">
                            <stop className={styles.inkTone} stopOpacity="0.5" />
                            <stop offset="0.55" className={styles.inkTone} stopOpacity="0.2" />
                            <stop offset="1" className={styles.inkTone} stopOpacity="0" />
                        </linearGradient>
                        <linearGradient id="fuji-wash" x1="1150" y1="342" x2="1150" y2="880" gradientUnits="userSpaceOnUse">
                            <stop className={styles.mountainTint} stopOpacity="0.32" />
                            <stop offset="0.4" className={styles.mountainTint} stopOpacity="0.14" />
                            <stop offset="0.72" className={styles.mountainTint} stopOpacity="0.05" />
                            <stop offset="1" className={styles.mountainTint} stopOpacity="0" />
                        </linearGradient>
                        <radialGradient id="fuji-sun-halo" cx="1150" cy="340" r="560" gradientUnits="userSpaceOnUse">
                            <stop className={styles.sunGlow} stopOpacity="0.34" />
                            <stop offset="0.3" className={styles.sunGlow} stopOpacity="0.14" />
                            <stop offset="1" className={styles.sunGlow} stopOpacity="0" />
                        </radialGradient>
                        <radialGradient id="fuji-sun-disc" cx="0.5" cy="0.5" r="0.5">
                            <stop className={styles.sunCore} />
                            <stop offset="0.7" className={styles.sunCore} />
                            <stop offset="1" className={styles.sunEdge} />
                        </radialGradient>
                        <filter id="fuji-sun-bloom" x="-50%" y="-50%" width="200%" height="200%">
                            <feGaussianBlur stdDeviation="14" />
                        </filter>
                        <mask id="fuji-behind-ridge" maskUnits="userSpaceOnUse" x="-1600" y="-400" width="4800" height="1800">
                            <rect x="-1600" y="-400" width="4800" height="1800" fill="white" />
                            <g data-fuji-depth="0.16"><path d={silhouette} fill="black" /></g>
                        </mask>
                        <linearGradient id="fuji-meteor-tail" x1="0" y1="0" x2="150" y2="-75" gradientUnits="userSpaceOnUse">
                            <stop className={styles.starStop} />
                            <stop offset="1" className={styles.starStop} stopOpacity="0" />
                        </linearGradient>
                        <linearGradient id="fuji-haze" x1="0" y1="0" x2="0" y2="1">
                            <stop stopColor="black" stopOpacity="0" />
                            <stop offset="1" stopColor="black" />
                        </linearGradient>
                        <mask id="fuji-haze-fade" maskUnits="userSpaceOnUse" x="-1600" y="-400" width="4800" height="1800">
                            <rect x="-1600" y="-400" width="4800" height="1800" fill="white" />
                                </mask>
                    </defs>
                    <g mask="url(#fuji-haze-fade)">
                        <g data-fuji-depth="0.32">
                            <circle className={styles.sun} cx="1150" cy="340" r="560" fill="url(#fuji-sun-halo)" />
                        </g>
                        <g mask="url(#fuji-behind-ridge)">
                            <g data-fuji-depth="0.32">
                                <g className={styles.sun}>
                                    <circle className={styles.sunBloom} cx="1150" cy="340" r="132" filter="url(#fuji-sun-bloom)" />
                                    <circle cx="1150" cy="340" r="115" fill="url(#fuji-sun-disc)" />
                                </g>
                                <g className={styles.stars}>
                                    <g>{STARS.map(({ x, y, r, phase }) => <circle key={`${x}-${y}`} className={`${styles.star} ${phase} ${styles.starTone}`} cx={x} cy={y} r={r} />)}</g>
                                    <ShootingStar x={1500} y={80} className={styles.shootingStar} />
                                    <ShootingStar x={1040} y={60} className={`${styles.shootingStar} ${styles.shootingStarLate}`} />
                                </g>
                            </g>
                            <g data-fuji-depth="0.24">
                                <CloudLoop clouds={BACK_CLOUDS} className={styles.farClouds} />
                            </g>
                        </g>
                        <g data-fuji-depth="0.16">
                            <path d={silhouette} fill="url(#fuji-wash)" />
                            <path className={styles.outline} d={skyline} stroke="url(#fuji-ink)" vectorEffect="non-scaling-stroke" />
                            <path className={styles.ascentTrail} d={leftSlope} mask="url(#fuji-ascent-trail)" vectorEffect="non-scaling-stroke" />
                            <g data-fuji-ascent-marker transform={`translate(${ASCENT_START.x} ${ASCENT_START.y})`}>
                                <circle className={styles.ascentPulse} r="7" />
                                <circle className={styles.ascentDot} r="3.75" />
                            </g>
                        </g>
                        <g data-fuji-depth="0.06">
                            <CloudLoop clouds={FRONT_CLOUDS} className={styles.nearClouds} />
                        </g>
                    </g>
                </svg>
            </div>
        </FujiMotion>
    );
}
