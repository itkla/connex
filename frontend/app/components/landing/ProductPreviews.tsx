import { ArrowRightIcon, BuildingOffice2Icon, CheckCircleIcon, ChevronDownIcon, UserGroupIcon } from "@heroicons/react/24/outline";
import { cn } from "@/lib/utils";
import { AskConnexConversation } from "./AskConnexConversation";
import { SampleEvidence } from "./SampleEvidence";
import { SAMPLE_WORKSPACE, type LandingTranslation } from "./sampleWorkspace";
import styles from "./landing.module.css";

function SampleCaption({ t }: { t: LandingTranslation }) {
    return <figcaption className="mt-4 text-sm text-muted-foreground"><time dateTime={SAMPLE_WORKSPACE.asOf}>{t("sampleCaption")}</time></figcaption>;
}

function Status({ children, risk = false }: { children: React.ReactNode; risk?: boolean }) {
    return <span className={cn("inline-flex items-center gap-2 text-sm font-medium", risk && "rounded-full bg-risk-high/10 px-3 py-1")}>
        <span aria-hidden="true" className={cn("size-2 shrink-0 rounded-full", risk ? "bg-risk-high" : "bg-brand")} />{children}
    </span>;
}

/** A broad customer workspace, with intelligence attached to ordinary CRM work. */
export function CustomerWorkspacePreview({ t }: { t: LandingTranslation }) {
    return (
        <figure className="mt-12 sm:mt-16">
            <div className="overflow-hidden rounded-2xl border border-border bg-card">
                <div className="flex flex-wrap items-center justify-between gap-3 border-b border-border bg-muted/40 px-5 py-4 sm:px-8">
                    <p className="flex items-center gap-2 text-sm font-medium"><BuildingOffice2Icon aria-hidden="true" className="size-5" />{t("workspaceLabel")}</p>
                    <p className="text-sm text-muted-foreground">{t("workspaceOwner")}</p>
                </div>
                <div className="hidden lg:grid lg:grid-cols-[0.85fr_1.4fr_1fr]">
                    <div className="border-b border-border p-5 sm:p-8 lg:border-r lg:border-b-0">
                        <p className="text-2xl font-semibold tracking-tight">{t("sampleCompany")}</p>
                        <p className="mt-2 text-sm text-muted-foreground">{t("sampleCompanyMeta")}</p>
                        <p className="mt-7 text-sm font-semibold">{t("recordContacts")}</p>
                        <ul className="mt-3 space-y-4 text-sm">
                            {(["mari", "kenji", "rina"] as const).map((person) => <li key={person}><p>{t(`sample_${person}`)}</p><p className="mt-0.5 text-muted-foreground">{t(`sample_${person}_role`)}</p></li>)}
                        </ul>
                    </div>
                    <div className="border-b border-border p-5 sm:p-8 lg:border-r lg:border-b-0">
                        <p className="text-sm text-muted-foreground">{t("recordDeal")}</p>
                        <div className="mt-2 flex flex-wrap items-start justify-between gap-3"><p className="text-xl font-semibold">{t("sampleRenewal")}</p><Status risk>{t("sampleRisk")}</Status></div>
                        <p className="mt-4 text-3xl font-semibold tabular-nums">{t("sampleRenewalAmount")}</p>
                        <p className="mt-2 text-sm text-muted-foreground">{t("sampleRenewalStage")}</p>
                        <div className="mt-7 border-t border-border pt-5">
                            <p className="text-sm font-semibold">{t("recordActivity")}</p>
                            <p className="mt-3 text-base">{t("source_review_title")}</p>
                            <time dateTime={SAMPLE_WORKSPACE.lastContact} className="mt-1 block text-sm text-muted-foreground">{t("source_review_date")}</time>
                            <p className="mt-3 text-sm leading-relaxed text-muted-foreground">{t("workspaceActivity")}</p>
                        </div>
                    </div>
                    <div className="bg-brand/5 p-5 sm:p-8">
                        <p className="text-sm font-semibold">{t("recordTask")}</p>
                        <p className="mt-3 text-xl font-medium leading-snug">{t("sampleTask")}</p>
                        <p className="mt-3 text-sm text-muted-foreground">{t("sampleTaskOwner")}</p>
                        <time dateTime={SAMPLE_WORKSPACE.followUpDue} className="mt-2 block text-sm">{t("sampleTaskDue")}</time>
                        <div className="mt-8 border-t border-border pt-5">
                            <p className="text-sm font-semibold">{t("workspaceSignal")}</p>
                            <p className="mt-2 text-sm leading-relaxed text-muted-foreground">{t("workspaceSignalBody")}</p>
                        </div>
                    </div>
                </div>
                <div className="p-5 sm:p-8 lg:hidden">
                    <p className="text-xl font-semibold">{t("sampleCompany")}</p>
                    <p className="mt-1 text-sm text-muted-foreground">{t("sampleCompanyMeta")}</p>
                    <div className="mt-5 border-t border-border pt-5">
                        <div className="flex flex-wrap items-center justify-between gap-3">
                            <p className="font-semibold">{t("sampleRenewal")}</p>
                            <Status risk>{t("sampleRisk")}</Status>
                        </div>
                        <p className="mt-3 text-2xl font-semibold tabular-nums">{t("sampleRenewalAmount")}</p>
                        <p className="mt-1 text-sm text-muted-foreground">{t("sampleRenewalStage")}</p>
                    </div>
                    <div className="mt-5 border-t border-border pt-5">
                        <p className="text-sm text-muted-foreground">{t("recordTask")}</p>
                        <p className="mt-2 text-lg font-semibold">{t("sampleTask")}</p>
                        <p className="mt-2 text-sm text-muted-foreground">{t("sampleTaskOwner")}</p>
                        <time dateTime={SAMPLE_WORKSPACE.followUpDue} className="mt-1 block text-sm">{t("sampleTaskDue")}</time>
                    </div>
                    <details className="group mt-5 border-t border-border">
                        <summary className="flex min-h-11 cursor-pointer list-none items-center justify-between gap-3 py-3 text-sm font-medium [&::-webkit-details-marker]:hidden">
                            {t("workspaceDetails")}
                            <ChevronDownIcon aria-hidden="true" className="size-4 shrink-0 group-open:rotate-180" />
                        </summary>
                        <p className="mt-3 text-sm font-semibold">{t("recordContacts")}</p>
                        <ul className="mt-3 space-y-3 text-sm">
                            {(["mari", "kenji", "rina"] as const).map((person) => (
                                <li key={person}><p>{t(`sample_${person}`)}</p><p className="text-muted-foreground">{t(`sample_${person}_role`)}</p></li>
                            ))}
                        </ul>
                        <div className="mt-5 border-t border-border pt-5">
                            <p className="text-sm font-semibold">{t("source_review_title")}</p>
                            <time dateTime={SAMPLE_WORKSPACE.lastContact} className="mt-1 block text-sm text-muted-foreground">{t("source_review_date")}</time>
                            <p className="mt-2 text-sm leading-relaxed text-muted-foreground">{t("workspaceActivity")}</p>
                            <p className="mt-3 text-sm leading-relaxed">{t("workspaceSignalBody")}</p>
                        </div>
                    </details>
                </div>
            </div>
            <SampleCaption t={t} />
        </figure>
    );
}

