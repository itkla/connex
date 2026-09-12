import { ArrowPathIcon, ChatBubbleLeftRightIcon, ClockIcon } from "@heroicons/react/24/outline";
import type { LandingTranslation } from "./sampleWorkspace";
import styles from "./warmth.module.css";

/** Explains the inputs and bands without inventing a customer's warmth reading. */
export function WarmthPreview({ t }: { t: LandingTranslation }) {
    const inputs = [
        { key: "recency", Icon: ClockIcon },
        { key: "frequency", Icon: ArrowPathIcon },
        { key: "kind", Icon: ChatBubbleLeftRightIcon },
    ] as const;
    const bands = ["cold", "cool", "warm", "hot"] as const;

    return (
        <figure className={styles.diagram} aria-label={t("warmth.visualLabel")}>
            <ul className={styles.inputs}>
                {inputs.map(({ key, Icon }) => <li key={key}>
                    <span className={styles.inputIcon}><Icon aria-hidden="true" /></span>
                    <span>{t(`warmth.inputs.${key}`)}</span>
                </li>)}
            </ul>
            <svg className={styles.connections} viewBox="0 0 600 104" preserveAspectRatio="none" aria-hidden="true">
                <path d="M100 0 V24 Q100 48 124 48 H276 Q300 48 300 72 V100 M500 0 V24 Q500 48 476 48 H324 Q300 48 300 72 M300 0 V100" vectorEffect="non-scaling-stroke" />
                <path d="m294 94 6 6 6-6" vectorEffect="non-scaling-stroke" />
            </svg>
            <p className={styles.scaleLabel}>{t("warmth.scale")}</p>
            <ol className={styles.bands}>
                {bands.map((band) => <li key={band} data-warmth-band={band}>
                    <span className={styles.bandColor} aria-hidden="true" />
                    <span>{t(`warmth.bands.${band}`)}</span>
                </li>)}
            </ol>
            <figcaption className={styles.caption}>{t("warmth.noHistory")}</figcaption>
        </figure>
    );
}
