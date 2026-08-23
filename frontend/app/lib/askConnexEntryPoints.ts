import type { AiAssistantSkill, AiChatPageContextKind, RadarFamily } from '@/app/lib/types';

/**
 * How many jobs one surface offers at once.
 *
 * A short, stable list is a menu of the work this page supports; a long one is a prompt gallery,
 * which is the thing Ask Connex is deliberately not. Four is the ceiling the product contract sets,
 * and the order never reshuffles, so the same page always offers the same jobs in the same places.
 */
export const ASK_CONNEX_JOB_LIMIT = 4;

/**
 * One job a surface can offer, named in the user's language and backed by a declared capability.
 *
 * `skillKey` is the whole gate: a job is offered only when the server's own directory says this
 * member can run that capability here. Nothing in this table can make a page advertise work the
 * server would then decline, and a capability that has not shipped yet simply never appears.
 */
export type AskConnexJob = {
    /** Stable id, and the fragment its label and prompt copy are keyed under. */
    id: string;
    /** Declared catalog key whose presence in the directory makes this job offerable. */
    skillKey: string;
    /** Surfaces this job reads naturally on, independent of what the skill can anchor to. */
    contexts: readonly AiChatPageContextKind[];
};

/**
 * Every job Ask Connex offers from a surface, in the order surfaces offer them.
 *
 * The same capability is a different job on a different record — briefing a person is briefing a
 * relationship, briefing a company is briefing an account — so each reading is its own row with its
 * own copy rather than one label bent to fit both.
 *
 * Rows whose capability this build cannot run are still declared here. They cost nothing while the
 * directory withholds their key, and they light up in the right place, with copy already written,
 * on the day the capability ships.
 */
const ASK_CONNEX_JOBS: readonly AskConnexJob[] = [
    { id: 'relationshipBrief', skillKey: 'relationship_brief_v1', contexts: ['person'] },
    { id: 'companyBrief', skillKey: 'relationship_brief_v1', contexts: ['company'] },
    { id: 'relationshipCooling', skillKey: 'relationship_cooling_explanation_v1', contexts: ['person'] },
    { id: 'companyCooling', skillKey: 'relationship_cooling_explanation_v1', contexts: ['company'] },
    { id: 'dealRisk', skillKey: 'deal_risk_review_v1', contexts: ['deal'] },
    { id: 'contactActivity', skillKey: 'activity_digest_v1', contexts: ['person'] },
    { id: 'companyActivity', skillKey: 'activity_digest_v1', contexts: ['company'] },
    { id: 'dealActivity', skillKey: 'activity_digest_v1', contexts: ['deal'] },
    { id: 'companyPipeline', skillKey: 'pipeline_attention_review_v1', contexts: ['company'] },
    { id: 'dealPipeline', skillKey: 'pipeline_attention_review_v1', contexts: ['deal'] },
    { id: 'relationshipChanges', skillKey: 'relationship_change_summary_v1', contexts: ['person', 'company'] },
    { id: 'dealChanges', skillKey: 'relationship_change_summary_v1', contexts: ['deal'] },
    { id: 'introPath', skillKey: 'introduction_path_explanation_v1', contexts: ['person', 'company'] },
    { id: 'meetingPreparation', skillKey: 'meeting_preparation_v1', contexts: ['person', 'company', 'deal'] },
    { id: 'meetingFollowUp', skillKey: 'meeting_follow_up_extraction_v1', contexts: ['person', 'company', 'deal'] },
    { id: 'followUpDraft', skillKey: 'follow_up_draft_v1', contexts: ['person', 'company', 'deal'] },
    { id: 'stakeholderGaps', skillKey: 'stakeholder_gap_analysis_v1', contexts: ['company', 'deal'] },
    { id: 'companyReview', skillKey: 'company_review_v1', contexts: ['company'] },
    { id: 'commitments', skillKey: 'commitment_extraction_v1', contexts: ['person', 'company', 'deal'] },
    { id: 'dataQuality', skillKey: 'data_quality_review_v1', contexts: ['person', 'company', 'deal'] },
    { id: 'workspaceWorkBrief', skillKey: 'daily_work_brief_v1', contexts: [] },
    { id: 'workspacePipeline', skillKey: 'pipeline_attention_review_v1', contexts: [] },
    { id: 'workspaceActivity', skillKey: 'activity_digest_v1', contexts: [] },
    { id: 'workspaceReport', skillKey: 'natural_language_report_v1', contexts: [] },
];

/** Every job this client declares, so a test can prove each one carries copy in both languages. */
export const ASK_CONNEX_JOBS_ALL: readonly AskConnexJob[] = ASK_CONNEX_JOBS;

