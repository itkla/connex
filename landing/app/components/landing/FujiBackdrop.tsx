import { FujiMotion } from "./FujiMotion";
import styles from "./fuji.module.css";

/** A fine Fuji skyline, translucent slopes, and softly feathered clouds. */
export function FujiBackdrop({ pauseLabel, resumeLabel }: { pauseLabel: string; resumeLabel: string }) {
    const skyline = "M160 846 C342 816 481 767 613 700 C762 625 891 518 1039 393 L1094 352 L1107 350 L1112 346 L1121 348 L1134 344 L1149 347 L1161 345 L1173 348 L1184 342 L1194 344 L1202 350 C1289 418 1368 485 1462 547 C1607 643 1747 718 1940 777";
    return (
        <FujiMotion pauseLabel={pauseLabel} resumeLabel={resumeLabel}>
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
                    <radialGradient id="fuji-cloud" cx="0.5" cy="0.48" r="0.52">
                        <stop className={styles.cloudGlow} stopOpacity="0.95" />
                        <stop offset="0.55" className={styles.cloudGlow} stopOpacity="0.8" />
                        <stop offset="0.8" className={styles.cloudTone} stopOpacity="0.45" />
                        <stop offset="1" className={styles.cloudTone} stopOpacity="0" />
                    </radialGradient>
                    <filter id="fuji-cloud-soften" x="-5%" y="-25%" width="110%" height="150%">
                        <feGaussianBlur stdDeviation="2.5" />
                    </filter>
                    <linearGradient id="fuji-haze" x1="0" y1="0" x2="0" y2="1">
                        <stop className={styles.hazeTone} stopOpacity="0" />
                        <stop offset="1" className={styles.hazeTone} />
                    </linearGradient>
                </defs>
                <g data-fuji-depth="0.16">
                    <g className={`${styles.cloudDrift} ${styles.farClouds}`}>
                        {[-1600, 0, 1600].map((offset) => <g key={offset} transform={`translate(${offset} 0)`}>
                            <path d="M180 422 C290 418 317 397 367 402 C394 376 437 382 462 399 C509 387 545 404 580 404 C638 404 707 417 782 429 C675 450 558 442 487 454 C390 463 300 435 180 446 Z" fill="url(#fuji-cloud)" filter="url(#fuji-cloud-soften)" />
                            <path d="M762 254 C839 242 915 253 963 239 C1003 212 1053 216 1081 235 C1115 217 1153 231 1167 242 C1244 236 1328 248 1398 262 C1310 281 1210 269 1133 286 C1046 299 1003 274 929 282 C869 283 816 269 762 273 Z" fill="url(#fuji-cloud)" filter="url(#fuji-cloud-soften)" />
                        </g>)}
                    </g>
                </g>
                <g data-fuji-depth="0.08">
                    <path d={`${skyline} L1940 930 H160 Z`} fill="url(#fuji-wash)" />
                    <path className={styles.outline} d={skyline} stroke="url(#fuji-ink)" vectorEffect="non-scaling-stroke" />
                </g>
                <g data-fuji-depth="0.28">
                    <g className={`${styles.cloudDrift} ${styles.nearClouds}`}>
                        {[-1600, 0, 1600].map((offset) => <g key={offset} transform={`translate(${offset} 0)`}>
                            <path d="M280 660 C434 643 498 660 584 636 C626 602 690 615 718 634 C754 612 802 624 818 640 C884 625 922 643 976 651 C1031 651 1112 673 1201 679 C1100 707 1004 684 921 713 C832 737 753 698 687 711 C547 727 430 680 280 688 Z" fill="url(#fuji-cloud)" filter="url(#fuji-cloud-soften)" />
                            <path d="M965 548 C1050 538 1101 553 1157 529 C1191 502 1231 507 1258 525 C1293 508 1330 520 1350 536 C1422 523 1471 540 1504 543 C1572 541 1652 563 1720 568 C1624 593 1500 570 1438 598 C1385 615 1319 584 1260 596 C1182 602 1090 565 965 574 Z" fill="url(#fuji-cloud)" filter="url(#fuji-cloud-soften)" />
                        </g>)}
                    </g>
                </g>
                <path d="M0 716 C335 688 562 770 846 738 C1143 708 1358 753 1600 722 V900 H0 Z" fill="url(#fuji-haze)" />
            </svg>
            <div className={styles.copyVeil} aria-hidden="true" />
        </FujiMotion>
    );
}
