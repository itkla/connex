import { ArrowRightIcon, BuildingOffice2Icon, ChartBarIcon, CheckIcon, ClipboardDocumentListIcon, ClockIcon, CurrencyYenIcon, DocumentCheckIcon, DocumentTextIcon, UserIcon } from "@heroicons/react/24/outline";
import type { LandingTranslation } from "./sampleWorkspace";
import type { ReactNode } from "react";
import { TeamworkStory } from "./TeamworkStory";
import styles from "./teamwork.module.css";

function Pipeline({ t }: { t: LandingTranslation }) {
    return <div className={styles.pipeline}>
        {(["new", "proposal", "won"] as const).map((stage, index) => <div key={stage} className={styles.lane}>
            <p>{t(`teamStory.visual.${stage}`)}</p>
            <div className={`${styles.deal} ${index === 2 ? styles.selectedDeal : ""}`}>
                {index === 2 ? <CheckIcon /> : <BuildingOffice2Icon />}
                <span /><span />
            </div>
            {index === 0 && <div className={styles.dealGhost} />}
        </div>)}
        <div className={styles.pipelineRoute}><CurrencyYenIcon /><span /><ArrowRightIcon /></div>
    </div>;
}

function Quote({ t }: { t: LandingTranslation }) {
    return <div className={styles.quoteScene}>
        <div className={styles.quoteSource}><CurrencyYenIcon /><span>{t("teamDeals")}</span></div>
        <ArrowRightIcon className={styles.quoteArrow} />
        <div className={styles.document}>
            <DocumentTextIcon className={styles.documentIcon} />
            <p>{t("teamQuotes")}</p>
            <div className={styles.documentLines}><span /><span /><span /></div>
            <div className={styles.documentTotal} />
            <div className={styles.approval}><DocumentCheckIcon /><span>{t("teamStory.visual.review")}</span></div>
        </div>
    </div>;
}

function Progress({ t }: { t: LandingTranslation }) {
    return <div className={styles.progressScene}>
        <div className={styles.chart}>
            <div className={styles.artifactTitle}><ChartBarIcon /><span>{t("teamStory.visual.analytics")}</span></div>
            <div className={styles.chartBars}>{["44%", "65%", "52%", "79%", "68%"].map((height, index) => <span key={index} style={{ height }} />)}</div>
        </div>
        <div className={styles.report}>
            <DocumentTextIcon />
            <p>{t("teamStory.visual.reports")}</p>
            <div className={styles.documentLines}><span /><span /><span /></div>
        </div>
    </div>;
}

function Handover({ t }: { t: LandingTranslation }) {
    return <div className={styles.handoverScene}>
        <div className={styles.owners}>
            <span><UserIcon /></span><ArrowRightIcon /><span className={styles.newOwner}><UserIcon /></span>
        </div>
        <p className={styles.ownerLabel}>{t("teamStory.visual.owner")}</p>
        <div className={styles.history}>
            <div className={styles.artifactTitle}><BuildingOffice2Icon /><span>{t("teamStory.visual.customerRecord")}</span></div>
            <div className={styles.historyRow}><ClockIcon /><span>{t("recordActivity")}</span><CheckIcon /></div>
            <div className={styles.historyRow}><ClipboardDocumentListIcon /><span>{t("recordTasks")}</span><CheckIcon /></div>
            <div className={styles.historyRow}><UserIcon /><span>{t("teamStory.visual.ownership")}</span><CheckIcon /></div>
        </div>
    </div>;
}

/** Four conceptual artifacts illustrate a shared customer history without live or sample records. */
export function TeamworkPreview({ t, header }: { t: LandingTranslation; header: ReactNode }) {
    const stages = [{ id: "deals", Visual: Pipeline }, { id: "quotes", Visual: Quote }, { id: "progress", Visual: Progress }, { id: "handover", Visual: Handover }] as const;
    return <TeamworkStory header={header} caption={t("teamStory.visual.caption")} scrollLabel={t("teamStory.scroll")} steps={stages.map(({ id, Visual }) => ({
        id,
        content: <><h3 className={styles.stepHeading}>{t(`teamStory.${id}.heading`)}</h3><p className={styles.stepBody}>{t(`teamStory.${id}.body`)}</p></>,
        visual: <Visual t={t} />,
    }))} />;
}