/** Explains how customer information connects without requiring sample context. */
export function ConnectedRecordPreview({ t }: { t: LandingTranslation }) {
    return (
        <div className="mt-10 border-t border-border">
            <div className="grid gap-8 py-8 md:grid-cols-[0.8fr_1.7fr] md:gap-16">
                <div><BuildingOffice2Icon aria-hidden="true" className="mb-4 size-7 text-brand-dark dark:text-brand" /><p className="text-xl font-semibold">{t("recordCompany")}</p><p className="mt-2 text-base leading-relaxed text-muted-foreground">{t("recordCompanyAnnotation")}</p></div>
                <dl className="grid gap-x-10 gap-y-7 sm:grid-cols-2">
                    {(["Contacts", "Deal", "Activity", "NoteTask"] as const).map((kind) => <div key={kind}>
                        <dt className="font-semibold">{t(`record${kind}`)}</dt>
                        <dd className="mt-2 text-base leading-relaxed text-muted-foreground">{t(`record${kind}Annotation`)}</dd>
                    </div>)}
                </dl>
            </div>
        </div>
    );
}

export function AskConnexPreview({ t }: { t: LandingTranslation }) {
    return (
        <figure>
            <AskConnexConversation key={t("askPrompt")} title={t("askBriefTitle")} prompt={t("askPrompt")} userLabel={t("askYou")} assistantLabel={t("askName")} exampleLabel={t("askExample")} thinkingLabel={t("askThinking")} replayLabel={t("askReplay")} skipLabel={t("askSkip")}>
                <p className={`${styles.askResponsePart} text-xl font-medium leading-relaxed`}>{t("askFinding")}</p>
                <ul className={`${styles.askResponsePart} mt-5 list-disc space-y-3 pl-5 text-base leading-relaxed text-muted-foreground`}><li>{t("askFindingReview")}</li><li>{t("askFindingPricing")}</li></ul>
                <div className={`${styles.askResponsePart} my-6 border-t border-border pt-5`}><p className="font-semibold">{t("askNext")}</p><p className="mt-2 text-base leading-relaxed">{t("askNextBody")}</p></div>
                <div className={styles.askResponsePart}>
                    <p className="mb-2 text-sm text-muted-foreground">{t("askSources")}</p>
                    <SampleEvidence source="review" t={t} />
                    <SampleEvidence source="pricing" t={t} />
                </div>
            </AskConnexConversation>
            <SampleCaption t={t} />
        </figure>
    );
}

