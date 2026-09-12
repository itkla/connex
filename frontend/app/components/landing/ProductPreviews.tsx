import { ArrowRightIcon, ArrowUpRightIcon, BuildingOffice2Icon, ChartBarIcon, ChatBubbleLeftRightIcon, CheckCircleIcon, ChevronDownIcon, ClipboardDocumentListIcon, ClockIcon, CurrencyYenIcon, DocumentCheckIcon, DocumentTextIcon, PencilSquareIcon, UserGroupIcon, UserIcon } from "@heroicons/react/24/outline";
import { AskConnexConversation } from "./AskConnexConversation";
import { SampleEvidence } from "./SampleEvidence";
import { WorkflowSequence } from "./WorkflowSequence";
import type { LandingTranslation } from "./sampleWorkspace";
import styles from "./landing.module.css";

type DiagramIcon = typeof BuildingOffice2Icon;

function RecordSymbol({ icon: Icon, label, className = "" }: { icon: DiagramIcon; label: string; className?: string }) {
    return <div className={`${styles.recordSymbol} ${className}`}><span className={styles.symbolIcon}><Icon aria-hidden="true" /></span><span className="relative mx-auto mt-3 block w-fit bg-background px-1 text-sm font-medium sm:text-base">{label}</span></div>;
}

/** Landing-only interpretation of the auth diagram: a shared record and two connected groups. */
export function CustomerWorkspacePreview({ t }: { t: LandingTranslation }) {
    return (
        <figure className="mt-10 sm:mt-12" aria-label={t("customerVisualLabel")}>
            <div className={styles.heroScene}>
                <svg className={styles.diagramLines} viewBox="0 0 100 100" preserveAspectRatio="none" aria-hidden="true">
                    <path d="M50 50 C66 40 76 30 82 17 M50 50 C36 62 26 72 18 83" vectorEffect="non-scaling-stroke" />
                </svg>
                <div className={styles.heroCompany}>
                    <span className={styles.heroCompanyIcon}><BuildingOffice2Icon aria-hidden="true" className="size-7" /></span>
                    <div><p className="text-lg font-semibold sm:text-xl">{t("recordCompany")}</p><p className="mt-1 text-sm text-muted-foreground">{t("heroRecordContext")}</p></div>
                </div>
                <div className={styles.heroContact}>
                    <span className={styles.heroAvatar}><UserIcon aria-hidden="true" className="size-7" /></span>
                    <p className={styles.heroContactLabel}>{t("recordContacts")}</p>
                </div>
                <div className={styles.heroTeam}>
                    <span className={styles.heroAvatar}><UserGroupIcon aria-hidden="true" className="size-7" /></span>
                    <p className={styles.heroTeamLabel}>{t("heroYourTeam")}</p>
                </div>
            </div>
        </figure>
    );
}

export function ConnectedRecordPreview({ t }: { t: LandingTranslation }) {
    const records = [
        [BuildingOffice2Icon, "recordCompany"], [UserGroupIcon, "recordContacts"], [CurrencyYenIcon, "recordDeal"],
        [ChatBubbleLeftRightIcon, "recordActivity"], [PencilSquareIcon, "recordNote"], [ClipboardDocumentListIcon, "recordTasks"],
    ] as const;
    return <ul className="mt-8 flex flex-wrap gap-x-7 gap-y-4 text-sm font-medium sm:text-base">{records.map(([Icon, label]) => <li key={label} className="flex items-center gap-2"><Icon aria-hidden="true" className="size-5 text-brand-dark dark:text-brand" />{t(label)}</li>)}</ul>;
}

export function AskConnexPreview({ t }: { t: LandingTranslation }) {
    return (
        <figure aria-label={t("askExample")}>
            <AskConnexConversation key={t("askPrompt")} title={t("askBriefTitle")} prompt={t("askPrompt")} userLabel={t("askYou")} assistantLabel={t("askName")} exampleLabel={t("askExample")} thinkingLabel={t("askThinking")} replayLabel={t("askReplay")} skipLabel={t("askSkip")}>
                <p className={`${styles.askResponsePart} text-xl font-medium leading-relaxed`}>{t("askFinding")}</p>
                <div className={`${styles.askResponsePart} mt-5 flex items-start gap-3 text-base leading-relaxed`}><ArrowUpRightIcon aria-hidden="true" className="mt-1 size-5 shrink-0 text-brand-dark dark:text-brand" /><p>{t("askNextBody")}</p></div>
                <details className={`${styles.askResponsePart} group mt-5 border-t border-border`} data-ask-sources>
                    <summary className="flex min-h-11 cursor-pointer list-none items-center justify-between gap-3 py-3 text-sm text-muted-foreground [&::-webkit-details-marker]:hidden"><span className="flex items-center gap-2"><DocumentTextIcon aria-hidden="true" className="size-4" />{t("askSources")}</span><ChevronDownIcon aria-hidden="true" className="size-4 shrink-0 group-open:rotate-180" /></summary>
                    <p className="mb-3 text-sm text-muted-foreground">{t("sampleCaption")}</p>
                    <SampleEvidence source="review" t={t} />
                    <SampleEvidence source="pricing" t={t} />
                </details>
            </AskConnexConversation>
        </figure>
    );
}

