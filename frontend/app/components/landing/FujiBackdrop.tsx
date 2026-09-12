import { FujiMotion } from "./FujiMotion";
import styles from "./fuji.module.css";

/** An original Fuji silhouette with a broad skirt, an uneven crater rim, and snow-filled gullies. */
export function FujiBackdrop({ pauseLabel, resumeLabel }: { pauseLabel: string; resumeLabel: string }) {
    const silhouette = "M160 846 C342 816 481 767 613 700 C762 625 891 518 1039 393 L1094 352 L1107 350 L1112 346 L1121 348 L1134 344 L1149 347 L1161 345 L1173 348 L1184 342 L1194 344 L1202 350 C1289 418 1368 485 1462 547 C1607 643 1747 718 1940 777 L1940 930 H160 Z";
    return (
        <FujiMotion pauseLabel={pauseLabel} resumeLabel={resumeLabel}>
            <div className={styles.atmosphere} aria-hidden="true" />
            <svg className={styles.landscape} viewBox="0 0 1600 900" fill="none" preserveAspectRatio="xMidYMax slice" aria-hidden="true" focusable="false">
                <defs>
                    <linearGradient id="fuji-stone" x1="1180" y1="340" x2="1110" y2="880" gradientUnits="userSpaceOnUse">
                        <stop className={styles.stonePeak} />
                        <stop offset="0.65" className={styles.stoneSlope} />
                        <stop offset="1" className={styles.stoneFoot} />
                    </linearGradient>
                    <linearGradient id="fuji-snow" x1="1150" y1="338" x2="1090" y2="578" gradientUnits="userSpaceOnUse">
                        <stop className={styles.snowPeak} />
                        <stop offset="1" className={styles.snowFoot} />
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
                    <clipPath id="fuji-outline"><path d={silhouette} /></clipPath>
                </defs>
                <g data-fuji-depth="0.16">
                    <g className={`${styles.cloudDrift} ${styles.farClouds}`}>
                        {[-1600, 0, 1600].map((offset) => <g key={offset} transform={`translate(${offset} 0)`}>
                            <path d="M60 305 C167 298 193 310 266 305 C336 287 364 289 406 299 C454 302 477 291 526 298 C590 312 700 302 793 312 C626 330 468 321 341 325 C221 324 140 319 60 320 Z" fill="url(#fuji-cloud)" />
                            <path d="M955 208 C1035 199 1098 209 1132 200 C1174 184 1218 190 1253 198 C1310 197 1380 200 1466 208 C1353 220 1157 221 955 215 Z" fill="url(#fuji-cloud)" />
                        </g>)}
                    </g>
                </g>
                <g data-fuji-depth="0.08">
                    <path d={silhouette} fill="url(#fuji-stone)" />
                    <g clipPath="url(#fuji-outline)">
                        <path className={styles.ridgeShadow} d="M1184 336 C1221 409 1294 477 1351 570 C1427 686 1538 790 1678 906 H1134 C1146 732 1169 562 1184 336 Z" />
                        <path className={styles.ridgeLight} d="M1107 351 C1064 439 994 510 951 594 C899 684 825 778 717 878 L997 900 C1027 677 1095 487 1134 343 Z" />
                        <path d="M1039 393 L1094 352 L1107 350 L1112 346 L1121 348 L1134 344 L1149 347 L1161 345 L1173 348 L1184 342 L1194 344 L1202 350 C1230 377 1260 400 1293 423 C1267 411 1253 409 1240 397 C1237 414 1243 430 1253 443 C1231 427 1224 405 1210 392 C1204 418 1211 438 1218 462 C1199 443 1190 426 1186 412 C1184 443 1179 463 1164 484 C1172 454 1171 431 1167 414 C1155 431 1148 452 1137 461 C1141 440 1148 418 1141 407 C1127 426 1120 443 1101 457 C1110 436 1115 422 1117 407 C1098 425 1085 445 1062 455 C1081 435 1090 421 1092 409 C1074 421 1057 433 1037 439 L1065 408 L1021 427 Z" fill="url(#fuji-snow)" />
                        <g className={styles.snowGullies}>
                            <path d="M1092 412 C1043 471 1011 507 964 544 C1015 518 1067 470 1105 414 Z" />
                            <path d="M1149 429 C1118 493 1092 540 1057 585 C1099 550 1131 489 1159 436 Z" />
                            <path d="M1197 410 C1258 460 1291 500 1340 531 C1296 487 1257 443 1210 407 Z" />
                            <path d="M1231 411 C1297 449 1341 489 1404 510 C1336 473 1295 437 1242 408 Z" />
                        </g>
                        <path className={styles.ridgeDetail} d="M1099 488 C1045 566 1001 619 943 665 M1190 502 C1226 585 1284 667 1329 721 M1249 491 C1328 582 1421 645 1499 694" />
                    </g>
                </g>
                <g data-fuji-depth="0.28">
                    <g className={`${styles.cloudDrift} ${styles.nearClouds}`}>
                        {[-1600, 0, 1600].map((offset) => <g key={offset} transform={`translate(${offset} 0)`}>
                            <path d="M105 643 C247 616 358 641 446 626 C533 607 573 620 636 624 C718 630 753 611 822 621 C963 639 1050 634 1173 649 C1007 671 894 653 741 666 C603 680 465 661 355 669 C245 670 174 660 105 660 Z" fill="url(#fuji-cloud)" />
                            <path d="M983 506 C1067 490 1121 499 1166 491 C1207 479 1242 484 1275 491 C1325 499 1366 485 1419 493 C1479 501 1533 497 1598 508 C1494 527 1372 516 1301 524 C1189 532 1074 516 983 519 Z" fill="url(#fuji-cloud)" />
                        </g>)}
                    </g>
                </g>
                <path d="M0 716 C335 688 562 770 846 738 C1143 708 1358 753 1600 722 V900 H0 Z" fill="url(#fuji-haze)" />
            </svg>
            <div className={styles.copyVeil} aria-hidden="true" />
        </FujiMotion>
    );
}