export function MapPreview({ t }: { t: LandingTranslation }) {
    return (
        <figure className="mt-10">
            <div className="rounded-2xl border border-border bg-card p-5 sm:p-8 lg:p-10">
                <div className="hidden sm:block">
                    <div className={styles.network}>
                        <svg className={styles.networkLines} viewBox="0 0 900 420" preserveAspectRatio="none" aria-hidden="true">
                            <path d="M150 45 L150 360 M750 45 L750 360" fill="none" stroke="currentColor" strokeWidth="1.5" strokeDasharray="6 6" />
                            <path d="M450 210 L150 360 M450 210 L450 360 M450 210 L750 360" fill="none" stroke="currentColor" strokeWidth="1.5" />
                        </svg>
                        <div className={styles.networkItem}><UserGroupIcon aria-hidden="true" className="mx-auto mb-2 size-5" /><p className="font-semibold">{t("sampleMisaki")}</p><p className="mt-1 text-sm text-muted-foreground">{t("mapMember")}</p></div>
                        <div aria-hidden="true" />
                        <div className={styles.networkItem}><UserGroupIcon aria-hidden="true" className="mx-auto mb-2 size-5" /><p className="font-semibold">{t("sampleAya")}</p><p className="mt-1 text-sm text-muted-foreground">{t("mapMember")}</p></div>
                        <div aria-hidden="true" />
                        <div className={`${styles.networkItem} ring-2 ring-brand`}><BuildingOffice2Icon aria-hidden="true" className="mx-auto mb-2 size-6 text-brand-dark dark:text-brand" /><p className="font-semibold">{t("sampleCompany")}</p><p className="mt-1 text-sm text-muted-foreground">{t("mapCompany")}</p></div>
                        <div aria-hidden="true" />
                        {(["mari", "kenji", "rina"] as const).map((person) => <div className={styles.networkItem} key={person}><p className="font-semibold">{t(`sample_${person}`)}</p><p className="mt-1 text-sm text-muted-foreground">{t(`sample_${person}_role`)}</p></div>)}
                    </div>
                    <ul className="mt-8 flex flex-wrap gap-x-8 gap-y-3 border-t border-border pt-5 text-sm text-muted-foreground">
                        <li className="flex items-center gap-2"><span aria-hidden="true" className="w-8 border-t border-brand-dark" />{t("mapEmployment")}</li>
                        <li className="flex items-center gap-2"><span aria-hidden="true" className="w-8 border-t border-dashed border-brand-dark" />{t("mapInteraction")}</li>
                    </ul>
                </div>
                <div className="sm:hidden"><p className="text-xl font-semibold">{t("sampleCompany")}</p><p className="mt-3 text-sm text-muted-foreground">{t("mapMobileCaption")}</p><ul className="mt-4 divide-y divide-border">{(["mari", "kenji", "rina"] as const).map((person) => <li className="py-4" key={person}><p className="font-semibold">{t(`sample_${person}`)}</p><p className="mt-1 text-sm text-muted-foreground">{t(`sample_${person}_role`)}</p></li>)}</ul></div>
                <div className="mt-6 grid gap-4 border-t border-border pt-6 md:grid-cols-[1fr_1.4fr] md:gap-10">
                    <div><p className="font-semibold">{t("mapSummaryTitle")}</p><p className="mt-2 text-sm leading-relaxed text-muted-foreground">{t("mapSummary")}</p></div>
                    <p className="text-base leading-relaxed">{t("mapEvidence")}</p>
                </div>
            </div>
            <SampleCaption t={t} />
        </figure>
    );
}

