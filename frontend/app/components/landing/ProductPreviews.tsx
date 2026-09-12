import { ArrowUpRightIcon, BuildingOffice2Icon, ChartBarIcon, ChatBubbleLeftRightIcon, CheckCircleIcon, ChevronDownIcon, ClipboardDocumentListIcon, ClockIcon, CurrencyYenIcon, DocumentCheckIcon, DocumentTextIcon, FunnelIcon, PencilSquareIcon, UserGroupIcon, UserIcon } from "@heroicons/react/24/outline";
import { AskConnexConversation } from "./AskConnexConversation";
import { ConnectedRecordTabs } from "./ConnectedRecordTabs";
import { SampleEvidence } from "./SampleEvidence";
import { WorkflowSequence } from "./WorkflowSequence";
import type { LandingTranslation } from "./sampleWorkspace";
import styles from "./landing.module.css";

export function ConnectedRecordPreview({ t }: { t: LandingTranslation }) {
    const icons = { company: BuildingOffice2Icon, contacts: UserGroupIcon, deals: CurrencyYenIcon, activities: ChatBubbleLeftRightIcon, notes: PencilSquareIcon, tasks: ClipboardDocumentListIcon };
    const records = [
        { id: "company", related: ["contacts", "deals"] },
        { id: "contacts", related: ["company", "activities"] },
        { id: "deals", related: ["company", "contacts"] },
        { id: "activities", related: ["contacts", "deals"] },
        { id: "notes", related: ["contacts", "deals"] },
        { id: "tasks", related: ["contacts", "deals"] },
    ] as const;
    const items = records.map(({ id, related }) => {
        const Icon = icons[id];
        return {
            id,
            label: t(`connectedRecords.${id}.label`),
            icon: <Icon aria-hidden="true" className="size-5" />,
            content: <div className={styles.recordDetail}>
                <div className="max-w-lg">
                    <h3 className="text-2xl font-semibold leading-tight tracking-tight sm:text-3xl">{t(`connectedRecords.${id}.heading`)}</h3>
                    <p className="mt-4 text-base leading-relaxed text-muted-foreground sm:text-lg">{t(`connectedRecords.${id}.body`)}</p>
                </div>
                <div className={styles.recordConnections} aria-hidden="true">
                    <div className={styles.recordPrimary}>
                        <span className={styles.recordPrimaryIcon}><Icon /></span>
                        <span>{t(`connectedRecords.${id}.label`)}</span>
                    </div>
                    <svg className={styles.recordLines} viewBox="0 0 100 100" preserveAspectRatio="none">
                        <path d="M0 50 H30 Q50 50 50 30 V25 Q50 20 60 20 H100 M30 50 Q50 50 50 70 V75 Q50 80 60 80 H100" vectorEffect="non-scaling-stroke" />
                    </svg>
                    <div className={styles.recordRelated}>
                        {related.map((relatedId) => {
                            const RelatedIcon = icons[relatedId];
                            return <div key={relatedId}><span className={styles.recordRelatedIcon}><RelatedIcon /></span><span>{t(`connectedRecords.${relatedId}.label`)}</span></div>;
                        })}
                    </div>
                </div>
            </div>,
        };
    });
    return <ConnectedRecordTabs label={t("connectedRecords.choose")} initialValue="company" items={items} />;
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

/** Two sparse branches preserve the real Map's workspace-to-contact hierarchy. */
export function MapPreview({ t }: { t: LandingTranslation }) {
    const nodes = [
        { kind: "member", icon: UserIcon, appearance: styles.mapMember, positions: [styles.mapMemberFirst, styles.mapMemberSecond] },
        { kind: "company", icon: BuildingOffice2Icon, appearance: styles.mapCompany, positions: [styles.mapCompanyFirst, styles.mapCompanySecond] },
        { kind: "contact", icon: UserIcon, appearance: styles.mapContact, positions: [styles.mapContactFirst, styles.mapContactSecond, styles.mapContactThird, styles.mapContactFourth] },
    ];
    const legend = [[UserIcon, "mapMember", styles.mapMemberKey], [BuildingOffice2Icon, "mapCompanies", styles.mapCompanyKey], [UserIcon, "mapContact", styles.mapContactKey]] as const;
    return (
        <figure className="mt-10" aria-label={t("mapVisualLabel")}>
            <div className={styles.mapScene} aria-hidden="true">
                <svg className={`${styles.mapLines} ${styles.mapHorizontalLines}`} viewBox="0 0 100 100" preserveAspectRatio="none">
                    <path className={styles.mapTeamLine} d="M34 50 H66 M18 50 H34 M66 50 H82" vectorEffect="non-scaling-stroke" />
                    <path d="M18 50 C11 50 11 25 4 25 M18 50 C11 50 11 75 4 75 M82 50 C89 50 89 25 96 25 M82 50 C89 50 89 75 96 75" vectorEffect="non-scaling-stroke" />
                </svg>
                <svg className={`${styles.mapLines} ${styles.mapVerticalLines}`} viewBox="0 0 100 100" preserveAspectRatio="none">
                    <path className={styles.mapTeamLine} d="M50 32 V68 M50 18 V32 M50 68 V82" vectorEffect="non-scaling-stroke" />
                    <path d="M50 18 C50 11 24 12 24 5 M50 18 C50 11 76 12 76 5 M50 82 C50 89 24 88 24 95 M50 82 C50 89 76 88 76 95" vectorEffect="non-scaling-stroke" />
                </svg>
                <div className={`${styles.mapNode} ${styles.mapWorkspace}`} data-map-node="workspace">
                    <BuildingOffice2Icon />
                    <span className={styles.mapWorkspaceLabel}>{t("mapWorkspace")}</span>
                </div>
                {nodes.flatMap(({ kind, icon: Icon, appearance, positions }) => positions.map((position) => <div key={position} className={`${styles.mapNode} ${appearance} ${position}`} data-map-node={kind}><Icon /></div>))}
            </div>
            <figcaption className="mt-5 flex flex-wrap justify-center gap-x-8 gap-y-3 text-sm text-muted-foreground">
                {legend.map(([Icon, label, appearance]) => <span key={label} className="flex items-center gap-2"><span aria-hidden="true" className={`${styles.mapLegendIcon} ${appearance}`}><Icon /></span>{t(label)}</span>)}
            </figcaption>
        </figure>
    );
}

export function WorkflowPreview({ t }: { t: LandingTranslation }) {
    const connections = [
        { position: styles.workflowLead, path: "M32 50 H100", highlighted: true },
        { position: styles.workflowYes, path: "M0 50 H20 Q30 50 30 40 V35 Q30 25 40 25 H68", highlighted: true },
        { position: styles.workflowNo, path: "M0 50 H20 Q30 50 30 60 V65 Q30 75 40 75 H68", highlighted: false },
    ];
    const nodes = [
        { id: "trigger", icon: CheckCircleIcon, label: "workflow_trigger_title", position: styles.workflowTrigger },
        { id: "condition", icon: FunnelIcon, label: "workflow_condition_title", position: styles.workflowCondition },
        { id: "task", icon: ClipboardDocumentListIcon, label: "workflow_task_title", position: styles.workflowTask },
        { id: "activity", icon: ClockIcon, label: "workflow_activity_title", position: styles.workflowActivity },
    ] as const;
    return (
        <figure className="mt-10" aria-label={t("workflowDiagramLabel")}>
            <WorkflowSequence>
                <div className={styles.workflowDiagram}>
                    {connections.map(({ position, path, highlighted }) => <div key={position} className={`${styles.workflowConnector} ${position}`} aria-hidden="true">
                        <svg className={styles.workflowTrack} viewBox="0 0 100 100" preserveAspectRatio="none"><path d={path} vectorEffect="non-scaling-stroke" /></svg>
                        {highlighted && <svg className={styles.workflowFlow} viewBox="0 0 100 100" preserveAspectRatio="none"><path d={path} vectorEffect="non-scaling-stroke" /></svg>}
                    </div>)}
                    {nodes.map(({ id, icon: Icon, label, position }) => <div key={id} className={`${styles.workflowBox} ${position}`} data-workflow-node={id}>
                        <span className={styles.workflowBoxIcon}><Icon aria-hidden="true" /></span>
                        <p>{t(label)}</p>
                        {id === "task" && <span className={`${styles.workflowBranchLabel} text-brand-dark dark:text-brand`}>{t("workflow_yes")}</span>}
                        {id === "activity" && <span className={`${styles.workflowBranchLabel} text-muted-foreground`}>{t("workflow_no")}</span>}
                    </div>)}
                </div>
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