/** Connection types remain readable through line style and labels, without a fictional cast. */
export function MapPreview({ t }: { t: LandingTranslation }) {
    return (
        <figure className="mt-10" aria-label={t("mapVisualLabel")}>
            <div className={styles.mapScene}>
                <svg className={styles.diagramLines} viewBox="0 0 1000 400" preserveAspectRatio="none" aria-hidden="true">
                    <path d="M250 60 C120 180 200 210 200 325 M750 60 C880 180 800 210 800 325" strokeDasharray="5 6" />
                    <path d="M500 180 C500 260 200 240 200 325 M500 180 V325 M500 180 C500 260 800 240 800 325" />
                </svg>
                <RecordSymbol icon={UserIcon} label={t("mapMember")} className={styles.mapMemberFirst} />
                <RecordSymbol icon={UserIcon} label={t("mapMember")} className={styles.mapMemberSecond} />
                <RecordSymbol icon={BuildingOffice2Icon} label={t("recordCompany")} className={styles.mapCompany} />
                {[styles.mapContactFirst, styles.mapContactSecond, styles.mapContactThird].map((position) => <RecordSymbol key={position} icon={UserIcon} label={t("mapContact")} className={position} />)}
            </div>
            <figcaption className="mt-5 flex flex-wrap justify-center gap-x-8 gap-y-3 text-sm text-muted-foreground">
                <span className="flex items-center gap-2"><span aria-hidden="true" className="w-7 border-t border-brand-dark dark:border-brand" />{t("mapEmployment")}</span>
                <span className="flex items-center gap-2"><span aria-hidden="true" className="w-7 border-t border-dashed border-brand-dark dark:border-brand" />{t("mapInteraction")}</span>
            </figcaption>
        </figure>
    );
}

export function WorkflowPreview({ t }: { t: LandingTranslation }) {
    const steps = [[CheckCircleIcon, "trigger"], [ClipboardDocumentListIcon, "task"], [ClockIcon, "activity"]] as const;
    return (
        <figure className="mt-10">
            <WorkflowSequence>
                <ol className={styles.workflowSteps}>
                    {steps.map(([Icon, step], index) => <li key={step} className={styles.workflowStep}>
                        <span className={styles.workflowIcon}><Icon aria-hidden="true" /></span>
                        <p className="mt-4 text-base font-medium sm:text-lg">{t(`workflow_${step}_title`)}</p>
                        {index < steps.length - 1 && <span className={styles.workflowLink} aria-hidden="true"><ArrowRightIcon /></span>}
                    </li>)}
                </ol>
            </WorkflowSequence>
            <figcaption className="mt-6 text-sm text-muted-foreground">{t("workflowRecipe")}</figcaption>
            <details className="group mt-3 max-w-2xl">
                <summary className="flex min-h-11 w-fit cursor-pointer list-none items-center gap-2 text-sm font-medium [&::-webkit-details-marker]:hidden">{t("workflowReviewLabel")}<ChevronDownIcon aria-hidden="true" className="size-4 group-open:rotate-180" /></summary>
                <p className="mt-2 text-sm leading-relaxed text-muted-foreground">{t("workflowReview")}</p>
            </details>
        </figure>
    );
}

export function TeamworkPreview({ t }: { t: LandingTranslation }) {
    const artifacts = [[CurrencyYenIcon, "teamDeals"], [DocumentCheckIcon, "teamQuotes"], [ChartBarIcon, "teamProgress"], [UserGroupIcon, "teamHandover"]] as const;
    return <ul className="mt-10 grid grid-cols-2 gap-x-8 gap-y-10 border-t border-border pt-10 sm:grid-cols-4">{artifacts.map(([Icon, label]) => <li key={label}><Icon aria-hidden="true" className="size-10 text-brand-dark sm:size-12 dark:text-brand" /><p className="mt-4 text-base font-medium sm:text-lg">{t(label)}</p></li>)}</ul>;
}
