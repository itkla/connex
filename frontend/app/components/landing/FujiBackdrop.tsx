import { FujiMotion } from "./FujiMotion";
import styles from "./fuji.module.css";

type Puff = readonly [cx: number, r: number];

const LONG_CLOUD: readonly Puff[] = [[60, 30], [120, 46], [200, 62], [290, 50], [370, 70], [460, 52], [530, 40], [585, 28]];
const SHORT_CLOUD: readonly Puff[] = [[50, 28], [105, 44], [180, 58], [255, 46], [320, 52], [385, 34], [420, 22]];
const CLOUD_SHADE_BAND = 9;

/** Returns a deterministic pseudo-random sequence so the star field renders identically everywhere. */
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

/** A fine Fuji skyline with a daytime sun or a starry night sky, and drifting bubbly clouds. */
export function FujiBackdrop({ pauseLabel, resumeLabel }: { pauseLabel: string; resumeLabel: string }) {
    const skyline = "M160 846 C342 816 481 767 613 700 C762 625 891 518 1039 393 L1094 352 L1107 350 L1112 346 L1121 348 L1134 344 L1149 347 L1161 345 L1173 348 L1184 342 L1194 344 L1202 350 C1289 418 1368 485 1462 547 C1607 643 1747 718 1940 777";
    const silhouette = `${skyline} L1940 930 H160 Z`;
    return (
        <FujiMotion pauseLabel={pauseLabel} resumeLabel={resumeLabel}>
            <div className={styles.scenery}>
                <svg className={styles.landscape} viewBox="0 0 1600 900" fill="none" preserveAspectRatio="xMidYMax slice" aria-hidden="true" focusable="false">
                    <defs>
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
                        <filter id="fuji-ridge-feather" x="-5%" y="-5%" width="110%" height="110%">
                            <feGaussianBlur stdDeviation="2" />
                        </filter>
                        <mask id="fuji-behind-ridge" maskUnits="userSpaceOnUse" x="0" y="0" width="1600" height="900">
                            <rect width="1600" height="900" fill="white" />
                            <path d={silhouette} fill="black" filter="url(#fuji-ridge-feather)" />
                        </mask>
                        <linearGradient id="fuji-meteor-tail" x1="0" y1="0" x2="150" y2="-75" gradientUnits="userSpaceOnUse">
                            <stop className={styles.starStop} />
                            <stop offset="1" className={styles.starStop} stopOpacity="0" />
                        </linearGradient>
                        <linearGradient id="fuji-haze" x1="0" y1="0" x2="0" y2="1">
                            <stop className={styles.hazeTone} stopOpacity="0" />
                            <stop offset="1" className={styles.hazeTone} />
                        </linearGradient>
                    </defs>
                    <g data-fuji-depth="0.08">
                        <g className={styles.sun}>
                            <circle cx="1150" cy="340" r="560" fill="url(#fuji-sun-halo)" />
                            <g mask="url(#fuji-behind-ridge)">
                                <circle className={styles.sunBloom} cx="1150" cy="340" r="132" filter="url(#fuji-sun-bloom)" />
                                <circle cx="1150" cy="340" r="115" fill="url(#fuji-sun-disc)" />
                            </g>
                        </g>
                        <g className={styles.stars}>
                            <g>{STARS.map(({ x, y, r, phase }) => <circle key={`${x}-${y}`} className={`${styles.star} ${phase} ${styles.starTone}`} cx={x} cy={y} r={r} />)}</g>
                            <ShootingStar x={1500} y={80} className={styles.shootingStar} />
                            <ShootingStar x={1040} y={60} className={`${styles.shootingStar} ${styles.shootingStarLate}`} />
                        </g>
                    </g>
                    <g data-fuji-depth="0.16">
                        <g className={`${styles.cloudDrift} ${styles.farClouds}`}>
                            {[-1600, 0, 1600].map((offset) => <g key={offset} transform={`translate(${offset} 0)`}>
                                <BubblyCloud puffs={SHORT_CLOUD} x={200} y={452} scale={0.75} />
                                <BubblyCloud puffs={LONG_CLOUD} x={780} y={286} scale={0.8} />
                            </g>)}
                        </g>
                    </g>
                    <g data-fuji-depth="0.08">
                        <path d={silhouette} fill="url(#fuji-wash)" />
                        <path className={styles.outline} d={skyline} stroke="url(#fuji-ink)" vectorEffect="non-scaling-stroke" />
                    </g>
                    <g data-fuji-depth="0.28">
                        <g className={`${styles.cloudDrift} ${styles.nearClouds}`}>
                            {[-1600, 0, 1600].map((offset) => <g key={offset} transform={`translate(${offset} 0)`}>
                                <BubblyCloud puffs={LONG_CLOUD} x={290} y={704} scale={1.1} />
                                <BubblyCloud puffs={SHORT_CLOUD} x={1040} y={590} scale={1} />
                            </g>)}
                        </g>
                    </g>
                    <path d="M0 716 C335 688 562 770 846 738 C1143 708 1358 753 1600 722 V900 H0 Z" fill="url(#fuji-haze)" />
                </svg>
            </div>
        </FujiMotion>
    );
}