export function WorkflowPreview({ t }: { t: LandingTranslation }) {
    return (
        <figure className="mt-10">
            <div className="rounded-2xl bg-muted/50 p-5 sm:p-8">
                <p className="mb-7 flex items-center gap-2 font-semibold"><CheckCircleIcon aria-hidden="true" className="size-5 text-brand-dark dark:text-brand" />{t("workflowRecipe")}</p>
                <ol className="grid gap-8 md:grid-cols-3">
                    {(["trigger", "task", "activity"] as const).map((step, index) => <li key={step} className="relative min-w-0 border-t border-border pt-5 md:pr-5">
                        <p className="text-sm text-muted-foreground">{t(`workflow_${step}_kind`)}</p><p className="mt-2 text-lg font-semibold">{t(`workflow_${step}_title`)}</p><p className="mt-3 text-base leading-relaxed text-muted-foreground">{t(`workflow_${step}_body`)}</p>
                        {index < 2 && <ArrowRightIcon aria-hidden="true" className="absolute top-5 -right-3 hidden size-5 text-brand-dark md:block dark:text-brand" />}
                    </li>)}
                </ol>
                <p className="mt-7 border-t border-border pt-5 text-sm leading-relaxed text-muted-foreground">{t("workflowReview")}</p>
            </div>
            <SampleCaption t={t} />
        </figure>
    );
}

export function TeamworkPreview({ t }: { t: LandingTranslation }) {
    return (
        <figure className="mt-10">
            <div className="grid divide-y divide-border rounded-2xl border border-border bg-card md:grid-cols-2 md:divide-y-0">
                <div className="p-5 sm:p-8 md:border-r md:border-border">
                    <p className="text-sm text-muted-foreground">{t("teamPipelineLabel")}</p><p className="mt-3 text-xl font-semibold">{t("sampleCompany")}</p>
                    <dl className="mt-6 space-y-5">{(["won", "open"] as const).map((stage) => <div key={stage} className="flex flex-wrap justify-between gap-3"><dt><p className="font-medium">{t(`teamDeal_${stage}`)}</p><p className="mt-1 text-sm text-muted-foreground">{t(`teamDeal_${stage}_date`)}</p></dt><dd><Status risk={stage === "open"}>{t(`teamDeal_${stage}_status`)}</Status></dd></div>)}</dl>
                    <p className="mt-6 border-t border-border pt-5 text-sm leading-relaxed text-muted-foreground">{t("teamAnalyticsExample")}</p>
                </div>
                <div className="p-5 sm:p-8">
                    <p className="text-sm text-muted-foreground">{t("teamQuoteLabel")}</p><p className="mt-3 text-xl font-semibold">{t("teamQuoteTitle")}</p><p className="mt-3 text-3xl font-semibold tabular-nums">{t("sampleRenewalAmount")}</p><p className="mt-3 text-sm text-muted-foreground">{t("teamQuoteState")}</p>
                    <div className="mt-6 border-t border-border pt-5"><p className="text-sm font-semibold">{t("teamHandover")}</p><p className="mt-2 text-base leading-relaxed text-muted-foreground">{t("teamHandoverBody")}</p></div>
                </div>
            </div>
            <SampleCaption t={t} />
        </figure>
    );
}
