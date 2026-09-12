import { FujiMotion } from "./FujiMotion";
import styles from "./fuji.module.css";

/** Fuji reduced to its open skyline: broad skirts and a short, uneven crater rim. */
export function FujiBackdrop({ pauseLabel, resumeLabel }: { pauseLabel: string; resumeLabel: string }) {
    const skyline = "M160 846 C342 816 481 767 613 700 C762 625 891 518 1039 393 L1094 352 L1107 350 L1112 346 L1121 348 L1134 344 L1149 347 L1161 345 L1173 348 L1184 342 L1194 344 L1202 350 C1289 418 1368 485 1462 547 C1607 643 1747 718 1940 777";
    return (
        <FujiMotion pauseLabel={pauseLabel} resumeLabel={resumeLabel}>
            <div className={styles.atmosphere} aria-hidden="true" />
            <svg className={styles.landscape} viewBox="0 0 1600 900" fill="none" preserveAspectRatio="xMidYMax slice" aria-hidden="true" focusable="false">
                <defs>
                    <linearGradient id="fuji-ink" x1="1150" y1="342" x2="1150" y2="846" gradientUnits="userSpaceOnUse">
                        <stop className={styles.inkTone} stopOpacity="0.65" />
                        <stop offset="0.55" className={styles.inkTone} stopOpacity="0.3" />
                        <stop offset="1" className={styles.inkTone} stopOpacity="0" />
                    </linearGradient>
                    <linearGradient id="fuji-cloud" x1="0" y1="0" x2="1" y2="0">
                        <stop className={styles.cloudTone} stopOpacity="0" />
                        <stop offset="0.22" className={styles.cloudTone} stopOpacity="0.85" />
                        <stop offset="0.68" className={styles.cloudTone} />
                        <stop offset="1" className={styles.cloudTone} stopOpacity="0" />
                    </linearGradient>
                    <linearGradient id="fuji-haze" x1="0" y1="0" x2="0" y2="1">
                        <stop className={styles.hazeTone} stopOpacity="0" />
                        <stop offset="1" className={styles.hazeTone} />
                    </linearGradient>
                </defs>
                <g data-fuji-depth="0.16">
                    <g className={`${styles.cloudDrift} ${styles.farClouds}`}>
                        {[-1600, 0, 1600].map((offset) => <g key={offset} transform={`translate(${offset} 0)`}>
                            <path className={styles.cloudWisp} d="M60 312 C252 312 328 298 428 305 S643 320 793 312" stroke="url(#fuji-cloud)" vectorEffect="non-scaling-stroke" />
                            <path className={styles.cloudWisp} d="M955 208 C1076 216 1135 198 1220 201 S1381 213 1466 208" stroke="url(#fuji-cloud)" vectorEffect="non-scaling-stroke" />
                        </g>)}
                    </g>
                </g>
                <g data-fuji-depth="0.08">
                    <path className={styles.outline} d={skyline} stroke="url(#fuji-ink)" vectorEffect="non-scaling-stroke" />
                </g>
                <g data-fuji-depth="0.28">
                    <g className={`${styles.cloudDrift} ${styles.nearClouds}`}>
                        {[-1600, 0, 1600].map((offset) => <g key={offset} transform={`translate(${offset} 0)`}>
                            <path className={styles.cloudWisp} d="M105 649 C281 665 441 632 570 640 S935 663 1173 649" stroke="url(#fuji-cloud)" vectorEffect="non-scaling-stroke" />
                            <path className={styles.cloudWisp} d="M983 510 C1110 521 1155 494 1256 501 S1473 521 1598 508" stroke="url(#fuji-cloud)" vectorEffect="non-scaling-stroke" />
                        </g>)}
                    </g>
                </g>
                <path d="M0 716 C335 688 562 770 846 738 C1143 708 1358 753 1600 722 V900 H0 Z" fill="url(#fuji-haze)" />
            </svg>
            <div className={styles.copyVeil} aria-hidden="true" />
        </FujiMotion>
    );
}