/**
 * The context a surface offers jobs for: the record kind it is about, and whether it actually has a
 * record to anchor to.
 *
 * A record page has both. The workspace has neither, so it offers only the jobs that need no
 * subject — a capability that refuses without a record must not be offered where there is none.
 */
export type AskConnexJobContext = {
    kind: AiChatPageContextKind | null;
    hasSubject: boolean;
};

/**
 * The job context a conversation surface offers from.
 *
 * Only a record the surface is genuinely about anchors a record job. A browser selection is not a
 * subject: every record job here is written about one relationship, one account, or one deal, and
 * offering "Brief me on this relationship" against twelve selected contacts would promise a reading
 * no capability performs. Selection-shaped work has no declared capability behind it yet, so a
 * surface carrying only a selection offers the jobs that need no subject at all rather than
 * borrowing the first selected record's kind and speaking about the rest as if they were not there.
 *
 * @param recordKind the record this surface is about, or null when it is about none
 * @returns the context its offers are resolved from
 */
export function askConnexJobContext(
    recordKind: AiChatPageContextKind | null,
): AskConnexJobContext {
    return { kind: recordKind, hasSubject: recordKind !== null };
}

function offersContext(skill: AiAssistantSkill, kind: AiChatPageContextKind | null): boolean {
    return kind === null || skill.contextKinds.includes(kind);
}

/**
 * Resolves what a surface may offer from what the server says this member can run.
 *
 * Four rules, all of which have to hold: the capability is in the caller's directory, the
 * capability itself can anchor to this kind of record, this client has product copy for that
 * reading, and a capability that refuses without a record has one. The result keeps the catalog's
 * own order and is capped, so it is stable between renders rather than a fresh arrangement each
 * time the page re-renders.
 *
 * @param skills the caller's directory, in catalog order
 * @param context the record kind the surface is about and whether it carries one
 * @param limit how many jobs the surface has room to offer
 * @returns the offerable jobs, ordered and capped
 */
export function askConnexJobs(
    skills: readonly AiAssistantSkill[],
    context: AskConnexJobContext,
    limit: number = ASK_CONNEX_JOB_LIMIT,
): AskConnexJob[] {
    if (limit <= 0) return [];
    const runnable = new Map(skills.map((skill) => [skill.key, skill]));
    const offered: AskConnexJob[] = [];
    for (const job of ASK_CONNEX_JOBS) {
        if (context.kind !== null && !job.contexts.includes(context.kind)) continue;
        if (context.kind === null && job.contexts.length > 0) continue;
        const skill = runnable.get(job.skillKey);
        if (skill === undefined) continue;
        if (!offersContext(skill, context.kind)) continue;
        if (skill.needsSubject && !context.hasSubject) continue;
        offered.push(job);
        if (offered.length === limit) break;
    }
    return offered;
}

/**
 * Which declared capability can explain each kind of Radar signal.
 *
 * Radar owns what a signal is, when it fired, and what it is evidenced by; Ask Connex is only asked
 * to explain one, and only where a capability exists that reads the same ground Radar read. A family
 * with no such capability offers nothing rather than handing the question to a general answer that
 * would restate the card back to the reader.
 */
const ASK_CONNEX_SIGNAL_EXPLANATIONS: Readonly<Record<RadarFamily, string>> = {
    relationship_decay: 'relationship_cooling_explanation_v1',
    deal_risk: 'deal_risk_review_v1',
    warm_path: 'introduction_path_explanation_v1',
};

/**
 * Whether this member can ask Ask Connex to explain one Radar signal.
 *
 * @param skills the caller's directory
 * @param family the signal's family
 * @param subject the record the signal is about
 * @returns whether the explanation is genuinely available for this signal
 */
export function canExplainAskConnexSignal(
    skills: readonly AiAssistantSkill[],
    family: RadarFamily,
    subject: AiChatPageContextKind,
): boolean {
    const skillKey = ASK_CONNEX_SIGNAL_EXPLANATIONS[family];
    const skill = skills.find((candidate) => candidate.key === skillKey);
    return skill !== undefined && skill.contextKinds.includes(subject);
}

/**
 * Whether a surface has anything to offer at all.
 *
 * An entry point with no jobs behind it is a control that opens an empty menu, so surfaces ask this
 * before rendering one and stay entirely absent when Ask Connex cannot help here — the deterministic
 * page underneath is untouched either way.
 */
export function hasAskConnexJobs(
    skills: readonly AiAssistantSkill[],
    context: AskConnexJobContext,
): boolean {
    return askConnexJobs(skills, context, 1).length > 0;
}
